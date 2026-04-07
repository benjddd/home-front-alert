package com.attius.homefrontalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts LocalPollingService after device reboot.
 *
 * Only relevant for:
 * - Standard users (always on Direct HFC)
 * - Pro users who have manually switched to Direct HFC mode (shield_active = true)
 *
 * Pro users in FCM mode don't need this — FCM wakes the app on its own.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(StatusManager.PREFS_NAME, Context.MODE_PRIVATE)
        val shieldActive = prefs.getBoolean("shield_active", !BuildConfig.IS_PAID)

        if (shieldActive) {
            Log.d("HomeFrontAlerts", "Boot complete — restarting LocalPollingService (shield_active=true)")
            val serviceIntent = Intent(context, LocalPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Log.d("HomeFrontAlerts", "Boot complete — FCM mode active, no service restart needed")
        }
    }
}
