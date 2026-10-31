package com.takin1217.dividendvault.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.takin1217.dividendvault.VaultApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as VaultApp
        if (app.running.value) return START_NOT_STICKY
        app.running.value = true
        ensureChannel()
        val notice = notification("선택한 종목의 일봉을 이어 받는 중")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(7, notice)
        }
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        scope.launch {
            try {
                SyncEngine(this@SyncService).syncSelected(force)
            } catch (error: Exception) {
                app.status.value = error.message ?: "동기화 실패"
            } finally {
                app.running.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", "일봉 동기화", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "sync")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("배당금고").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).build()
    }

    companion object {
        const val EXTRA_FORCE = "force"
    }
}
