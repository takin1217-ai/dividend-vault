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
        exchange: String,
    ): String {
        val account = accountForMarket(market)
        if (market.uppercase() !in marketsOf(account)) error("이 계좌에는 그 나라가 연결되어 있지 않습니다.")
        val mode = account.getString("mode")
        val phrase = if (mode == "paper") "모의주문확인" else "실전주문확인"
        if (confirm != phrase) error("${if (mode == "live") "실전" else "모의"} 주문은 확인 문구 '$phrase'가 있어야 전송됩니다.")
        if (side != "buy" && side != "sell") error("side는 buy 또는 sell 입니다.")
        if (qty < 1) error("한국투자증권 주문 수량은 1주 이상의 정수입니다.")
        if (price <= 0) error("지정가 가격이 필요합니다.")
        if (mode == "live") {
            val blocked = TradeRules.liveBlockReason(app.database.readableDatabase)
            if (blocked != null) error(blocked)
        }
        if (side == "sell") {
            val blocked = TradeRules.lossSellReason(app.database.readableDatabase, account.getString("alias"), market, symbol, qty, price)
            if (blocked != null) error(blocked)
        }
        val venue = when (market.uppercase()) {
            "US" -> exchange.ifBlank { account.optString("us_exchange", "NASD") }
            "VN" -> exchange.ifBlank { account.optString("vn_exchange", "VNSE") }
            else -> exchange
        }
        val board = board(market, symbol, venue)
        val trId = tr(board.group, side, mode)
        val body = if (market == "KR") {
            JSONObject()
                .put("CANO", account.getString("account_no"))
                .put("ACNT_PRDT_CD", account.getString("product_code"))
                .put("PDNO", board.product)
                .put("ORD_DVSN", "00")
                .put("ORD_QTY", qty.toString())
                .put("ORD_UNPR", priceText(market, price))
        } else {
            JSONObject()
                .put("CANO", account.getString("account_no"))
                .put("ACNT_PRDT_CD", account.getString("product_code"))
                .put("OVRS_EXCG_CD", board.exchange)
                .put("PDNO", board.product)
                .put("ORD_QTY", qty.toString())
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
                qty.toDouble(),
                price,
                "submitted",
                JSONObject().put("tr_id", trId).put("path", path).toString(),
                response.toString().take(4000),
            ),
        )
        val summary = "$trId ${account.getString("alias")} $symbol $side ${qty}주 $message"
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

    private fun usExchange(exchange: String): String {
        return when (exchange.uppercase()) {
            "NYQ", "NYS", "NYSE" -> "NYSE"
            "ASE", "AMX", "PCX", "AMS", "AMEX" -> "AMEX"
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
