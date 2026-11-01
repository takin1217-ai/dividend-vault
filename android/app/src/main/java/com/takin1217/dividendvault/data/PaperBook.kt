package com.takin1217.dividendvault.data

import com.takin1217.dividendvault.VaultApp

object PaperBook {
    fun run(app: VaultApp, market: String): String {
        val broker = KisBroker(app)
        val policy = broker.policyForMarket(market)
        val settings = app.database.readSettings()
        val result = Research.yieldTilt(
            app.database.readableDatabase,
            market,
            policy.minYield,
            policy.maxYield,
            policy.feeBps,
            policy.weightCap,
            policy.maxHoldings,
            policy.rebalanceDays,
        )
        val db = app.database.writableDatabase
        val markets = settings.getJSONArray("markets")
        var seed = policy.paperCash
        var currency = ""
        for (index in 0 until markets.length()) {
            val row = markets.getJSONObject(index)
            if (row.getString("code") == market) {
                if (seed <= 0) seed = row.optDouble("paper_cash")
                currency = row.optString("currency")
            }
        }
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO paper_accounts(market, name, currency, cash, created_at) VALUES(?,?,?,?,datetime('now')) ON CONFLICT(market) DO NOTHING",
                arrayOf(market, "$market 가상", currency, seed),
            )
            val notes = TradeRules.settle(db, market, latestSession(db), policy.cutRatio)
            val accountId = db.rawQuery("SELECT id FROM paper_accounts WHERE market=?", arrayOf(market)).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            var cash = db.rawQuery("SELECT cash FROM paper_accounts WHERE id=?", arrayOf(accountId.toString())).use { cursor ->
                cursor.moveToFirst()
                cursor.getDouble(0)
            }
            val held = mutableSetOf<String>()
            db.rawQuery("SELECT symbol FROM paper_positions WHERE account_id=?", arrayOf(accountId.toString())).use { cursor ->
                while (cursor.moveToNext()) held += cursor.getString(0)
            }
            var bought = 0
            result.weights.entries.sortedByDescending { it.value }.forEach { (symbol, weight) ->
                if (symbol in held || weight <= 0 || cash <= 0) return@forEach
                val price = db.rawQuery("SELECT close FROM bars WHERE symbol=? AND close>0 ORDER BY date DESC LIMIT 1", arrayOf(symbol)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
                }
                if (price <= 0) return@forEach
            val shares = TradeRules.orderShares(market, price, TradeRules.minNotional(settings, market))
            if (shares <= 0) return@forEach
                val notional = shares * price
                val fee = TradeRules.charge(market, "buy", notional)
                if (notional + fee > cash) return@forEach
                cash -= notional + fee
                bought += 1
                db.execSQL("INSERT INTO paper_positions(account_id, symbol, qty, avg_price) VALUES(?,?,?,?)", arrayOf(accountId, symbol, shares, price))
                db.execSQL(
                    "INSERT INTO paper_orders(account_id, symbol, side, qty, status, reason, created_at, filled_at, fill_price, fee) VALUES(?,?,?,?,?,?,datetime('now'),datetime('now'),?,?)",
                    arrayOf(accountId, symbol, "buy", shares, "filled", "계좌 매매 조건", price, fee),
                )
            }
            db.execSQL("UPDATE paper_accounts SET cash=? WHERE id=?", arrayOf(cash, accountId))
            db.setTransactionSuccessful()
            val sold = notes.count { it.contains("매도") }
            return "${policy.line()} 이익 매도 ${sold}건, 신규 매수 ${bought}종목. 손실 보유는 그대로 둡니다."
        } finally {
            db.endTransaction()
        }
    }

    fun sellBreaks(app: VaultApp, market: String): String {
        val policy = KisBroker(app).policyForMarket(market)
        val db = app.database.writableDatabase
        db.beginTransaction()
        return try {
            val notes = TradeRules.settle(db, market, latestSession(db), policy.cutRatio)
            db.setTransactionSuccessful()
            notes.joinToString("\n").ifBlank { "배당 축소·무배당 중 팔 종목이 없습니다." }
        } finally {
            db.endTransaction()
        }
    }

    private fun latestSession(db: android.database.sqlite.SQLiteDatabase): String {
        return db.rawQuery("SELECT MAX(date) FROM bars", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0) != null) cursor.getString(0) else java.time.LocalDate.now().toString()
        }
    }
}
