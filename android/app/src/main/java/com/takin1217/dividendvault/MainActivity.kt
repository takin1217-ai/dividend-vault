package com.takin1217.dividendvault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.takin1217.dividendvault.data.Research
import com.takin1217.dividendvault.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importVault(activity, uri)
            report = "금고를 가져왔습니다. 기존 파일은 덮어쓰기 전에 같은 폴더에 남겨 두지 않으므로, 가져오기 전 내보내기를 먼저 하세요."
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("배당금고", style = MaterialTheme.typography.headlineMedium)
        Text("미국 1000 · 중국 250 · 일본 250 · 홍콩 150 · 베트남 50. 한국과 영국은 조건에서 켤 수 있고, 목표는 최소의 5배까지입니다.")
        Text(report)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text("한국투자증권. 모의 키와 실전 키는 다릅니다. 미국 거래소는 NASD, NYSE, AMEX 중 하나를 적으세요.")
        OutlinedTextField(alias, { alias = it }, label = { Text("별칭") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mode, { mode = it }, label = { Text("paper 또는 live") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appKey, { appKey = it }, label = { Text("앱키") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appSecret, { appSecret = it }, label = { Text("앱시크릿") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accountNo, { accountNo = it }, label = { Text("계좌 8자리") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            runCatching { KisBroker(activity).add(alias, mode, appKey, appSecret, accountNo); appSecret = ""; report = "계좌를 기기에만 저장했습니다." }
                .onFailure { report = it.message ?: "저장 실패" }
        }) { Text("계좌 추가") }
        OutlinedTextField(symbol, { symbol = it }, label = { Text("종목") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exchange, { exchange = it.uppercase() }, label = { Text("미국 거래소 NASD NYSE AMEX") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(side, { side = it }, label = { Text("buy 또는 sell") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(qty, { qty = it }, label = { Text("정수 수량") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it }, label = { Text("지정가") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirm, { confirm = it }, label = { Text("모의주문확인 또는 실전주문확인") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    runCatching { KisBroker(activity).ping(0) }.getOrElse { it.message ?: "토큰 실패" }
                }
            }
        }) { Text("토큰 시험") }
        Button(onClick = {
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    runCatching {
                        KisBroker(activity).order(0, market, symbol, side, qty.toInt(), price.toDouble(), confirm, exchange)
                    }.getOrElse { it.message ?: "주문 실패" }
                }
            }
        }) { Text("주문 전송") }
        Text("학습 기울기의 릿지 회귀는 연구 단말과 같은 과거 데이터로만 맞춥니다. 폰에서는 그 금고의 일봉으로 배당 기울기를 계산합니다. 최소 보유 수 변경은 연구 단말의 조건 화면에서 저장한 금고를 가져오면 반영됩니다.")
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
    var cash = 0.0
    var currency = ""
    for (index in 0 until markets.length()) {
        val row = markets.getJSONObject(index)
        if (row.getString("code") == market) {
            cash = row.optDouble("paper_cash")
            currency = row.optString("currency")
        }
    }
    db.beginTransaction()
    try {
        db.execSQL("INSERT INTO paper_accounts(market, name, currency, cash, created_at) VALUES(?,?,?,?,datetime('now')) ON CONFLICT(market) DO UPDATE SET cash=excluded.cash", arrayOf(market, "$market 가상", currency, cash))
        val accountId = db.rawQuery("SELECT id FROM paper_accounts WHERE market=?", arrayOf(market)).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        db.execSQL("DELETE FROM paper_positions WHERE account_id=?", arrayOf(accountId))
        var spent = 0.0
        result.weights.forEach { (symbol, weight) ->
            val price = db.rawQuery("SELECT close FROM bars WHERE symbol=? ORDER BY date DESC LIMIT 1", arrayOf(symbol)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
            if (price <= 0 || weight <= 0) return@forEach
            val qty = cash * weight / price
            spent += qty * price
            db.execSQL("INSERT INTO paper_positions(account_id, symbol, qty, avg_price) VALUES(?,?,?,?)", arrayOf(accountId, symbol, qty, price))
        }
        db.execSQL("UPDATE paper_accounts SET cash=? WHERE id=?", arrayOf((cash - spent).coerceAtLeast(0.0), accountId))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    return "가상 계좌에 ${result.weights.size}종목을 최신 종가 가정으로 반영했습니다. 평가액은 연구 단말에서도 이어서 볼 수 있습니다."
}
