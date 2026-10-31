package com.takin1217.dividendvault.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.takin1217.dividendvault.VaultApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as VaultApp
        if (app.running.value) return@withContext Result.success()
        app.running.value = true
        try {
            SyncEngine(applicationContext).syncSelected(force = false)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            app.running.value = false
        }
    }
}
