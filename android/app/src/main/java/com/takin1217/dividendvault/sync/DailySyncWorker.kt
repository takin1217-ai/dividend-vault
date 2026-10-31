package com.takin1217.dividendvault.sync

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.takin1217.dividendvault.Phase
import com.takin1217.dividendvault.VaultApp
import com.takin1217.dividendvault.notify.VaultNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notice = VaultNotifier(applicationContext).progress("일봉·백업", "백그라운드에서 이어 받는 중")
        return ForegroundInfo(VaultNotifier.PROGRESS_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as VaultApp
        val notifier = VaultNotifier(applicationContext)
        if (app.running.value) {
            app.phase.value = Phase.Delayed
            notifier.delayed("일봉 수신 지연", "다른 수신이 끝나기를 기다립니다.")
            return@withContext Result.success()
        }
        app.running.value = true
        app.phase.value = Phase.Running
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            notifier.progress("일봉·백업", "백그라운드에서 이어 받는 중")
        }
        try {
            val failed = SyncEngine(applicationContext).syncSelected(force = false)
            val backup = backupVault(applicationContext)
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
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 3) {
                app.phase.value = Phase.Delayed
                app.status.value = error.message ?: "수신이 지연되었습니다."
                notifier.delayed("일봉 수신 지연", app.status.value)
                Result.retry()
            } else {
                app.phase.value = Phase.Error
                app.status.value = error.message ?: "수신에 실패했습니다."
                notifier.error("일봉 수신 오류", app.status.value)
                Result.failure()
            }
        } finally {
            app.running.value = false
        }
    }
}
