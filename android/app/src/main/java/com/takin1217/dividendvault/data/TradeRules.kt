package com.takin1217.dividendvault.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

/** Online Korea Investment & Securities costs. Vietnam buys stay at or above 200,000,000 dong. */
object TradeRules {
    data class Schedule(
        val commission: Double,
        val buyTax: Double,
        val sellTax: Double,
        val minCommission: Double,
        val lot: Int,
        val minBuyNotional: Double,
        val waiveOddSell: Boolean,
    )

    private val schedules = mapOf(
        "US" to Schedule(0.0025, 0.0, 0.0000206, 0.0, 1, 0.0, false),
        "CN" to Schedule(0.0030, 0.0000841, 0.0005841, 0.0, 100, 0.0, false),
        "HK" to Schedule(0.0030, 0.001085, 0.001085, 0.0, 100, 0.0, false),
        "JP" to Schedule(0.0023, 0.0, 0.0, 0.0, 100, 0.0, false),
        "VN" to Schedule(0.0040, 0.0, 0.001, 800_000.0, 100, 200_000_000.0, true),
        "KR" to Schedule(0.000140527, 0.0, 0.0020, 0.0, 1, 0.0, false),
        "GB" to Schedule(0.0030, 0.005, 0.0, 0.0, 1, 0.0, false),
    )

    fun schedule(market: String) = schedules[market] ?: schedules.getValue("US")

    fun charge(market: String, side: String, notional: Double, oddLot: Boolean = false): Double {
        if (notional <= 0) return 0.0
        val item = schedule(market)
        var fee = notional * item.commission
        if (item.minCommission > 0 && !(oddLot && side == "sell" && item.waiveOddSell)) {
            fee = maxOf(fee, item.minCommission)
        }
        return fee + notional * if (side == "sell") item.sellTax else item.buyTax
    }

    fun orderShares(market: String, price: Double, minNotional: Double): Int {
        if (price <= 0) return 0
        val item = schedule(market)
        val lot = item.lot
        val floor = maxOf(minNotional, item.minBuyNotional)
        val oneLot = price * lot
        return if (floor <= 0 || oneLot + 1e-9 >= floor) lot else (ceil(floor / price / lot).toInt()) * lot
    }

    fun minNotional(settings: JSONObject, market: String): Double {
        val markets = settings.optJSONArray("markets") ?: return defaultNotional(market)
        for (index in 0 until markets.length()) {
            val row = markets.getJSONObject(index)
            if (row.optString("code") == market) return row.optDouble("min_order_notional", defaultNotional(market))
        }
        return defaultNotional(market)
    }

    fun defaultNotional(market: String): Double = when (market) {
        "US" -> 100.0
        "CN" -> 1_000.0
        "HK" -> 1_000.0
        "JP" -> 10_000.0
        "VN" -> 200_000_000.0
        "KR" -> 100_000.0
        "GB" -> 100.0
        else -> 0.0
    }

    fun buyShares(market: String, cash: Double, price: Double): Int {
        if (cash <= 0 || price <= 0) return 0
        val item = schedule(market)
        val lot = item.lot
        val minimumShares = if (item.minBuyNotional > 0) {
            (ceil(item.minBuyNotional / price / lot).toInt()) * lot
        } else {
            0
        }
        fun affordable(shares: Int): Boolean {
            if (shares <= 0) return false
            val notional = shares * price
            return notional + charge(market, "buy", notional) <= cash + 1e-6
        }
        if (minimumShares > 0 && !affordable(minimumShares)) return 0
        var low = minimumShares
        var high = maxOf(minimumShares, (floor(cash / price / lot).toInt()) * lot)
        var best = if (minimumShares > 0) minimumShares else 0
        while (low <= high) {
            val mid = ((low + high) / 2 / lot) * lot
            if (mid <= 0 || mid < minimumShares) {
                low = maxOf(mid + lot, if (minimumShares > 0) minimumShares else lot)
                continue
            }
            if (affordable(mid)) {
                best = mid
                low = mid + lot
            } else {
                high = mid - lot
            }
        }
        return best
    }

    data class Exit(val symbol: String, val kind: String, val shares: Int, val price: Double, val fee: Double, val pnl: Double, val sell: Boolean)

    fun exits(db: SQLiteDatabase, market: String, rows: List<Triple<String, Double, Double>>, prices: Map<String, Double>, asof: String, cutRatio: Double = 0.2): List<Exit> {
        return rows.mapNotNull { (symbol, qty, avg) ->
            val kind = dividendEvent(db, symbol, asof, cutRatio) ?: return@mapNotNull null
            val price = prices[symbol] ?: return@mapNotNull null
            if (price <= 0) return@mapNotNull null
            val item = schedule(market)
            val whole = (floor(qty / item.lot).toInt()) * item.lot
            val shares = when {
                whole >= item.lot -> whole
                market == "VN" && qty >= 1 -> floor(qty).toInt()
                item.lot == 1 -> floor(qty).toInt()
                else -> 0
            }
            val odd = shares in 1 until item.lot
            val fee = if (shares > 0) charge(market, "sell", shares * price, odd) else 0.0
            val pnl = if (shares > 0) (price - avg) * shares - fee else 0.0
            Exit(symbol, kind, shares, price, fee, pnl, pnl > 0 && shares > 0)
        }
    }

    fun settle(db: SQLiteDatabase, market: String, asof: String, cutRatio: Double = 0.2): List<String> {
        val account = db.rawQuery("SELECT id, cash FROM paper_accounts WHERE market=?", arrayOf(market)).use { cursor ->
            if (!cursor.moveToFirst()) return listOf("가상 계좌가 없습니다. 먼저 최신 비중을 반영하세요.")
            cursor.getInt(0) to cursor.getDouble(1)
        }
        val positions = mutableListOf<Triple<String, Double, Double>>()
        db.rawQuery("SELECT symbol, qty, avg_price FROM paper_positions WHERE account_id=?", arrayOf(account.first.toString())).use { cursor ->
            while (cursor.moveToNext()) positions += Triple(cursor.getString(0), cursor.getDouble(1), cursor.getDouble(2))
        }
        val prices = positions.associate { (symbol, _, _) ->
            symbol to db.rawQuery("SELECT close FROM bars WHERE symbol=? AND close>0 ORDER BY date DESC LIMIT 1", arrayOf(symbol)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
        }
        var cash = account.second
        val notes = mutableListOf<String>()
        exits(db, market, positions, prices, asof, cutRatio).forEach { item ->
            if (!item.sell) {
                notes += "${item.symbol} ${item.kind} · 수수료·세금 후 손실이라 보유"
                return@forEach
            }
            cash += item.shares * item.price - item.fee
            db.execSQL("DELETE FROM paper_positions WHERE account_id=? AND symbol=?", arrayOf(account.first, item.symbol))
            db.execSQL(
                "INSERT INTO paper_orders(account_id, symbol, side, qty, status, reason, created_at, filled_at, fill_price, fee) VALUES(?,?,?,?,?,?,datetime('now'),datetime('now'),?,?)",
                arrayOf(account.first, item.symbol, "sell", item.shares, "filled", "${item.kind} · 수수료·세금 후 이익 매도 · 손절 아님", item.price, item.fee),
            )
            notes += "${item.symbol} ${item.kind} · ${item.shares}주 매도 · 수수료·세금 후 이익"
        }
        db.execSQL("UPDATE paper_accounts SET cash=? WHERE id=?", arrayOf(cash, account.first))
        return notes.ifEmpty { listOf("배당 축소나 무배당으로 바로 팔 보유가 없습니다. 가격 하락만으로는 손절하지 않습니다.") }
    }

    fun review(db: SQLiteDatabase, market: String, asof: String, cutRatio: Double = 0.2): List<String> {
        val notes = mutableListOf<String>()
        val positions = mutableListOf<Triple<String, Double, Double>>()
        db.rawQuery(
            """
            SELECT p.symbol, p.qty, p.avg_price FROM paper_positions p
            JOIN paper_accounts a ON a.id=p.account_id WHERE a.market=?
            """.trimIndent(),
            arrayOf(market),
        ).use { cursor ->
            while (cursor.moveToNext()) positions += Triple(cursor.getString(0), cursor.getDouble(1), cursor.getDouble(2))
        }
        positions.forEach { (symbol, qty, avg) ->
            val kind = dividendEvent(db, symbol, asof, cutRatio) ?: return@forEach
            val price = db.rawQuery("SELECT close FROM bars WHERE symbol=? AND close>0 ORDER BY date DESC LIMIT 1", arrayOf(symbol)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
            if (price <= 0) return@forEach
            val item = schedule(market)
            val whole = (floor(qty / item.lot).toInt()) * item.lot
            val shares = if (whole >= item.lot) whole else if (market == "VN" && qty >= 1) floor(qty).toInt() else if (item.lot == 1) floor(qty).toInt() else 0
            val odd = shares in 1 until item.lot
            if (shares <= 0) {
                notes += "$symbol $kind · 최소 수량 미만이라 매도하지 않음"
                return@forEach
            }
            val fee = charge(market, "sell", shares * price, odd)
            val pnl = (price - avg) * shares - fee
            notes += if (pnl > 0) {
                "$symbol $kind · ${shares}주 매도 가능 · 수수료·세금 후 이익"
            } else {
                "$symbol $kind · 수수료·세금 후 손실이라 보유"
            }
        }
        return notes
    }

    private fun dividendEvent(db: SQLiteDatabase, symbol: String, asof: String, cutRatio: Double): String? {
        val day = LocalDate.parse(asof)
        val recentStart = day.minusDays(365).toString()
        val priorStart = day.minusDays(730).toString()
        val recent = dividendSum(db, symbol, recentStart, asof)
        val prior = dividendSum(db, symbol, priorStart, recentStart)
        if (prior <= 0) return null
        if (recent <= 0) return "무배당"
        if (recent < prior * (1 - cutRatio)) return "배당축소"
        return null
    }

    fun liveBlockReason(db: SQLiteDatabase): String? {
        val raw = db.rawQuery("SELECT value FROM meta WHERE key='settings'", null).use { cursor ->
            if (!cursor.moveToFirst()) return "설정이 없습니다. 일봉과 모의를 먼저 마치세요."
            cursor.getString(0)
        }
        val markets = JSONObject(raw).getJSONArray("markets")
        val gaps = mutableListOf<String>()
        for (index in 0 until markets.length()) {
            val market = markets.getJSONObject(index)
            if (!market.optBoolean("enabled")) continue
            val code = market.getString("code")
            val name = market.optString("name", code)
            val minimum = market.optInt("min_symbols")
            val target = market.optInt("target_symbols")
            val selected = count(db, "SELECT COUNT(*) FROM symbols WHERE market=? AND selected=1", code)
            val pooled = count(db, "SELECT COUNT(*) FROM symbols WHERE market=?", code)
            val bars = count(db, "SELECT COUNT(*) FROM symbols s WHERE s.market=? AND s.selected=1 AND EXISTS (SELECT 1 FROM bars b WHERE b.symbol=s.symbol)", code)
            if (selected < minimum) gaps += "$name 선택 $selected/$minimum"
            else if (selected < target && pooled > selected) gaps += "$name 목표 $selected/$target"
            if (bars < minOf(selected, minimum)) gaps += "$name 일봉 $bars"
            if (count(db, "SELECT COUNT(*) FROM backtests WHERE market=?", code) < 1) gaps += "$name 과거 모의 없음"
            if (count(db, "SELECT COUNT(*) FROM paper_orders o JOIN paper_accounts a ON a.id=o.account_id WHERE a.market=?", code) < 1) gaps += "$name 가상 모의 없음"
        }
        if (gaps.isEmpty()) return null
        return "실전 주문은 7개국 2배 일봉과 과거 모의, 가상 모의가 끝난 뒤에만 열립니다. " + gaps.take(6).joinToString(" · ")
    }

    fun lossSellReason(db: SQLiteDatabase, alias: String, market: String, symbol: String, qty: Int, price: Double): String? {
        val avg = averageCost(db, alias, market, symbol)
            ?: return "매수 기록이 없어 이익인지 확인할 수 없습니다. 손절 주문은 받지 않습니다."
        val item = schedule(market)
        val whole = (floor(qty.toDouble() / item.lot).toInt()) * item.lot
        val shares = when {
            whole >= item.lot -> whole
            market == "VN" && qty >= 1 -> qty
            item.lot == 1 -> qty
            else -> 0
        }
        if (shares <= 0) return "최소 주문 수량보다 적어 매도하지 않습니다."
        val odd = shares in 1 until item.lot
        val fee = charge(market, "sell", shares * price, odd)
        if ((price - avg) * shares - fee <= 0) return "수수료와 세금 뒤 이익이 아닙니다. 손절 주문은 받지 않습니다."
        return null
    }

    private fun averageCost(db: SQLiteDatabase, alias: String, market: String, symbol: String): Double? {
        db.rawQuery(
            "SELECT COALESCE(SUM(qty), 0), COALESCE(SUM(qty * price), 0) FROM kis_orders WHERE account_alias=? AND symbol=? AND side='buy' AND status!='error'",
            arrayOf(alias, symbol),
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getDouble(0) > 0) return cursor.getDouble(1) / cursor.getDouble(0)
        }
        return db.rawQuery(
            "SELECT p.avg_price FROM paper_positions p JOIN paper_accounts a ON a.id=p.account_id WHERE a.market=? AND p.symbol=? AND p.qty>0",
            arrayOf(market, symbol),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) else null
        }
    }

    private fun count(db: SQLiteDatabase, sql: String, market: String): Int {
        return db.rawQuery(sql, arrayOf(market)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun trailingYield(db: SQLiteDatabase, symbol: String): Double? {
        val last = db.rawQuery(
            "SELECT close, date FROM bars WHERE symbol=? AND close>0 ORDER BY date DESC LIMIT 1",
            arrayOf(symbol),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getDouble(0) to cursor.getString(1)
        }
        if (last.first <= 0 || last.second.isNullOrBlank()) return null
        val start = LocalDate.parse(last.second).minusDays(365).toString()
        val total = dividendSum(db, symbol, start, last.second)
        if (total <= 0) return null
        return total / last.first
    }

    private fun dividendSum(db: SQLiteDatabase, symbol: String, start: String, end: String): Double {
        return db.rawQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM dividends WHERE symbol=? AND ex_date>? AND ex_date<=?",
            arrayOf(symbol, start, end),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }
}
