package com.takin1217.dividendvault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class VaultDb(private val context: Context) : SQLiteOpenHelper(context, "vault.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        val schema = context.assets.open("schema.sql").bufferedReader().use { it.readText() }
        schema.split(';').forEach { statement ->
            val sql = statement.trim()
            if (sql.isNotEmpty()) db.execSQL(sql)
        }
        seedSettings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys=ON")
        seedSettings(db)
        applyHoldingFloors(db)
        ensureOrderNotionals(db)
    }

    fun readSettings(): JSONObject {
        val db = writableDatabase
        seedSettings(db)
        applyHoldingFloors(db)
        ensureOrderNotionals(db)
        return db.rawQuery("SELECT value FROM meta WHERE key='settings'", null).use { cursor ->
            cursor.moveToFirst()
            JSONObject(cursor.getString(0))
        }
    }

    private fun seedSettings(db: SQLiteDatabase) {
        val exists = db.rawQuery("SELECT 1 FROM meta WHERE key='settings'", null).use { it.moveToFirst() }
        if (exists) return
        db.execSQL(
            "INSERT INTO meta(key, value) VALUES('settings', ?)",
            arrayOf(DEFAULT_SETTINGS),
        )
    }

    private fun applyHoldingFloors(db: SQLiteDatabase) {
        val marker = db.rawQuery("SELECT value FROM meta WHERE key='holding_floor'", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (marker == "20261031-2x") return
        val raw = db.rawQuery("SELECT value FROM meta WHERE key='settings'", null).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getString(0)
        }
        val settings = JSONObject(raw)
        val markets = settings.getJSONArray("markets")
        val floors = mapOf(
            "US" to (1000 to 2000),
            "CN" to (250 to 500),
            "HK" to (150 to 300),
            "JP" to (250 to 500),
            "VN" to (50 to 100),
            "KR" to (250 to 500),
            "GB" to (50 to 100),
        )
        for (index in 0 until markets.length()) {
            val row = markets.getJSONObject(index)
            val floor = floors[row.getString("code")] ?: continue
            row.put("enabled", true)
            row.put("min_symbols", floor.first)
            row.put("target_symbols", floor.second)
            row.put("max_symbols", floor.first * 5)
        }
        db.execSQL("UPDATE meta SET value=? WHERE key='settings'", arrayOf(settings.toString()))
        db.execSQL(
            "INSERT INTO meta(key, value) VALUES('holding_floor', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            arrayOf("20261031-2x"),
        )
    }

    private fun ensureOrderNotionals(db: SQLiteDatabase) {
        val raw = db.rawQuery("SELECT value FROM meta WHERE key='settings'", null).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getString(0)
        }
        val settings = JSONObject(raw)
        val markets = settings.getJSONArray("markets")
        var changed = false
        for (index in 0 until markets.length()) {
            val row = markets.getJSONObject(index)
            if (row.has("min_order_notional")) continue
            row.put("min_order_notional", TradeRules.defaultNotional(row.getString("code")))
            changed = true
        }
        if (changed) db.execSQL("UPDATE meta SET value=? WHERE key='settings'", arrayOf(settings.toString()))
    }

    fun saveMarketRule(code: String, minSymbols: Int, minNotional: Double) {
        if (minSymbols < 1) error("최소 보유 종목은 1개 이상입니다.")
        if (minNotional < 0) error("최소 주문 금액은 0 이상입니다.")
        val db = writableDatabase
        val settings = readSettings()
        val markets = settings.getJSONArray("markets")
        var found = false
        for (index in 0 until markets.length()) {
            val row = markets.getJSONObject(index)
            if (row.getString("code") != code) continue
            row.put("min_symbols", minSymbols)
            row.put("target_symbols", minSymbols * 2)
            row.put("max_symbols", minSymbols * 5)
            row.put("min_order_notional", minNotional)
            found = true
        }
        if (!found) error("나라를 찾지 못했습니다.")
        db.execSQL("UPDATE meta SET value=? WHERE key='settings'", arrayOf(settings.toString()))
    }

    companion object {
        private const val DEFAULT_SETTINGS =
            """{"years":10,"expansion_factor":5.0,"min_yield":0.02,"max_yield":0.12,"min_avg_volume":50000.0,"exclude_preferred":true,"exclude_leveraged":true,"include_etp":false,"etp_limit":0,"fee_bps":10.0,"model_weight":0.5,"strategy":"learned_tilt","weight_cap":4.0,"auto_daily":true,"markets":[{"code":"US","name":"미국","enabled":true,"min_symbols":1000,"target_symbols":2000,"max_symbols":5000,"min_market_cap":300000000.0,"currency":"USD","paper_cash":1000000.0,"yahoo_region":"us","benchmark":"SPY","source":"yahoo"},{"code":"CN","name":"중국","enabled":true,"min_symbols":250,"target_symbols":500,"max_symbols":1250,"min_market_cap":5000000000.0,"currency":"CNY","paper_cash":5000000.0,"yahoo_region":"cn","benchmark":"000300.SS","source":"yahoo"},{"code":"HK","name":"홍콩","enabled":true,"min_symbols":150,"target_symbols":300,"max_symbols":750,"min_market_cap":5000000000.0,"currency":"HKD","paper_cash":5000000.0,"yahoo_region":"hk","benchmark":"^HSI","source":"yahoo"},{"code":"JP","name":"일본","enabled":true,"min_symbols":250,"target_symbols":500,"max_symbols":1250,"min_market_cap":50000000000.0,"currency":"JPY","paper_cash":100000000.0,"yahoo_region":"jp","benchmark":"^N225","source":"yahoo"},{"code":"VN","name":"베트남","enabled":true,"min_symbols":50,"target_symbols":100,"max_symbols":250,"min_market_cap":0.0,"currency":"VND","paper_cash":10000000000.0,"yahoo_region":"vn","benchmark":"E1VFVN30.VN","source":"vndirect"},{"code":"KR","name":"한국","enabled":true,"min_symbols":250,"target_symbols":500,"max_symbols":1250,"min_market_cap":200000000000.0,"currency":"KRW","paper_cash":100000000.0,"yahoo_region":"kr","benchmark":"069500.KS","source":"yahoo"},{"code":"GB","name":"영국","enabled":true,"min_symbols":50,"target_symbols":100,"max_symbols":250,"min_market_cap":200000000.0,"currency":"GBP","paper_cash":500000.0,"yahoo_region":"gb","benchmark":"^FTSE","source":"yahoo"}]}"""
    }
}
