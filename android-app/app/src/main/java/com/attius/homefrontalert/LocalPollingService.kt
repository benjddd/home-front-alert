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
 * A highly-optimized, localized connectivity shield that bypasses Firebase 
 * to provided calculated sub-second latency for critical life-safety alerts.
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
        const val CHANNEL_ID = "LocalShieldChannel"
    }

    override fun onCreate() {
        super.onCreate()
        distanceCalculator = ZoneDistanceCalculator(this)
        locationManager = AppLocationManager.getInstance(this)
        locationManager.startTracking()
        toneGenerator = DynamicToneGenerator(this)
        createNotificationChannel()
        val notification = createStatusNotification("Direct Shield Active", "GREEN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        isRunning = true
        getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE).edit().putBoolean("shield_active", true).apply()
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
        
        Log.i("HomeFrontAlerts", "Connectivity Shield Service Started")
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Direct Connectivity Shield",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateForegroundNotification(status: String) {
        val sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val zone = sharedPrefs.getString("current_home_zone", "Jerusalem")
        val vol = (sharedPrefs.getFloat("alert_volume", 1.0f) * 100).toInt()
        
        val statusText = when(status) {
            "RED" -> getString(R.string.notif_critical)
            "ORANGE" -> getString(R.string.notif_warning)
            "YELLOW" -> getString(R.string.notif_threat)
            else -> getString(R.string.notif_ok)
        }
        
        // 10-Minute Summary Logic
        val threatsStr = sharedPrefs.getString("active_threat_map", "{}") ?: "{}"
        val tenMinsMs = 10 * 60 * 1000L
        var alertsCount10m = 0
        var closestDist10m = Double.MAX_VALUE
        try {
            val threats = org.json.JSONObject(threatsStr)
            val iter = threats.keys()
            val res = locationManager.resolveCurrentLocation()
            val recentZones = mutableListOf<String>()
            while(iter.hasNext()) {
                val z = iter.next()
                val obj = threats.getJSONObject(z)
                val t = obj.optLong("t", 0L)
                val rawName = obj.optString("name", z)
                if (System.currentTimeMillis() - t <= tenMinsMs) {
                    alertsCount10m++
                    recentZones.add(rawName)
                }
            }
            if (recentZones.isNotEmpty()) {
                val dists = distanceCalculator.calculateDistancesToAlerts(res.lat, res.lng, recentZones)
                if (dists.isNotEmpty()) {
                    closestDist10m = dists.minOrNull() ?: Double.MAX_VALUE
                }
            }
        } catch(e: Exception) {}

        var recentText = ""
        if (alertsCount10m > 0) {
            val distText = if (closestDist10m == Double.MAX_VALUE) getString(R.string.remote_alert) else String.format("%.1f km", closestDist10m)
            recentText = "\n📍 " + getString(R.string.recent_alerts_summary, alertsCount10m, distText)
        }

        val content = "Zone: $zone | Vol: $vol%\n$statusText$recentText"
        val notification = createStatusNotification(content, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createStatusNotification(content: String, status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val color = when(status) {
            "RED" -> Color.RED
            "ORANGE" -> Color.parseColor("#FF9500")
            "YELLOW" -> Color.YELLOW
            else -> Color.GREEN
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(color)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColorized(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
