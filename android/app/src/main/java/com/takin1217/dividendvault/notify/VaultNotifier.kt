package com.takin1217.dividendvault.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.takin1217.dividendvault.R

class VaultNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensure() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(channel("vault_progress", "진행", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(channel("vault_events", "완료·오류·토큰·매매", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun progress(title: String, text: String): Notification {
        ensure()
        val notice = base("vault_progress", title, text).setOngoing(true).setOnlyAlertOnce(true).build()
        manager.notify(PROGRESS_ID, notice)
        return notice
    }

    fun done(title: String, text: String) {
        manager.cancel(PROGRESS_ID)
        event(DONE_ID, title, text)
    }

    fun error(title: String, text: String) {
        manager.cancel(PROGRESS_ID)
        event(ERROR_ID, title, text)
    }

    fun delayed(title: String, text: String) {
        event(DELAY_ID, title, text)
    }

    fun token(text: String) = event(TOKEN_ID, "접근 토큰", text)

    fun trade(text: String) = event(TRADE_ID, "주문", text)

    fun backup(text: String) = event(BACKUP_ID, "백업", text)

    private fun event(id: Int, title: String, text: String) {
        ensure()
        manager.notify(id, base("vault_events", title, text).setAutoCancel(true).build())
    }

    private fun base(channelId: String, title: String, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_vault)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
    }

    private fun channel(id: String, name: String, importance: Int): NotificationChannel {
        return NotificationChannel(id, name, importance).apply { description = "배당금고 $name" }
    }

    companion object {
        const val PROGRESS_ID = 7
        private const val TOKEN_ID = 11
        private const val BACKUP_ID = 12
        private const val TRADE_ID = 13
        private const val DONE_ID = 14
        private const val ERROR_ID = 15
        private const val DELAY_ID = 16
    }
}
