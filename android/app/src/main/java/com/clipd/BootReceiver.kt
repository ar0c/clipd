package com.clipd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences(Sync.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("manually_stopped", false)) return
            context.startForegroundService(Intent(context, ClipSyncService::class.java))
        }
    }
}
