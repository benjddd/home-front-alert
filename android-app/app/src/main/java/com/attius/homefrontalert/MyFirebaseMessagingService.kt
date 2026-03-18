package com.attius.homefrontalert

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import android.content.Context
import android.content.Intent

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private lateinit var toneGenerator: DynamicToneGenerator
    private lateinit var distanceCalculator: ZoneDistanceCalculator

    override fun onCreate() {
        super.onCreate()
        toneGenerator = DynamicToneGenerator(this)
        distanceCalculator = ZoneDistanceCalculator(this)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (!BuildConfig.IS_PAID) return

        Log.d("HomeFrontAlerts", "FCM Message received from: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            val alertData = remoteMessage.data
            
            // Auto-Failover Recovery: We received FCM, so backend must be healthy.
            val prefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            
            // Record the heartbeat
            prefs.edit().putLong("last_fcm_heartbeat_ms", System.currentTimeMillis()).apply()
            
            // If we are currently in failover mode (shield_active = true) AND mode is AUTO, turn it off.
            val mode = prefs.getInt("connectivity_mode", 0)
            if (prefs.getBoolean("shield_active", false) && mode == 0) {
                Log.i("HomeFrontAlerts", "FCM received while in AUTO failover! Stopping Direct Shield.")
                prefs.edit().putBoolean("shield_active", false).apply()
                stopService(Intent(this, LocalPollingService::class.java))
            }
            
            val alertType = alertData["type"]
            if (alertType == "KEEPALIVE") {
                Log.d("HomeFrontAlerts", "FCM Keepalive received. Heartbeat registered.")
                return // Silently drop the message now that we've updated the timer.
            }
            
            val citiesJsonStr = alertData["cities"]
            val alertId = alertData["alertId"] ?: System.currentTimeMillis().toString()

            if (citiesJsonStr != null) {
                try {
                    val citiesArray = JSONArray(citiesJsonStr)
                    val cities = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) cities.add(citiesArray.getString(i))

                    val type = AlertStyleRegistry.getStyle("", alertType ?: "")

                    // Log raw FCM metadata for diagnostics
                    StatusManager.logFcmDiagnostic(this, remoteMessage.data.toString())

                    StatusManager.processAlert(this, alertId, type, cities, "[FCM]", toneGenerator, remoteMessage.data.toString())

                } catch (e: Exception) {
                    Log.e("HomeFrontAlerts", "FCM processing failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Called when the FCM token is rotated (reinstall, token expiry, etc.).
     * Re-subscribe to the alerts topic so delivery is never silently broken.
     */
    override fun onNewToken(token: String) {
        Log.d("HomeFrontAlerts", "FCM token refreshed — re-subscribing to alerts topic")
        if (BuildConfig.IS_PAID) {
            FirebaseMessaging.getInstance().subscribeToTopic("alerts")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) Log.d("HomeFrontAlerts", "Re-subscribed to alerts topic after token refresh")
                    else Log.w("HomeFrontAlerts", "Failed to re-subscribe after token refresh: ${task.exception?.message}")
                }
        }
    }
}
