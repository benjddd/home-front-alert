package com.attius.homefrontalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Foreground service that polls HFC directly at 2-second intervals when
 * Firebase delivery is unavailable or the user has selected Direct mode.
 * Runs silently in the background — the foreground notification is kept
 * at IMPORTANCE_MIN and carries no user-facing branding.
 */
class LocalPollingService : Service() {

    private var pollingThread: Thread? = null
    private var isRunning = false

    private lateinit var distanceCalculator: ZoneDistanceCalculator
    private lateinit var toneGenerator: DynamicToneGenerator
    private lateinit var locationManager: AppLocationManager
    private val recentlyPlayedAlerts = mutableSetOf<String>()

    companion object {
        const val NOTIFICATION_ID = 991
        const val ALERT_NOTIFICATION_ID = 992
        const val SHIELD_CHANNEL_ID = "LocalShieldStatusChannel"
        const val ALERT_CHANNEL_ID = "ActiveAlertChannel"
    }

    override fun onCreate() {
        super.onCreate()
        distanceCalculator = ZoneDistanceCalculator(this)
        locationManager = AppLocationManager.getInstance(this)
        locationManager.startTracking()
        toneGenerator = DynamicToneGenerator(this)
        createNotificationChannel()
        val notification = createStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        isRunning = true
        getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE).edit().apply {
            putBoolean("shield_active", true)
            putLong("shield_start_ms", System.currentTimeMillis())
            apply()
        }
        pollingThread = kotlin.concurrent.thread(start = true) {
            while (isRunning) {
                try {
                    StatusManager.runPollCycle(this, toneGenerator = toneGenerator)
                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "Poll cycle error", e)
                }
                try {
                    Thread.sleep(2000) // 2s direct HFC poll rate
                } catch (e: InterruptedException) {
                    isRunning = false
                    break
                }
            }
        }
        
        Log.i("HomeFrontAlerts", "LocalPollingService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_NOTIFICATION") {
            val status = intent.getStringExtra("status") ?: "GREEN"
            updateForegroundNotification(status)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE).edit().putBoolean("shield_active", false).apply()
        pollingThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Quiet Background Status Channel (The "App is running" notification)
            val shieldChannel = NotificationChannel(
                SHIELD_CHANNEL_ID,
                "Alert Status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Shows current alert status while the app is monitoring in the background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(shieldChannel)

            // 2. High-Priority Active Alert Channel (The "Red Siren" popup)
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Active Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH // HIGH forces head-up display drop-down
            ).apply {
                description = "Critical alerts when threats are detected."
                enableVibration(true)
                enableLights(true)
                lightColor = Color.RED
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun updateForegroundNotification(status: String) {
        val notification = createStatusNotification(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createStatusNotification(status: String = "GREEN"): Notification {
        val prefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val startTime = prefs.getLong("shield_start_ms", System.currentTimeMillis())

        val rawZone = prefs.getString("current_home_zone", "") ?: ""
        val zone = ZoneDistanceCalculator(this).getLocalizedName(rawZone)

        val statusLabel = when (status) {
            "RED"    -> getString(R.string.critical_status)
            "ORANGE" -> getString(R.string.warning_status)
            "YELLOW" -> getString(R.string.threat_status)
            else     -> getString(R.string.no_alerts)
        }

        val contentText = if (status == "GREEN") {
            "$statusLabel • $zone"
        } else {
            val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
            val count = try { org.json.JSONObject(threatsStr).length() } catch (e: Exception) { 0 }
            if (count > 0) "$statusLabel • $zone • $count ${if (count == 1) "zone" else "zones"}"
            else "$statusLabel • $zone"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, SHIELD_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setUsesChronometer(true)
            .setWhen(startTime)
            .setColorized(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
