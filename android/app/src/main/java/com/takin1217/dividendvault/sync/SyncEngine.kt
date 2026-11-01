package com.takin1217.dividendvault.sync

import android.content.Context
import com.takin1217.dividendvault.Phase
import com.takin1217.dividendvault.VaultApp
import com.takin1217.dividendvault.notify.VaultNotifier
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SyncEngine(context: Context) {
    private val app = context.applicationContext as VaultApp
    private val cookies = MemoryCookies()
    private         val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    fun syncSelected(force: Boolean): Int {
        val db = app.database.writableDatabase
        val today = LocalDate.now().toString()
        val symbols = mutableListOf<String>()
        var failed = 0
        app.phase.value = Phase.Running
        ensureUniverse(db)
        db.rawQuery(
            "SELECT symbol FROM symbols WHERE selected=1 OR instrument='BENCHMARK' ORDER BY market, pool_rank",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) symbols += cursor.getString(0)
        }
        val notifier = VaultNotifier(app)
        val again = mutableListOf<String>()
        symbols.forEachIndexed { index, symbol ->
            app.phase.value = Phase.Running
            app.status.value = "${index + 1}/${symbols.size} $symbol"
            if (index % 20 == 0) notifier.progress("일봉 수신", app.status.value)
            if (freshToday(db, symbol, today, force)) return@forEachIndexed
            if (!pullSymbol(db, symbol)) again += symbol
            Thread.sleep(200)
        }
        if (again.isNotEmpty()) {
            Thread.sleep(1500)
            again.forEachIndexed { index, symbol ->
                app.status.value = "다시 받는 중 ${index + 1}/${again.size} $symbol"
                if (index % 10 == 0) notifier.progress("일봉 수신", app.status.value)
                if (!pullSymbol(db, symbol)) failed += 1
                Thread.sleep(400)
            }
        }
        app.phase.value = if (failed == 0) Phase.Done else Phase.Error
        app.status.value = if (symbols.isEmpty()) {
            "고를 종목이 없습니다. 네트워크를 확인한 뒤 다시 받으세요."
        } else {
            "동기화 ${symbols.size}종목, 오류 $failed"
        }
        return failed
    }

    private fun ensureUniverse(db: android.database.sqlite.SQLiteDatabase) {
        val raw = db.rawQuery("SELECT value FROM meta WHERE key='settings'", null).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getString(0)
        }
        val settings = JSONObject(raw)
        val markets = settings.getJSONArray("markets")
        val minYield = settings.optDouble("min_yield", 0.02)
        val maxYield = settings.optDouble("max_yield", 0.12)
        for (index in 0 until markets.length()) {
            val market = markets.getJSONObject(index)
            if (!market.optBoolean("enabled")) continue
            val code = market.getString("code")
            val name = market.optString("name", code)
            val target = market.optInt("target_symbols")
            val benchmark = market.optString("benchmark")
            if (benchmark.isNotBlank()) rememberSymbol(db, benchmark, code, benchmark, "BENCHMARK", null, null, 0, false)
            val have = db.rawQuery(
                "SELECT COUNT(*) FROM symbols WHERE market=? AND selected=1 AND instrument!='BENCHMARK'",
                arrayOf(code),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            if (have >= target) continue
            app.status.value = "$name 최소의 2배, $target 종목을 고르는 중"
            VaultNotifier(app).progress("종목 선정", app.status.value)
            try {
                if (market.optString("source") == "vndirect") fillVietnam(db, target) else fillYahoo(db, code, market.optString("yahoo_region"), target, minYield, maxYield)
            } catch (error: Exception) {
                app.status.value = "$name 종목 선정을 넘기고 받은 종목부터 이어 받습니다."
            }
        }
    }

    private fun freshToday(db: android.database.sqlite.SQLiteDatabase, symbol: String, today: String, force: Boolean): Boolean {
        if (force) return false
        return db.rawQuery("SELECT status, last_success FROM sync_state WHERE symbol=?", arrayOf(symbol)).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok" && (cursor.getString(1) ?: "").startsWith(today)
        }
    }

    private fun pullSymbol(db: android.database.sqlite.SQLiteDatabase, symbol: String): Boolean {
        return try {
            val last = db.rawQuery("SELECT MAX(date) FROM bars WHERE symbol=?", arrayOf(symbol)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val chart = fetchChart(symbol, last)
            if (chart.bars.isEmpty()) error("일봉이 없습니다")
            db.beginTransaction()
            try {
                chart.bars.forEach { bar -> upsertBar(db, symbol, bar) }
                chart.dividends.forEach { (date, amount) ->
                    db.execSQL(
                        "INSERT INTO dividends(symbol, ex_date, amount, source, updated_at) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(symbol, ex_date) DO UPDATE SET amount=excluded.amount, updated_at=excluded.updated_at",
                        arrayOf(symbol, date, amount, "yahoo", Instant.now().toString()),
                    )
                }
                val stats = db.rawQuery("SELECT MIN(date), MAX(date), COUNT(*) FROM bars WHERE symbol=?", arrayOf(symbol)).use { cursor ->
                    cursor.moveToFirst()
                    Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
                }
                db.execSQL(
                    "INSERT INTO sync_state(symbol, first_date, last_date, bar_count, div_count, last_attempt, last_success, last_error, status) " +
                        "VALUES(?,?,?,?,0,?,?,NULL,'ok') ON CONFLICT(symbol) DO UPDATE SET first_date=excluded.first_date, last_date=excluded.last_date, " +
                        "bar_count=excluded.bar_count, last_attempt=excluded.last_attempt, last_success=excluded.last_success, last_error=NULL, status='ok'",
                    arrayOf(symbol, stats.first, stats.second, stats.third, Instant.now().toString(), Instant.now().toString()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (error: Exception) {
            db.execSQL(
                "INSERT INTO sync_state(symbol, bar_count, div_count, last_attempt, last_error, status) VALUES(?,0,0,?,?,'error') " +
                    "ON CONFLICT(symbol) DO UPDATE SET last_attempt=excluded.last_attempt, last_error=excluded.last_error, status='error'",
                arrayOf(symbol, Instant.now().toString(), error.message?.take(300)),
            )
            false
        }
    }

    private fun fillYahoo(db: android.database.sqlite.SQLiteDatabase, market: String, region: String, target: Int, minYield: Double, maxYield: Double) {
        val accepted = mutableListOf<JSONObject>()
        var offset = 0
        while (accepted.size < target && offset < 8000) {
            val page = screen(region, 250, offset)
            val quotes = page.optJSONArray("quotes") ?: break
            if (quotes.length() == 0) break
            for (index in 0 until quotes.length()) {
                val quote = quotes.getJSONObject(index)
                val symbol = quote.optString("symbol")
                if (symbol.isBlank()) continue
                val yld = yieldFraction(quote) ?: continue
                if (yld < minYield || yld > maxYield) continue
                val name = quote.optString("longName").ifBlank { quote.optString("shortName") }
                if (name.contains("Preferred", true) || name.contains("Warrant", true)) continue
                accepted += quote
                if (accepted.size >= target) break
            }
            offset += quotes.length()
            if (offset >= page.optInt("total", offset) || quotes.length() < 250) break
            Thread.sleep(250)
        }
        if (accepted.size < target) {
            offset = 0
            while (accepted.size < target && offset < 8000) {
                val page = screen(region, 250, offset)
                val quotes = page.optJSONArray("quotes") ?: break
                if (quotes.length() == 0) break
                for (index in 0 until quotes.length()) {
                    val quote = quotes.getJSONObject(index)
                    if (accepted.any { it.optString("symbol") == quote.optString("symbol") }) continue
                    if (quote.optString("symbol").isBlank()) continue
                    accepted += quote
                    if (accepted.size >= target) break
                }
                offset += quotes.length()
                if (quotes.length() < 250) break
                Thread.sleep(250)
            }
        }
        accepted.forEachIndexed { rank, quote ->
            rememberSymbol(
                db,
                quote.getString("symbol"),
                market,
                quote.optString("longName").ifBlank { quote.optString("shortName") },
                "EQUITY",
                quote.optString("exchange"),
                yieldFraction(quote),
                rank + 1,
                true,
            )
        }
    }

    private fun fillVietnam(db: android.database.sqlite.SQLiteDatabase, target: Int) {
        val rows = mutableListOf<JSONObject>()
        var page = 1
        while (rows.size < target && page <= 20) {
            val url = "https://api-finfo.vndirect.com.vn/v4/stocks?q=type:stock~status:listed&size=100&page=$page"
            val body = JSONObject(get(url))
            val data = body.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val floor = item.optString("floor").uppercase()
                val code = item.optString("code").uppercase()
                if (floor !in setOf("HOSE", "HNX") || !code.matches(Regex("[A-Z]{2,5}"))) continue
                if (floor == "HNX") continue
                rows += item
                if (rows.size >= target) break
            }
            page += 1
            Thread.sleep(150)
        }
        rows.forEachIndexed { rank, item ->
            val code = item.optString("code").uppercase()
            rememberSymbol(db, "$code.VN", "VN", item.optString("companyNameEng").ifBlank { code }, "EQUITY", "HOSE", null, rank + 1, true)
        }
    }

    private fun screen(region: String, size: Int, offset: Int): JSONObject {
        val query = JSONObject()
            .put("operator", "AND")
            .put(
                "operands",
                org.json.JSONArray()
                    .put(JSONObject().put("operator", "EQ").put("operands", org.json.JSONArray().put("region").put(region)))
                    .put(JSONObject().put("operator", "GT").put("operands", org.json.JSONArray().put("forward_dividend_yield").put(0.001))),
            )
        val payload = JSONObject()
            .put("size", size)
            .put("offset", offset)
            .put("sortField", "intradaymarketcap")
            .put("sortType", "DESC")
            .put("quoteType", "EQUITY")
            .put("query", query)
        val crumb = java.net.URLEncoder.encode(crumb(), "UTF-8")
        val request = Request.Builder()
            .url("https://query1.finance.yahoo.com/v1/finance/screener?crumb=$crumb&lang=en-US&region=US")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(json))
            .build()
        var last = "종목 선정 실패"
        for (attempt in 0 until 3) {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code == 429 || response.code >= 500) {
                        last = "종목 선정 HTTP ${response.code}"
                        return@use
                    }
                    if (!response.isSuccessful) error("종목 선정 HTTP ${response.code}")
                    val result = JSONObject(text).getJSONObject("finance").getJSONArray("result")
                    if (result.length() == 0) error("종목 선정 결과가 없습니다.")
                    return result.getJSONObject(0)
                }
            } catch (error: java.io.IOException) {
                last = error.message ?: "종목 선정 네트워크 지연"
            }
            Thread.sleep(600L * (attempt + 1))
        }
        error(last)
    }

    private fun crumb(): String {
        val text = get("https://query1.finance.yahoo.com/v1/test/getcrumb").trim()
        if (text.isEmpty() || text.startsWith("{")) error("종목 선정 인증 실패")
        return text
    }

    private fun yieldFraction(quote: JSONObject): Double? {
        val trailing = quote.optDouble("trailingAnnualDividendYield", Double.NaN)
        if (!trailing.isNaN() && trailing > 0 && trailing <= 0.25) return trailing
        val dividend = quote.optDouble("dividendYield", Double.NaN)
        if (!dividend.isNaN() && dividend > 0 && dividend <= 30) return dividend / 100.0
        return null
    }

    private fun rememberSymbol(
        db: android.database.sqlite.SQLiteDatabase,
        symbol: String,
        market: String,
        name: String,
        instrument: String,
        exchange: String?,
        yieldFrac: Double?,
        rank: Int,
        selected: Boolean,
    ) {
        db.execSQL(
            "INSERT INTO symbols(symbol, market, name, instrument, exchange, currency, yield_frac, market_cap, avg_volume, selected, pool_rank, listed_date, select_note, updated_at) " +
                "VALUES(?,?,?,?,?,NULL,?,NULL,NULL,?,?,NULL,?,?) ON CONFLICT(symbol) DO UPDATE SET market=excluded.market, name=COALESCE(excluded.name, symbols.name), " +
                "instrument=excluded.instrument, exchange=COALESCE(excluded.exchange, symbols.exchange), yield_frac=COALESCE(excluded.yield_frac, symbols.yield_frac), " +
                "selected=excluded.selected, pool_rank=excluded.pool_rank, select_note=excluded.select_note, updated_at=excluded.updated_at",
            arrayOf(symbol, market, name, instrument, exchange, yieldFrac, if (selected) 1 else 0, rank, if (selected) "phone" else "benchmark", Instant.now().toString()),
        )
    }

    private fun upsertBar(db: android.database.sqlite.SQLiteDatabase, symbol: String, bar: Bar) {
        db.rawQuery("SELECT close FROM bars WHERE symbol=? AND date=?", arrayOf(symbol, bar.date)).use { cursor ->
            if (!cursor.moveToFirst()) {
                db.execSQL(
                    "INSERT INTO bars(symbol, date, open, high, low, close, adj_close, volume, source, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(symbol, bar.date, bar.open, bar.high, bar.low, bar.close, bar.adj, bar.volume, "yahoo", Instant.now().toString()),
                )
            } else if (changed(cursor.getDouble(0), bar.close)) {
                val old = cursor.getDouble(0)
                db.execSQL(
                    "INSERT INTO bar_revisions(symbol, date, field, old_value, new_value, revised_at) VALUES(?,?,?,?,?,?)",
                    arrayOf(symbol, bar.date, "close", old, bar.close, Instant.now().toString()),
                )
                db.execSQL(
                    "UPDATE bars SET open=?, high=?, low=?, close=?, adj_close=?, volume=?, source='yahoo', updated_at=? WHERE symbol=? AND date=?",
                    arrayOf(bar.open, bar.high, bar.low, bar.close, bar.adj, bar.volume, Instant.now().toString(), symbol, bar.date),
                )
            }
        }
    }

    private fun finite(value: Double, fallback: Double) = if (value.isNaN()) fallback else value

    private fun changed(old: Double, new: Double): Boolean {
        val scale = maxOf(abs(old), abs(new), 1e-9)
        return abs(old - new) / scale > 1e-8
    }

    private fun fetchChart(symbol: String, lastDate: String?): Chart {
        val end = Instant.now().epochSecond + 86400
        val start = if (lastDate.isNullOrBlank()) end - 3660L * 86400 else LocalDate.parse(lastDate).minusDays(14).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(symbol, "UTF-8")}" +
            "?interval=1d&period1=$start&period2=$end&events=div,split&includeAdjustedClose=true"
        val body = get(url)
        val chart = JSONObject(body).getJSONObject("chart")
        if (chart.has("error") && !chart.isNull("error")) error(chart.getJSONObject("error").optString("description"))
        val result = chart.getJSONArray("result").getJSONObject(0)
        val offset = result.getJSONObject("meta").optInt("gmtoffset")
        val stamps = result.optJSONArray("timestamp") ?: return Chart(emptyList(), emptyList())
        val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val adjusted = result.getJSONObject("indicators").optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
        val bars = mutableListOf<Bar>()
        for (index in 0 until stamps.length()) {
            val close = quote.getJSONArray("close").optDouble(index, Double.NaN)
            if (close.isNaN() || close <= 0) continue
            val day = Instant.ofEpochSecond(stamps.getLong(index) + offset).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val adj = adjusted?.optDouble(index, close) ?: close
            bars += Bar(
                day,
                finite(quote.getJSONArray("open").optDouble(index, close), close),
                finite(quote.getJSONArray("high").optDouble(index, close), close),
                finite(quote.getJSONArray("low").optDouble(index, close), close),
                close,
                if (adj.isNaN() || adj <= 0) close else adj,
                finite(quote.getJSONArray("volume").optDouble(index, 0.0), 0.0),
            )
        }
        val dividends = mutableListOf<Pair<String, Double>>()
        val events = result.optJSONObject("events")?.optJSONObject("dividends")
        if (events != null) {
            val keys = events.keys()
            while (keys.hasNext()) {
                val item = events.getJSONObject(keys.next())
                val amount = item.optDouble("amount")
                if (amount > 0) dividends += Instant.ofEpochSecond(item.getLong("date") + offset).atZone(ZoneOffset.UTC).toLocalDate().toString() to amount
            }
        }
        return Chart(bars, dividends)
    }

    private fun get(url: String): String {
        val urls = listOf(url, url.replace("://query1.", "://query2.")).distinct()
        var last = "일봉 수신 실패"
        for (attempt in 0 until 3) {
            for (candidate in urls) {
                try {
                    val request = Request.Builder().url(candidate).header("User-Agent", USER_AGENT).build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.code == 429 || response.code >= 500) {
                            last = "HTTP ${response.code}"
                            return@use
                        }
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        return text
                    }
                } catch (error: java.io.IOException) {
                    last = error.message ?: "네트워크 지연"
                }
            }
            Thread.sleep(600L * (attempt + 1))
        }
        error(last)
    }

    private data class Bar(val date: String, val open: Double, val high: Double, val low: Double, val close: Double, val adj: Double, val volume: Double)
    private data class Chart(val bars: List<Bar>, val dividends: List<Pair<String, Double>>)

    private class MemoryCookies : CookieJar {
        private val all = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            all.removeAll { cookie -> cookies.any { it.name == cookie.name && it.domain == cookie.domain } }
            all += cookies
        }
        override fun loadForRequest(url: HttpUrl) = all.filter { it.matches(url) }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 DividendVault/1.0"
    }
}
