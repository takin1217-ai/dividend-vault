package com.takin1217.dividendvault.data

import android.content.Context
import com.takin1217.dividendvault.VaultApp
import com.takin1217.dividendvault.notify.VaultNotifier
import com.takin1217.dividendvault.security.DeviceCipher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class KisBroker(context: Context) {
    private val app = context.applicationContext as VaultApp
    private val accountsFile = File(app.filesDir, "kis.bin")
    private val tokensFile = File(app.filesDir, "kis-tokens.bin")
    private val legacyAccounts = File(app.filesDir, "kis.json")
    private val legacyTokens = File(app.filesDir, "kis-tokens.json")
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val clock = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.systemDefault())

    data class TradePolicy(
        val minYield: Double,
        val maxYield: Double,
        val cutRatio: Double,
        val weightCap: Double,
        val maxHoldings: Int,
        val rebalanceDays: Int,
        val feeBps: Double,
        val paperCash: Double,
    ) {
        fun line(): String {
            val cash = if (paperCash > 0) " · 시드 ${paperCash.toLong()}" else ""
            val book = if (maxHoldings > 0) "${maxHoldings}종목" else "조건 종목"
            return "배당 ${pct(minYield)}~${pct(maxYield)}% · 축소 ${pct(cutRatio)}% · $book · ${rebalanceDays}일 · 비중 ${trim(weightCap)}배$cash"
        }

        fun toJson(): JSONObject = JSONObject()
            .put("min_yield", minYield)
            .put("max_yield", maxYield)
            .put("cut_ratio", cutRatio)
            .put("weight_cap", weightCap)
            .put("max_holdings", maxHoldings)
            .put("rebalance_days", rebalanceDays)
            .put("fee_bps", feeBps)
            .put("paper_cash", paperCash)

        companion object {
            fun defaults() = TradePolicy(0.02, 0.12, 0.2, 4.0, 40, 21, 10.0, 0.0)

            fun from(account: JSONObject): TradePolicy {
                val policy = account.optJSONObject("policy") ?: return defaults()
                return TradePolicy(
                    minYield = policy.optDouble("min_yield", 0.02),
                    maxYield = policy.optDouble("max_yield", 0.12),
                    cutRatio = policy.optDouble("cut_ratio", 0.2),
                    weightCap = policy.optDouble("weight_cap", 4.0),
                    maxHoldings = policy.optInt("max_holdings", 40),
                    rebalanceDays = policy.optInt("rebalance_days", 21),
                    feeBps = policy.optDouble("fee_bps", 10.0),
                    paperCash = policy.optDouble("paper_cash", 0.0),
                )
            }

            fun parse(
                minPct: String,
                maxPct: String,
                cutPct: String,
                cap: String,
                holdings: String,
                days: String,
                fee: String,
                cash: String,
            ): TradePolicy {
                val minYield = percent(minPct, "최소 배당률")
                val maxYield = percent(maxPct, "최대 배당률")
                val cutRatio = percent(cutPct, "배당축소 기준")
                val weightCap = number(cap, "비중 상한")
                val maxHoldings = holdings.trim().ifBlank { "40" }.toIntOrNull() ?: error("최대 보유 종목은 정수입니다.")
                val rebalanceDays = days.trim().ifBlank { "21" }.toIntOrNull() ?: error("리밸런스 간격은 정수 일수입니다.")
                val feeBps = number(fee.ifBlank { "10" }, "모의 수수료")
                val paperCash = if (cash.isBlank()) 0.0 else number(cash, "가상 시드")
                if (minYield > maxYield) error("최소 배당률은 최대 배당률보다 클 수 없습니다.")
                if (minYield !in 0.0..0.25 || maxYield !in 0.0..0.30) error("배당률은 0%에서 30% 사이로 입력하세요.")
                if (cutRatio !in 0.05..0.9) error("배당축소 기준은 5%에서 90% 사이입니다.")
                if (weightCap !in 1.0..10.0) error("비중 상한은 1배에서 10배 사이입니다.")
                if (maxHoldings !in 0..500) error("최대 보유 종목은 0에서 500 사이입니다. 0이면 배당 조건에 맞는 종목을 씁니다.")
                if (rebalanceDays !in 5..63) error("리밸런스 간격은 5일에서 63일 사이입니다.")
                if (feeBps !in 0.0..100.0) error("모의 수수료는 0에서 100bp 사이입니다.")
                if (paperCash < 0) error("가상 시드는 0 이상입니다.")
                return TradePolicy(minYield, maxYield, cutRatio, weightCap, maxHoldings, rebalanceDays, feeBps, paperCash)
            }

            private fun percent(text: String, label: String): Double {
                val value = number(text, label)
                return value / 100.0
            }

            private fun number(text: String, label: String): Double {
                return text.trim().replace(",", "").toDoubleOrNull() ?: error("$label 숫자를 입력하세요.")
            }

            private fun pct(value: Double): String = trim(value * 100)

            private fun trim(value: Double): String {
                val whole = value.toLong().toDouble()
                return if (kotlin.math.abs(value - whole) < 1e-9) whole.toLong().toString() else value.toString()
            }
        }
    }

    data class AccountCard(
        val id: String,
        val alias: String,
        val mode: String,
        val mask: String,
        val productCode: String,
        val markets: List<String>,
        val usExchange: String,
        val vnExchange: String,
        val selectedMarkets: List<String>,
        val token: String,
        val policy: String,
    )

    fun add(
        alias: String,
        mode: String,
        appKey: String,
        appSecret: String,
        accountNo: String,
        markets: Set<String>,
        productCode: String,
        usExchange: String,
        vnExchange: String,
        assign: Boolean,
        policy: TradePolicy,
    ) {
        if (mode != "paper" && mode != "live") error("mode는 paper 또는 live 입니다.")
        val key = appKey.trim()
        val secret = appSecret.trim()
        val number = accountNo.trim()
        val chosen = markets.map { it.uppercase() }.filter { it in MARKET_CODES }.distinct()
        if (key.isEmpty() || secret.isEmpty() || number.length < 8) {
            error("앱키, 앱시크릿, 계좌번호 8자리를 입력하세요.")
        }
        if (chosen.isEmpty()) error("나라는 최소 1개를 고르세요. 한 계좌에 7개국을 모두 넣을 수 있습니다.")
        val us = usExchange.uppercase().ifBlank { "NASD" }
        val vn = vnExchange.uppercase().ifBlank { "VNSE" }
        if (us !in setOf("NASD", "NYSE", "AMEX")) error("미국 거래소는 NASD, NYSE, AMEX 중 하나입니다.")
        if (vn !in setOf("VNSE", "HASE")) error("베트남 거래소는 HOSE(VNSE) 또는 HNX(HASE) 입니다.")
        val product = productCode.trim().ifBlank { "01" }.take(2)
        val root = read(accountsFile, legacyAccounts)
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val id = UUID.randomUUID().toString().replace("-", "")
        val linked = JSONArray()
        chosen.forEach { linked.put(it) }
        accounts.put(
            JSONObject()
                .put("id", id)
                .put("alias", alias.ifBlank { if (mode == "paper") "모의" else "실전" })
                .put("mode", mode)
                .put("app_key", key)
                .put("app_secret", secret)
                .put("account_no", number.take(8))
                .put("product_code", product.ifBlank { "01" })
                .put("markets", linked)
                .put("us_exchange", us)
                .put("vn_exchange", vn)
                .put("policy", policy.toJson())
                .put("created_at", Instant.now().toString()),
        )
        val selected = root.optJSONObject("selected") ?: JSONObject()
        chosen.forEach { code ->
            if (assign || selected.optString(code).isEmpty()) selected.put(code, id)
        }
        root.put("accounts", accounts)
        root.put("selected", selected)
        write(accountsFile, root)
        legacyAccounts.delete()
    }

    data class AccountDraft(
        val id: String,
        val alias: String,
        val mode: String,
        val appKey: String,
        val accountNo: String,
        val productCode: String,
        val markets: Set<String>,
        val usExchange: String,
        val vnExchange: String,
        val policy: TradePolicy,
    )

    fun draft(id: String): AccountDraft {
        val row = accountById(id)
        return AccountDraft(
            id = row.getString("id"),
            alias = row.optString("alias"),
            mode = row.optString("mode", "paper"),
            appKey = row.optString("app_key"),
            accountNo = row.optString("account_no"),
            productCode = row.optString("product_code", "01"),
            markets = marketsOf(row).toSet(),
            usExchange = row.optString("us_exchange", "NASD"),
            vnExchange = row.optString("vn_exchange", "VNSE"),
            policy = TradePolicy.from(row),
        )
    }

    fun policyForMarket(market: String): TradePolicy = TradePolicy.from(accountForMarket(market))

    fun update(
        id: String,
        alias: String,
        mode: String,
        appKey: String,
        appSecret: String,
        accountNo: String,
        markets: Set<String>,
        productCode: String,
        usExchange: String,
        vnExchange: String,
        assign: Boolean,
        policy: TradePolicy,
    ) {
        if (mode != "paper" && mode != "live") error("mode는 paper 또는 live 입니다.")
        val chosen = markets.map { it.uppercase() }.filter { it in MARKET_CODES }.distinct()
        if (chosen.isEmpty()) error("나라는 최소 1개를 고르세요. 한 계좌에 7개국을 모두 넣을 수 있습니다.")
        val us = usExchange.uppercase().ifBlank { "NASD" }
        val vn = vnExchange.uppercase().ifBlank { "VNSE" }
        if (us !in setOf("NASD", "NYSE", "AMEX")) error("미국 거래소는 NASD, NYSE, AMEX 중 하나입니다.")
        if (vn !in setOf("VNSE", "HASE")) error("베트남 거래소는 HOSE(VNSE) 또는 HNX(HASE) 입니다.")
        val root = read(accountsFile, legacyAccounts)
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val row = (0 until accounts.length()).map { accounts.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: error("계좌를 찾지 못했습니다.")
        val key = appKey.trim()
        val secret = appSecret.trim()
        if (key.isNotEmpty() && key != row.optString("app_key") && secret.isEmpty()) error("앱키를 바꾸면 앱시크릿도 입력하세요.")
        if (alias.isNotBlank()) row.put("alias", alias)
        row.put("mode", mode)
        if (key.isNotEmpty()) row.put("app_key", key)
        if (secret.isNotEmpty()) row.put("app_secret", secret)
        val number = accountNo.trim()
        if (number.isNotEmpty()) {
            if (number.length < 8) error("계좌번호 8자리를 입력하세요.")
            row.put("account_no", number.take(8))
        }
        row.put("product_code", productCode.trim().ifBlank { "01" }.take(2))
        val linked = JSONArray()
        chosen.forEach { linked.put(it) }
        row.put("markets", linked)
        row.put("us_exchange", us)
        row.put("vn_exchange", vn)
        row.put("policy", policy.toJson())
        val selected = root.optJSONObject("selected") ?: JSONObject()
        val next = JSONObject()
        selected.keys().forEach { code ->
            val chosenId = selected.optString(code)
            if (chosenId != id || code in chosen) next.put(code, chosenId)
        }
        if (assign) chosen.forEach { next.put(it, id) }
        root.put("accounts", accounts)
        root.put("selected", next)
        write(accountsFile, root)
    }

    fun unselect(id: String, market: String) {
        val code = market.uppercase()
        val root = read(accountsFile, legacyAccounts)
        val selected = root.optJSONObject("selected") ?: JSONObject()
        if (selected.optString(code) == id) selected.remove(code)
        root.put("selected", selected)
        write(accountsFile, root)
    }

    fun cards(): List<AccountCard> {
        val root = read(accountsFile, legacyAccounts)
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val selected = root.optJSONObject("selected") ?: JSONObject()
        val chosenByAccount = mutableMapOf<String, MutableList<String>>()
        selected.keys().forEach { code ->
            val id = selected.optString(code)
            chosenByAccount.getOrPut(id) { mutableListOf() }.add(code)
        }
        return (0 until accounts.length()).map { index ->
            val account = accounts.getJSONObject(index)
            val markets = account.optJSONArray("markets")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: MARKET_CODES
            val number = account.optString("account_no")
            AccountCard(
                id = account.getString("id"),
                alias = account.optString("alias"),
                mode = account.optString("mode"),
                mask = "*".repeat((number.length - 4).coerceAtLeast(0)) + number.takeLast(4),
                productCode = account.optString("product_code", "01"),
                markets = markets,
                usExchange = account.optString("us_exchange", "NASD"),
                vnExchange = account.optString("vn_exchange", "VNSE"),
                selectedMarkets = chosenByAccount[account.getString("id")].orEmpty(),
                token = tokenLine(account),
                policy = TradePolicy.from(account).line(),
            )
        }
    }

    fun selectionLines(): String {
        val root = read(accountsFile, legacyAccounts)
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val byId = (0 until accounts.length()).associate { index ->
            val row = accounts.getJSONObject(index)
            row.getString("id") to row
        }
        val selected = root.optJSONObject("selected") ?: JSONObject()
        return MARKET_CODES.joinToString(" · ") { code ->
            val row = byId[selected.optString(code)]
            val label = NAMES[code]
            if (row == null) "$label 없음" else "$label ${row.optString("alias")}"
        }
    }

    fun select(id: String, market: String) {
        val code = market.uppercase()
        if (code !in MARKET_CODES) error("나라는 US CN HK JP VN KR GB 중 하나입니다.")
        val account = accountById(id)
        if (code !in marketsOf(account)) error("이 계좌에는 ${NAMES[code]}이 연결되어 있지 않습니다.")
        val root = read(accountsFile, legacyAccounts)
        val selected = root.optJSONObject("selected") ?: JSONObject()
        selected.put(code, id)
        root.put("selected", selected)
        write(accountsFile, root)
    }

    fun delete(id: String) {
        val root = read(accountsFile, legacyAccounts)
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val kept = JSONArray()
        for (index in 0 until accounts.length()) {
            val row = accounts.getJSONObject(index)
            if (row.getString("id") != id) kept.put(row)
        }
        val selected = root.optJSONObject("selected") ?: JSONObject()
        val next = JSONObject()
        selected.keys().forEach { code ->
            if (selected.optString(code) != id) next.put(code, selected.optString(code))
        }
        root.put("accounts", kept)
        root.put("selected", next)
        write(accountsFile, root)
    }

    fun accountCount(): Int = read(accountsFile, legacyAccounts).optJSONArray("accounts")?.length() ?: 0

    fun tokenStatus(market: String = ""): String {
        val account = if (market.isBlank()) {
            val accounts = read(accountsFile, legacyAccounts).optJSONArray("accounts") ?: JSONArray()
            if (accounts.length() == 0) return "계좌 없음"
            accounts.getJSONObject(0)
        } else {
            accountForMarket(market)
        }
        return "${account.getString("alias")} ${tokenLine(account)}"
    }

    private fun tokenLine(account: JSONObject): String {
        val cache = read(tokensFile, legacyTokens)
        val slot = cache.optJSONObject(tokenKey(account))
            ?: cache.optJSONObject(account.getString("id"))
            ?: return "토큰 없음"
        val expires = slot.optDouble("expires_at")
        val issued = slot.optDouble("issued_at")
        if (issued <= 0 || expires <= 0) return "토큰 없음"
        val now = System.currentTimeMillis() / 1000.0
        val window = "${clock.format(Instant.ofEpochSecond(issued.toLong()))} 발행 · ${clock.format(Instant.ofEpochSecond(expires.toLong()))} 만료"
        return if (now + 60 < expires) "유효 · $window" else "만료 · $window"
    }

    fun ping(index: Int): String = pingAccount(accountAt(index))

    fun pingMarket(market: String): String = pingAccount(accountForMarket(market))

    private fun pingAccount(account: JSONObject): String {
        token(account)
        val host = if (account.getString("mode") == "paper") "모의투자" else "실전투자"
        return "${account.getString("alias")} $host ${tokenLine(account)}"
    }

    fun order(
        index: Int,
        market: String,
        symbol: String,
        side: String,
        qty: Int,
        price: Double,
        confirm: String,
    ): String {
        val account = accountForMarket(market)
        if (market.uppercase() !in marketsOf(account)) error("이 계좌에는 그 나라가 연결되어 있지 않습니다.")
        val mode = account.getString("mode")
        val phrase = if (mode == "paper") "모의주문확인" else "실전주문확인"
        if (confirm != phrase) error("${if (mode == "live") "실전" else "모의"} 주문은 확인 문구 '$phrase'가 있어야 전송됩니다.")
        if (side != "buy" && side != "sell") error("side는 buy 또는 sell 입니다.")
        if (side == "sell" && qty < 1) error("매도 수량은 1주 이상의 정수입니다.")
        if (price <= 0) error("지정가 가격이 필요합니다.")
        if (mode == "live") {
            val blocked = TradeRules.liveBlockReason(app.database.readableDatabase)
            if (blocked != null) error(blocked)
        }
        val policy = TradePolicy.from(account)
        if (side == "buy") {
            val yield = TradeRules.trailingYield(app.database.readableDatabase, symbol)
            if (yield != null && (yield < policy.minYield || yield > policy.maxYield)) {
                error("이 계좌 배당 조건 ${"%.1f".format(policy.minYield * 100)}~${"%.1f".format(policy.maxYield * 100)}% 밖이라 매수하지 않습니다.")
            }
        }
        val sized = if (side == "buy") {
            TradeRules.orderShares(market, price, TradeRules.minNotional(app.database.readSettings(), market))
        } else {
            qty
        }
        if (sized < 1) error("주문 수량이 없습니다.")
        if (side == "sell") {
            val blocked = TradeRules.lossSellReason(app.database.readableDatabase, account.getString("alias"), market, symbol, sized, price)
            if (blocked != null) error(blocked)
        }
        val board = board(market, symbol, listedExchange(market, symbol))
        val trId = tr(board.group, side, mode)
        val body = if (market == "KR") {
            JSONObject()
                .put("CANO", account.getString("account_no"))
                .put("ACNT_PRDT_CD", account.getString("product_code"))
                .put("PDNO", board.product)
                .put("ORD_DVSN", "00")
                .put("ORD_QTY", sized.toString())
                .put("ORD_UNPR", priceText(market, price))
        } else {
            JSONObject()
                .put("CANO", account.getString("account_no"))
                .put("ACNT_PRDT_CD", account.getString("product_code"))
                .put("OVRS_EXCG_CD", board.exchange)
                .put("PDNO", board.product)
                .put("ORD_QTY", sized.toString())
                .put("OVRS_ORD_UNPR", priceText(market, price))
                .put("CTAC_TLNO", "")
                .put("MGCO_APTM_ODNO", "")
                .put("SLL_TYPE", "")
                .put("ORD_SVR_DVSN_CD", "0")
                .put("ORD_DVSN", "00")
        }
        val path = if (market == "KR") {
            "/uapi/domestic-stock/v1/trading/order-cash"
        } else {
            "/uapi/overseas-stock/v1/trading/order"
        }
        val response = post(account, path, trId, body, hash = true)
        val message = response.optString("msg1").ifBlank { "주문을 전송했습니다." }
        app.database.writableDatabase.execSQL(
            "INSERT INTO kis_orders(created_at, account_alias, mode, market, symbol, side, qty, price, status, request_json, response_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                Instant.now().toString(),
                account.getString("alias"),
                mode,
                market,
                symbol,
                side,
                sized.toDouble(),
                price,
                "submitted",
                JSONObject().put("tr_id", trId).put("path", path).toString(),
                response.toString().take(4000),
            ),
        )
        val summary = "$trId ${account.getString("alias")} $symbol $side ${sized}주 $message"
        VaultNotifier(app).trade(summary)
        return summary
    }

    private data class Board(val group: String, val product: String, val exchange: String)

    private fun board(market: String, symbol: String, exchange: String): Board {
        val code = symbol.substringBefore('.')
        return when (market) {
            "GB" -> error("영국은 데이터와 가상 모의만 됩니다.")
            "KR" -> Board("KR", code, "")
            "HK" -> Board("HK", if (code.length <= 4) code.padStart(4, '0') else code, "SEHK")
            "JP" -> Board("JP", code, "TKSE")
            "CN" -> if (symbol.endsWith(".SS")) Board("CN_SS", code, "SHAA") else Board("CN_SZ", code, "SZAA")
            "VN" -> Board("VN", code, if (symbol.endsWith(".HN") || exchange.uppercase() in setOf("HNX", "HASE")) "HASE" else "VNSE")
            else -> Board("US", code, usExchange(exchange))
        }
    }

    private fun listedExchange(market: String, symbol: String): String {
        val stored = app.database.readableDatabase.rawQuery(
            "SELECT exchange FROM symbols WHERE symbol=?",
            arrayOf(symbol),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        val label = stored.ifBlank { chartExchange(symbol) }
        return if (market.uppercase() == "US") usExchange(label) else label
    }

    private fun chartExchange(symbol: String): String {
        return runCatching {
            val encoded = java.net.URLEncoder.encode(symbol, "UTF-8")
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1d&range=5d")
                .header("User-Agent", "Mozilla/5.0 DividendVault/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return ""
                val meta = JSONObject(text).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                meta.optString("exchangeName").ifBlank { meta.optString("exchange") }
            }
        }.getOrDefault("")
    }

    private fun usExchange(exchange: String): String {
        val text = exchange.uppercase()
        return when {
            text.contains("NAS") || text in setOf("NMS", "NGM", "NCM") -> "NASD"
            text.contains("ARCA") || text.contains("AMERICAN") || text in setOf("ASE", "AMX", "PCX", "AMS", "AMEX") -> "AMEX"
            text.contains("NYSE") || text in setOf("NYQ", "NYS") -> "NYSE"
            else -> "NASD"
        }
    }

    private fun tr(group: String, side: String, mode: String): String {
        val pair = TR[group to side] ?: error("이 시장 주문 코드를 찾지 못했습니다.")
        return if (mode == "paper") pair.second else pair.first
    }

    private fun priceText(market: String, price: Double): String {
        if (market == "KR" || market == "JP" || market == "VN") return price.roundToInt().toString()
        val digits = if (market == "HK") 3 else 2
        return "%.${digits}f".format(price)
    }

    private fun accountForMarket(market: String): JSONObject {
        val code = market.uppercase()
        if (code !in MARKET_CODES) error("나라는 US CN HK JP VN KR GB 중 하나입니다.")
        val root = read(accountsFile, legacyAccounts)
        val selectedId = root.optJSONObject("selected")?.optString(code).orEmpty()
        if (selectedId.isEmpty()) error("${NAMES[code]} 주문 계좌가 없습니다. 계좌를 추가하고 그 나라를 지정하세요.")
        return accountById(selectedId)
    }

    private fun accountById(id: String): JSONObject {
        val accounts = read(accountsFile, legacyAccounts).optJSONArray("accounts") ?: JSONArray()
        for (index in 0 until accounts.length()) {
            val row = accounts.getJSONObject(index)
            if (row.getString("id") == id) return row
        }
        error("계좌를 찾지 못했습니다.")
    }

    private fun marketsOf(account: JSONObject): List<String> {
        val array = account.optJSONArray("markets") ?: return MARKET_CODES
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun accountAt(index: Int): JSONObject {
        val accounts = read(accountsFile, legacyAccounts).optJSONArray("accounts") ?: JSONArray()
        if (index < 0 || index >= accounts.length()) error("저장된 계좌가 없습니다. 먼저 계좌를 추가하세요.")
        return accounts.getJSONObject(index)
    }

    private fun tokenKey(account: JSONObject): String = "${account.getString("mode")}|${account.getString("app_key")}"

    private fun freshToken(slot: JSONObject?, now: Double): String? {
        if (slot == null) return null
        val cached = slot.optString("access_token")
        val expires = slot.optDouble("expires_at")
        return if (cached.isNotEmpty() && now + 60 < expires) cached else null
    }

    private fun token(account: JSONObject): String {
        val cache = read(tokensFile, legacyTokens)
        val now = System.currentTimeMillis() / 1000.0
        val key = tokenKey(account)
        var slot = cache.optJSONObject(key) ?: cache.optJSONObject(account.getString("id"))
        var cached = freshToken(slot, now)
        if (cached == null) {
            val names = cache.keys()
            while (names.hasNext()) {
                val item = cache.optJSONObject(names.next()) ?: continue
                if (item.optString("app_key") == account.getString("app_key") && item.optString("mode", account.getString("mode")) == account.getString("mode")) {
                    cached = freshToken(item, now)
                    if (cached != null) {
                        slot = item
                        break
                    }
                }
            }
        }
        if (cached != null && slot != null) {
            cache.put(
                key,
                JSONObject()
                    .put("access_token", cached)
                    .put("issued_at", slot.optDouble("issued_at", now))
                    .put("expires_at", slot.optDouble("expires_at"))
                    .put("app_key", account.getString("app_key"))
                    .put("mode", account.getString("mode")),
            )
            write(tokensFile, cache)
            return cached
        }
        val body = JSONObject()
            .put("grant_type", "client_credentials")
            .put("appkey", account.getString("app_key"))
            .put("appsecret", account.getString("app_secret"))
        val response = rawPost(base(account) + "/oauth2/tokenP", body, emptyMap())
        val access = response.optString("access_token")
        if (access.isEmpty()) {
            error(response.optString("error_description").ifBlank { response.optString("msg1").ifBlank { "토큰 발급 실패" } })
        }
        val issued = now
        val lifetime = response.optLong("expires_in", 86_400).coerceAtLeast(60)
        val expiry = issued + lifetime
        cache.put(
            key,
            JSONObject()
                .put("access_token", access)
                .put("issued_at", issued)
                .put("expires_at", expiry)
                .put("app_key", account.getString("app_key"))
                .put("mode", account.getString("mode")),
        )
        write(tokensFile, cache)
        legacyTokens.delete()
        VaultNotifier(app).token(
            "${account.getString("alias")} 토큰을 한 번 발행했습니다. ${clock.format(Instant.ofEpochSecond(issued.toLong()))}부터 ${clock.format(Instant.ofEpochSecond(expiry.toLong()))}까지 같은 앱키는 다시 받지 않습니다.",
        )
        return access
    }

    private fun post(account: JSONObject, path: String, trId: String, body: JSONObject, hash: Boolean): JSONObject {
        val headers = mutableMapOf(
            "authorization" to "Bearer ${token(account)}",
            "appkey" to account.getString("app_key"),
            "appsecret" to account.getString("app_secret"),
            "tr_id" to trId,
            "custtype" to "P",
        )
        if (hash) headers["hashkey"] = hashKey(account, body)
        val response = rawPost(base(account) + path, body, headers)
        if (response.optString("rt_cd", "0") !in setOf("0", "None", "")) {
            error(response.optString("msg1").ifBlank { "한국투자증권 오류" })
        }
        return response
    }

    private fun hashKey(account: JSONObject, body: JSONObject): String {
        val response = rawPost(
            base(account) + "/uapi/hashkey",
            body,
            mapOf("appkey" to account.getString("app_key"), "appsecret" to account.getString("app_secret")),
        )
        return response.optString("HASH").ifEmpty { error(response.optString("msg1").ifBlank { "해시키 발급 실패" }) }
    }

    private fun rawPost(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType))
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) error("응답이 비어 있습니다. HTTP ${response.code}")
            return JSONObject(text)
        }
    }

    private fun base(account: JSONObject): String = if (account.getString("mode") == "paper") PAPER else REAL

    private fun read(secure: File, legacy: File): JSONObject {
        DeviceCipher.readText(app, secure)?.let { return JSONObject(it) }
        if (!legacy.exists()) return JSONObject()
        val plain = JSONObject(legacy.readText())
        write(secure, plain)
        legacy.delete()
        return plain
    }

    private fun write(file: File, json: JSONObject) {
        DeviceCipher.writeText(app, file, json.toString())
    }

    companion object {
        private const val REAL = "https://openapi.koreainvestment.com:9443"
        private const val PAPER = "https://openapivts.koreainvestment.com:29443"
        val MARKET_CODES = listOf("US", "CN", "HK", "JP", "VN", "KR", "GB")
        val NAMES = mapOf(
            "US" to "미국",
            "CN" to "중국",
            "HK" to "홍콩",
            "JP" to "일본",
            "VN" to "베트남",
            "KR" to "한국",
            "GB" to "영국",
        )
        private val TR = mapOf(
            ("US" to "buy") to ("TTTT1002U" to "VTTT1002U"),
            ("US" to "sell") to ("TTTT1006U" to "VTTT1001U"),
            ("HK" to "buy") to ("TTTS1002U" to "VTTS1002U"),
            ("HK" to "sell") to ("TTTS1001U" to "VTTS1001U"),
            ("CN_SS" to "buy") to ("TTTS0202U" to "VTTS0202U"),
            ("CN_SS" to "sell") to ("TTTS1005U" to "VTTS1005U"),
            ("CN_SZ" to "buy") to ("TTTS0305U" to "VTTS0305U"),
            ("CN_SZ" to "sell") to ("TTTS0304U" to "VTTS0304U"),
            ("JP" to "buy") to ("TTTS0308U" to "VTTS0308U"),
            ("JP" to "sell") to ("TTTS0307U" to "VTTS0307U"),
            ("VN" to "buy") to ("TTTS0311U" to "VTTS0311U"),
            ("VN" to "sell") to ("TTTS0310U" to "VTTS0310U"),
            ("KR" to "buy") to ("TTTC0802U" to "VTTC0802U"),
            ("KR" to "sell") to ("TTTC0801U" to "VTTC0801U"),
        )
    }
}
