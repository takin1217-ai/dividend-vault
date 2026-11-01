package com.takin1217.dividendvault

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.takin1217.dividendvault.data.KisBroker
import com.takin1217.dividendvault.data.KisBroker.AccountCard
import com.takin1217.dividendvault.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE0B56A), background = Color(0xFF101613), surface = Color(0xFF18211C))) {
                Surface(modifier = Modifier.fillMaxSize()) { Desk(this) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Desk(activity: MainActivity) {
    val app = activity.application as VaultApp
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("수익을 보장하지 않습니다. 실전 주문은 확인 문구를 입력할 때만 전송됩니다.") }
    var orderMarket by remember { mutableStateOf("US") }
    var alias by remember { mutableStateOf("모의") }
    var mode by remember { mutableStateOf("paper") }
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var accountNo by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("AAPL") }
    var side by remember { mutableStateOf("buy") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var productCode by remember { mutableStateOf("01") }
    var assign by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf(readRuleFields(app)) }
    var minYield by remember { mutableStateOf("2") }
    var maxYield by remember { mutableStateOf("12") }
    var cutRatio by remember { mutableStateOf("20") }
    var weightCap by remember { mutableStateOf("4") }
    var maxHoldings by remember { mutableStateOf("40") }
    var rebalanceDays by remember { mutableStateOf("21") }
    var feeBps by remember { mutableStateOf("10") }
    var paperCash by remember { mutableStateOf("") }
    var linked by remember { mutableStateOf(KisBroker.MARKET_CODES.toSet()) }
    var accounts by remember { mutableIntStateOf(0) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val broker = remember { KisBroker(activity) }
    val cards = remember(accounts) { broker.cards() }
    val selection = remember(accounts) { broker.selectionLines() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
    val compact = maxWidth < 420.dp
    val pad = if (compact) 12.dp else 20.dp
    val gap = if (compact) 6.dp else 8.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text("배당금고", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
        Text("손실 매도는 하지 않습니다. 미국·베트남 거래소는 종목에서 정합니다.")
        Text(report)
        Button(onClick = {
            ContextCompat.startForegroundService(activity, Intent(activity, SyncService::class.java))
            report = "나라별 2배 일봉 수신을 시작했습니다. 알림에서 진행을 봅니다."
        }) { Text("2배 일봉 확보") }
        Button(onClick = {
            scope.launch {
                report = "AI 자동 매매를 계산하는 중"
                report = withContext(Dispatchers.IO) {
                    KisBroker.MARKET_CODES.joinToString("\n") { code ->
                        val line = runCatching { com.takin1217.dividendvault.data.PaperBook.run(app, code) }.getOrElse { it.message ?: "실패" }
                        "$code $line"
                    } + "\n실전 주문은 보내지 않았습니다."
                }
            }
        }) { Text("AI 자동 매매") }
        Text("나라별 최소 보유와 최소 주문 금액. 금액 이상인 종목은 1주문단위만 삽니다.")
        KisBroker.MARKET_CODES.forEach { code ->
            val pair = rules[code] ?: ("1" to "0")
            OutlinedTextField(pair.first, { rules = rules + (code to (it to pair.second)) }, label = { Text("${KisBroker.NAMES[code]} 최소 보유") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pair.second, { rules = rules + (code to (pair.first to it)) }, label = { Text("${KisBroker.NAMES[code]} 최소 주문 ${CURRENCIES[code]}") }, modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = {
            runCatching {
                rules.forEach { (code, pair) ->
                    val count = pair.first.trim().toIntOrNull() ?: error("${KisBroker.NAMES[code]} 최소 보유는 정수입니다.")
                    val amount = pair.second.trim().replace(",", "").toDoubleOrNull() ?: error("${KisBroker.NAMES[code]} 최소 주문 금액을 입력하세요.")
                    app.database.saveMarketRule(code, count, amount)
                }
                report = "나라별 보유 수와 주문 금액을 저장했습니다. 일봉 목표는 보유 수의 2배입니다."
            }.onFailure { report = it.message ?: "저장 실패" }
        }) { Text("나라 조건 저장") }
        Text(selection)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { linked = KisBroker.MARKET_CODES.toSet() }) { Text("7개국 모두") }
        }
        MarketChips(linked) { next -> if (next.isNotEmpty()) linked = next }
        OutlinedTextField(alias, { alias = it }, label = { Text("별칭") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "paper", onClick = { mode = "paper" }, label = { Text("모의") })
            FilterChip(selected = mode == "live", onClick = { mode = "live" }, label = { Text("실전") })
        }
        OutlinedTextField(appKey, { appKey = it }, label = { Text(if (editingId == null) "앱키" else "앱키, 비우면 유지") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appSecret, { appSecret = it }, label = { Text(if (editingId == null) "앱시크릿" else "앱시크릿, 비우면 유지") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accountNo, { accountNo = it }, label = { Text(if (editingId == null) "계좌 8자리" else "계좌 8자리, 비우면 유지") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(productCode, { productCode = it }, label = { Text("상품코드") }, modifier = Modifier.fillMaxWidth())
        Text("이 계좌의 매매 조건입니다.")
        OutlinedTextField(minYield, { minYield = it }, label = { Text("최소 배당률 %") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(maxYield, { maxYield = it }, label = { Text("최대 배당률 %") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cutRatio, { cutRatio = it }, label = { Text("배당축소 기준 %") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(weightCap, { weightCap = it }, label = { Text("한 종목 비중 상한 배") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(maxHoldings, { maxHoldings = it }, label = { Text("최대 보유 종목, 0이면 조건 전체") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(rebalanceDays, { rebalanceDays = it }, label = { Text("리밸런스 간격 일") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(feeBps, { feeBps = it }, label = { Text("모의 회전 수수료 bp") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(paperCash, { paperCash = it }, label = { Text("가상 시드, 비우면 나라 기본") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(assign, { assign = it })
            Text("고른 나라의 주문 계좌로 지정")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching {
                    val policy = KisBroker.TradePolicy.parse(minYield, maxYield, cutRatio, weightCap, maxHoldings, rebalanceDays, feeBps, paperCash)
                    val current = editingId
                    if (current == null) {
                        broker.add(alias, mode, appKey, appSecret, accountNo, linked, productCode, "NASD", "VNSE", assign, policy)
                        report = "계좌와 매매 조건을 암호화해 기기에만 저장했습니다."
                    } else {
                        broker.update(current, alias, mode, appKey, appSecret, accountNo, linked, productCode, "NASD", "VNSE", assign, policy)
                        editingId = null
                        report = "계좌 매매 조건을 저장했습니다."
                    }
                    appSecret = ""
                    accounts += 1
                }.onFailure { report = it.message ?: "저장 실패" }
            }) { Text(if (editingId == null) "계좌 추가" else "조건 저장") }
            if (editingId != null) {
                Button(onClick = { editingId = null; appSecret = "" }) { Text("수정 취소") }
            }
        }
        cards.forEach { card ->
            AccountRow(card, onSelect = { code ->
                runCatching { broker.select(card.id, code); accounts += 1; report = "${KisBroker.NAMES[code]} 주문 계좌를 ${card.alias}로 지정했습니다." }
                    .onFailure { report = it.message ?: "지정 실패" }
            }, onClear = { code ->
                broker.unselect(card.id, code)
                accounts += 1
                report = "${KisBroker.NAMES[code]} 주문 지정을 해제했습니다."
            }, onEdit = {
                val draft = broker.draft(card.id)
                editingId = draft.id
                alias = draft.alias
                mode = draft.mode
                appKey = draft.appKey
                appSecret = ""
                accountNo = draft.accountNo
                productCode = draft.productCode
                linked = draft.markets
                minYield = percentField(draft.policy.minYield)
                maxYield = percentField(draft.policy.maxYield)
                cutRatio = percentField(draft.policy.cutRatio)
                weightCap = numberField(draft.policy.weightCap)
                maxHoldings = draft.policy.maxHoldings.toString()
                rebalanceDays = draft.policy.rebalanceDays.toString()
                feeBps = numberField(draft.policy.feeBps)
                paperCash = if (draft.policy.paperCash > 0) numberField(draft.policy.paperCash) else ""
                report = "${draft.alias} 매매 조건을 고칩니다. 시크릿을 비우면 기존 값을 유지합니다."
            }, onPing = {
                scope.launch {
                    report = withContext(Dispatchers.IO) { runCatching { broker.ping(cards.indexOf(card)) }.getOrElse { it.message ?: "토큰 실패" } }
                    accounts += 1
                }
            }, onDelete = {
                if (editingId == card.id) editingId = null
                broker.delete(card.id)
                accounts += 1
                report = "${card.alias} 계좌를 지웠습니다."
            })
        }
        Text("주문 나라")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KisBroker.MARKET_CODES.forEach { code ->
                FilterChip(selected = orderMarket == code, onClick = { orderMarket = code }, label = { Text(KisBroker.NAMES[code] ?: code) })
            }
        }
        OutlinedTextField(symbol, { symbol = it }, label = { Text("종목") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "buy", onClick = { side = "buy" }, label = { Text("매수") })
            FilterChip(selected = side == "sell", onClick = { side = "sell" }, label = { Text("매도") })
        }
        if (side == "sell") {
            OutlinedTextField(qty, { qty = it }, label = { Text("매도 수량") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(price, { price = it }, label = { Text("지정가") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirm, { confirm = it }, label = { Text("모의주문확인 또는 실전주문확인") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    runCatching {
                        val amount = if (side == "sell") qty.toInt() else 0
                        broker.order(0, orderMarket, symbol, side, amount, price.toDouble(), confirm)
                    }.getOrElse { it.message ?: "주문 실패" }
                }
            }
        }) { Text("주문 전송") }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketChips(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KisBroker.MARKET_CODES.forEach { code ->
            FilterChip(
                selected = code in selected,
                onClick = {
                    val next = if (code in selected) selected - code else selected + code
                    if (next.isNotEmpty()) onChange(next)
                },
                label = { Text(KisBroker.NAMES[code] ?: code) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(
    card: AccountCard,
    onSelect: (String) -> Unit,
    onClear: (String) -> Unit,
    onEdit: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    val mode = if (card.mode == "live") "실전" else "모의"
    val names = card.markets.joinToString(" ") { KisBroker.NAMES[it] ?: it }
    val active = card.selectedMarkets.joinToString(" ") { KisBroker.NAMES[it] ?: it }
    Text("$mode ${card.alias} ${card.mask} · 상품 ${card.productCode} · $names")
    Text(card.policy)
    Text(card.token + if (active.isBlank()) "" else " · 주문지정 $active")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        card.markets.forEach { code ->
            val chosen = code in card.selectedMarkets
            Button(onClick = { if (chosen) onClear(code) else onSelect(code) }) {
                Text("${KisBroker.NAMES[code]} ${if (chosen) "해제" else "주문"}")
            }
        }
        Button(onClick = onEdit) { Text("조건 수정") }
        Button(onClick = onPing) { Text("토큰") }
        Button(onClick = onDelete) { Text("삭제") }
    }
}

private val CURRENCIES = mapOf("US" to "USD", "CN" to "CNY", "HK" to "HKD", "JP" to "JPY", "VN" to "VND", "KR" to "KRW", "GB" to "GBP")

private fun readRuleFields(app: VaultApp): Map<String, Pair<String, String>> {
    val markets = app.database.readSettings().getJSONArray("markets")
    val fields = mutableMapOf<String, Pair<String, String>>()
    for (index in 0 until markets.length()) {
        val row = markets.getJSONObject(index)
        val code = row.getString("code")
        val notional = row.optDouble("min_order_notional", com.takin1217.dividendvault.data.TradeRules.defaultNotional(code))
        fields[code] = row.optInt("min_symbols").toString() to numberField(notional)
    }
    return fields
}

private fun percentField(value: Double): String = numberField(value * 100)

private fun numberField(value: Double): String {
    val whole = value.toLong().toDouble()
    return if (kotlin.math.abs(value - whole) < 1e-9) whole.toLong().toString() else value.toString()
}
