package com.takin1217.dividendvault

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.takin1217.dividendvault.data.VaultDb
import com.takin1217.dividendvault.sync.DailySyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

class VaultApp : Application() {
    lateinit var database: VaultDb
    val status = MutableStateFlow("대기")
    val running = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        database = VaultDb(this)
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "dividend-vault-daily",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun reopen() {
        database.close()
        database = VaultDb(this)
    }
}
