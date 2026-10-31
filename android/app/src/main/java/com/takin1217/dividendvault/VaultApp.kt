package com.takin1217.dividendvault

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.takin1217.dividendvault.data.VaultDb
import com.takin1217.dividendvault.notify.VaultNotifier
import com.takin1217.dividendvault.sync.DailySyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

enum class Phase { Idle, Running, Done, Error, Delayed }

class VaultApp : Application() {
    lateinit var database: VaultDb
    val status = MutableStateFlow("대기")
    val running = MutableStateFlow(false)
    val phase = MutableStateFlow(Phase.Idle)
    val unlocked = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        database = VaultDb(this)
        VaultNotifier(this).ensure()
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(false)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "dividend-vault-daily",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun reopen() {
        database.close()
        database = VaultDb(this)
    }
}
