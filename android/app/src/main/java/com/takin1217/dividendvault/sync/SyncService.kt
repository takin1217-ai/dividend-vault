package com.takin1217.dividendvault.sync

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.takin1217.dividendvault.Phase
import com.takin1217.dividendvault.VaultApp
import com.takin1217.dividendvault.notify.VaultNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as VaultApp
        val notifier = VaultNotifier(this)
        if (app.running.value) return START_NOT_STICKY
        app.running.value = true
        app.phase.value = Phase.Running
        val notice = notifier.progress("일봉 수신", "나라별 최소 보유의 2배 일봉을 받는 중")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(VaultNotifier.PROGRESS_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(VaultNotifier.PROGRESS_ID, notice)
        }
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        scope.launch {
            try {
                val failed = SyncEngine(this@SyncService).syncSelected(force)
                val backup = backupVault(this@SyncService)
                notifier.backup("기기 안에 백업했습니다. ${backup.name}")
                if (failed == 0) {
                    app.phase.value = Phase.Done
                    app.status.value = "수신과 백업을 마쳤습니다."
                    notifier.done("일봉 수신 완료", app.status.value)
                } else {
                    app.phase.value = Phase.Error
                    app.status.value = "일부 종목 ${failed}건을 받지 못했습니다. 받은 봉은 유지됩니다."
                    notifier.error("일봉 수신 오류", app.status.value)
                }
            } catch (error: Exception) {
                app.phase.value = Phase.Error
                app.status.value = error.message ?: "동기화 실패"
                notifier.error("일봉 수신 오류", app.status.value)
            } finally {
                app.running.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_FORCE = "force"
    }
}
