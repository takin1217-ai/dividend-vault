package com.takin1217.dividendvault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import com.takin1217.dividendvault.data.KisBroker
import com.takin1217.dividendvault.data.KisBroker.AccountCard
import com.takin1217.dividendvault.data.Research
import com.takin1217.dividendvault.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var market by remember { mutableStateOf("US") }
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
    var exchange by remember { mutableStateOf("NASD") }
    var productCode by remember { mutableStateOf("01") }
    var vnExchange by remember { mutableStateOf("VNSE") }
    var assign by remember { mutableStateOf(true) }
    var linked by remember { mutableStateOf(KisBroker.MARKET_CODES.toSet()) }
    var accounts by remember { mutableIntStateOf(0) }
    val broker = remember { KisBroker(activity) }
    val cards = remember(accounts) { broker.cards() }
    val selection = remember(accounts) { broker.selectionLines() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importVault(activity, uri)
            report = "금고를 가져왔습니다. 기존 파일은 덮어쓰기 전에 같은 폴더에 남겨 두지 않으므로, 가져오기 전 내보내기를 먼저 하세요."
        }
    }
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
        Text("최소 보유는 미국 1000, 중국·일본·한국 250, 홍콩 150, 베트남·영국 50입니다. 일봉은 그 2배 이상을 최대 10년치 받습니다. 과거 모의와 가상 모의가 끝나기 전에는 실전 주문이 열리지 않습니다. 손실 매도는 하지 않습니다.")
        Text(report)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Button(onClick = {
                ContextCompat.startForegroundService(activity, Intent(activity, SyncService::class.java))
                report = "일봉 동기화를 시작했습니다. 알림에서 진행을 봅니다."
            }) { Text("일봉 이어받기") }
            Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text("금고 가져오기") }
            Button(onClick = { report = exportVault(activity) }) { Text("금고 내보내기") }
        }
        OutlinedTextField(market, { market = it.uppercase() }, label = { Text("시장 코드 US CN HK JP VN KR GB") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                report = "과거 모의를 계산하는 중"
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val settings = app.database.readSettings()
                        val result = Research.yieldTilt(
                            app.database.readableDatabase,
                            market,
                            settings.optDouble("min_yield", 0.02),
                            settings.optDouble("max_yield", 0.12),
                            settings.optDouble("fee_bps", 10.0),
                        )
                        val cagr = result.cagr?.let { "%.2f%%".format(it * 100) } ?: "—"
                        "배당 기울기 CAGR $cagr · 총수익 ${"%.2f%%".format(result.totalReturn * 100)} · MDD ${"%.2f%%".format(result.maxDrawdown * 100)} · 보유 ${result.holdings}"
                    }.getOrElse { it.message ?: "실패" }
                }
                report = text
            }
        }) { Text("배당 기울기 과거 모의") }
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) { runCatching { applyPaper(app, market) }.getOrElse { it.message ?: "실패" } }
            }
        }) { Text("가상 계좌에 최신 비중 반영") }
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    runCatching { sellDividendBreaks(app, market) }.getOrElse { it.message ?: "배당 매도 실패" }
                }
            }
        }) { Text("배당 축소면 이익일 때만 매도") }
        Button(onClick = {
            scope.launch {
                report = "AI 자동 매매를 계산하는 중"
                report = withContext(Dispatchers.IO) {
                    KisBroker.MARKET_CODES.joinToString("\n") { code ->
                        val line = runCatching { applyPaper(app, code) }.getOrElse { it.message ?: "실패" }
                        "$code $line"
                    } + "\n실전 주문은 보내지 않았습니다. 손실 매도는 하지 않습니다."
                }
            }
        }) { Text("AI 자동 매매") }
        Text("배당이 줄거나 끊기면 한투 수수료·세금 뒤에도 이익인 수량만 팝니다. 손실이면 보유합니다. 베트남 매수는 최소 2억 동입니다.")
        Text("한 계좌에 7개국을 모두 넣을 수 있고, 나라는 최소 1개입니다. 같은 앱키의 토큰은 한 번만 받아 만료 전까지 다시 쓰지 않습니다. 키와 토큰은 암호화되어 이 기기에만 있습니다. 영국은 주문 코드가 없습니다. 중국은 .SS 상해, .SZ 심천입니다.")
        Text(selection)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { linked = KisBroker.MARKET_CODES.toSet() }) { Text("7개국 모두") }
        }
        MarketChips(linked) { next -> if (next.isNotEmpty()) linked = next }
        OutlinedTextField(alias, { alias = it }, label = { Text("별칭") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mode, { mode = it }, label = { Text("paper 또는 live") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appKey, { appKey = it }, label = { Text("앱키") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appSecret, { appSecret = it }, label = { Text("앱시크릿") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accountNo, { accountNo = it }, label = { Text("계좌 8자리") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(productCode, { productCode = it }, label = { Text("상품코드") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exchange, { exchange = it.uppercase() }, label = { Text("미국 거래소 NASD NYSE AMEX") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vnExchange, { vnExchange = it.uppercase() }, label = { Text("베트남 VNSE 또는 HASE") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(assign, { assign = it })
            Text("고른 나라의 주문 계좌로 지정")
        }
        Button(onClick = {
            runCatching {
                broker.add(alias, mode, appKey, appSecret, accountNo, linked, productCode, exchange, vnExchange, assign)
                appSecret = ""
                accounts += 1
                report = "계좌를 암호화해 기기에만 저장했습니다."
            }.onFailure { report = it.message ?: "저장 실패" }
        }) { Text("계좌 추가") }
        cards.forEach { card ->
            AccountRow(card, onSelect = { code ->
                runCatching { broker.select(card.id, code); accounts += 1; report = "${KisBroker.NAMES[code]} 주문 계좌를 ${card.alias}로 지정했습니다." }
                    .onFailure { report = it.message ?: "지정 실패" }
            }, onPing = {
                scope.launch {
                    report = withContext(Dispatchers.IO) { runCatching { broker.ping(cards.indexOf(card)) }.getOrElse { it.message ?: "토큰 실패" } }
                    accounts += 1
                }
            }, onDelete = {
                broker.delete(card.id)
                accounts += 1
                report = "${card.alias} 계좌를 지웠습니다."
            })
        }
        OutlinedTextField(symbol, { symbol = it }, label = { Text("종목") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(side, { side = it }, label = { Text("buy 또는 sell") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(qty, { qty = it }, label = { Text("정수 수량") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it }, label = { Text("지정가") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirm, { confirm = it }, label = { Text("모의주문확인 또는 실전주문확인") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    runCatching {
                        broker.order(0, market, symbol, side, qty.toInt(), price.toDouble(), confirm, exchange)
                    }.getOrElse { it.message ?: "주문 실패" }
                }
            }
        }) { Text("주문 전송") }
        Text("학습 기울기의 릿지 회귀는 연구 단말과 같은 과거 데이터로만 맞춥니다. 폰에서는 그 금고의 일봉으로 배당 기울기를 계산합니다.")
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
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    val mode = if (card.mode == "live") "실전" else "모의"
    val names = card.markets.joinToString(" ") { KisBroker.NAMES[it] ?: it }
    val active = card.selectedMarkets.joinToString(" ") { KisBroker.NAMES[it] ?: it }
    Text("$mode ${card.alias} ${card.mask} · 상품 ${card.productCode} · $names")
    Text("미국 ${card.usExchange} · 베트남 ${if (card.vnExchange == "HASE") "HNX" else "HOSE"} · ${card.token}" + if (active.isBlank()) "" else " · 주문지정 $active")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        card.markets.forEach { code ->
            Button(onClick = { onSelect(code) }) { Text("${KisBroker.NAMES[code]} 주문") }
        }
        Button(onClick = onPing) { Text("토큰") }
        Button(onClick = onDelete) { Text("삭제") }
    }
}

private fun importVault(activity: MainActivity, uri: Uri) {
    val app = activity.application as VaultApp
    app.database.close()
    val target = activity.getDatabasePath("vault.db")
    activity.contentResolver.openInputStream(uri).use { input ->
        target.outputStream().use { output -> input?.copyTo(output) }
    }
    File(target.path + "-wal").delete()
    File(target.path + "-shm").delete()
    app.reopen()
}

private fun exportVault(activity: MainActivity): String {
    val app = activity.application as VaultApp
    app.database.readableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
    val dir = File(activity.filesDir, "exports").apply { mkdirs() }
    val dest = File(dir, "vault-export.sqlite")
    activity.getDatabasePath("vault.db").copyTo(dest, overwrite = true)
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", dest)
    val share = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    activity.startActivity(Intent.createChooser(share, "금고 내보내기"))
    return dest.absolutePath
}

private fun latestSession(db: android.database.sqlite.SQLiteDatabase): String {
    return db.rawQuery("SELECT MAX(date) FROM bars", null).use { cursor ->
        if (cursor.moveToFirst() && cursor.getString(0) != null) cursor.getString(0) else java.time.LocalDate.now().toString()
    }
}

private fun sellDividendBreaks(app: VaultApp, market: String): String {
    val db = app.database.writableDatabase
    db.beginTransaction()
    return try {
        val notes = com.takin1217.dividendvault.data.TradeRules.settle(db, market, latestSession(db))
        db.setTransactionSuccessful()
        notes.joinToString("\n")
    } finally {
        db.endTransaction()
    }
}

private fun applyPaper(app: VaultApp, market: String): String {
    val settings = app.database.readSettings()
    val result = Research.yieldTilt(
        app.database.readableDatabase,
        market,
        settings.optDouble("min_yield", 0.02),
        settings.optDouble("max_yield", 0.12),
        settings.optDouble("fee_bps", 10.0),
    )
    val db = app.database.writableDatabase
    val markets = settings.getJSONArray("markets")
    var seed = 0.0
    var currency = ""
    for (index in 0 until markets.length()) {
        val row = markets.getJSONObject(index)
        if (row.getString("code") == market) {
            seed = row.optDouble("paper_cash")
            currency = row.optString("currency")
        }
    }
    db.beginTransaction()
    try {
        db.execSQL(
            "INSERT INTO paper_accounts(market, name, currency, cash, created_at) VALUES(?,?,?,?,datetime('now')) ON CONFLICT(market) DO NOTHING",
            arrayOf(market, "$market 가상", currency, seed),
        )
        val notes = com.takin1217.dividendvault.data.TradeRules.settle(db, market, latestSession(db))
        val accountId = db.rawQuery("SELECT id, cash FROM paper_accounts WHERE market=?", arrayOf(market)).use { cursor ->
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
            val budget = kotlin.math.min(cash, (cash + seed) * weight)
            val shares = com.takin1217.dividendvault.data.TradeRules.buyShares(market, budget, price)
            if (shares <= 0) return@forEach
            val notional = shares * price
            val fee = com.takin1217.dividendvault.data.TradeRules.charge(market, "buy", notional)
            if (notional + fee > cash) return@forEach
            cash -= notional + fee
            bought += 1
            db.execSQL("INSERT INTO paper_positions(account_id, symbol, qty, avg_price) VALUES(?,?,?,?)", arrayOf(accountId, symbol, shares, price))
            db.execSQL(
                "INSERT INTO paper_orders(account_id, symbol, side, qty, status, reason, created_at, filled_at, fill_price, fee) VALUES(?,?,?,?,?,?,datetime('now'),datetime('now'),?,?)",
                arrayOf(accountId, symbol, "buy", shares, "filled", "한투 최소 수량·수수료 반영", price, fee),
            )
        }
        db.execSQL("UPDATE paper_accounts SET cash=? WHERE id=?", arrayOf(cash, accountId))
        db.setTransactionSuccessful()
        val sold = notes.count { it.contains("매도") }
        return "배당 축소·무배당 이익 매도 ${sold}건, 신규 매수 ${bought}종목. 손실 보유는 그대로 둡니다. 베트남은 2억 동 미만이면 사지 않습니다."
    } finally {
        db.endTransaction()
    }
}
