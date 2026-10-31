package com.takin1217.dividendvault.sync

import android.content.Context
import com.takin1217.dividendvault.VaultApp
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
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    fun syncSelected(force: Boolean) {
        val db = app.database.writableDatabase
        val today = LocalDate.now().toString()
        val symbols = mutableListOf<String>()
        db.rawQuery(
            "SELECT symbol FROM symbols WHERE selected=1 OR instrument='BENCHMARK' ORDER BY market, pool_rank",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) symbols += cursor.getString(0)
        }
        symbols.forEachIndexed { index, symbol ->
            app.status.value = "${index + 1}/${symbols.size} $symbol"
            if (!force) {
                db.rawQuery("SELECT status, last_success FROM sync_state WHERE symbol=?", arrayOf(symbol)).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getString(0) == "ok" && (cursor.getString(1) ?: "").startsWith(today)) {
                        return@forEachIndexed
                    }
                }
            }
            try {
                val last = db.rawQuery("SELECT MAX(date) FROM bars WHERE symbol=?", arrayOf(symbol)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                val chart = fetchChart(symbol, last)
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
            } catch (error: Exception) {
                db.execSQL(
                    "INSERT INTO sync_state(symbol, bar_count, div_count, last_attempt, last_error, status) VALUES(?,0,0,?,?,'error') " +
                        "ON CONFLICT(symbol) DO UPDATE SET last_attempt=excluded.last_attempt, last_error=excluded.last_error, status='error'",
                    arrayOf(symbol, Instant.now().toString(), error.message?.take(300)),
                )
            }
            Thread.sleep(350)
        }
        app.status.value = "동기화 완료 ${symbols.size}종목"
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
        val stamps = result.getJSONArray("timestamp")
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
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return text
        }
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
