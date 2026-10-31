package com.takin1217.dividendvault.sync

import android.content.Context
import com.takin1217.dividendvault.VaultApp
import java.io.File
import java.time.LocalDate

fun backupVault(context: Context): File {
    val app = context.applicationContext as VaultApp
    app.database.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
    val source = context.getDatabasePath("vault.db")
    val dir = File(context.filesDir, "backups").apply { mkdirs() }
    val dest = File(dir, "vault-${LocalDate.now()}.sqlite")
    source.copyTo(dest, overwrite = true)
    dir.listFiles { file -> file.name.startsWith("vault-") && file.name.endsWith(".sqlite") }
        ?.sortedByDescending { it.name }
        ?.drop(7)
        ?.forEach { it.delete() }
    return dest
}
