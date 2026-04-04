package com.attius.homefrontalert

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.ConcurrentHashMap

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private lateinit var toneGenerator: DynamicToneGenerator
    private lateinit var distanceCalculator: ZoneDistanceCalculator

    override fun onCreate() {
        super.onCreate()
        toneGenerator = DynamicToneGenerator(this)
        distanceCalculator = ZoneDistanceCalculator(this)
    }

    companion object {
        private val chunkBuffers = ConcurrentHashMap<String, MutableMap<Int, List<String>>>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
            
            val mode = prefs.getInt("connectivity_mode", 0)
            if (prefs.getBoolean("shield_active", false) && mode == 0) {
                Log.i("HomeFrontAlerts", "FCM received while in AUTO failover — stopping local polling.")
                prefs.edit().putBoolean("shield_active", false).apply()
                stopService(Intent(this, LocalPollingService::class.java))
            }
            
            val alertType = alertData["type"]
            if (alertType == "KEEPALIVE") {
                Log.d("HomeFrontAlerts", "FCM Keepalive received. Heartbeat registered.")
                return 
            }

            if (alertType == "CLEAR") {
                val clearScope = alertData["clearScope"]?.trim().orEmpty()
                val clearAlertId = alertData["alertId"]?.trim().orEmpty()
                val citiesJsonStr = alertData["cities"]
                val clearZones = mutableListOf<String>()
                if (citiesJsonStr != null) {
                    try {
                        val citiesArray = JSONArray(citiesJsonStr)
                        for (i in 0 until citiesArray.length()) clearZones.add(citiesArray.getString(i))
                    } catch (e: Exception) {
                        Log.w("HomeFrontAlerts", "FCM CLEAR cities parse failed: ${e.message}")
                    }
                }

                if (clearZones.isNotEmpty()) {
                    Log.i("HomeFrontAlerts", "FCM Zone Clear received (${clearZones.size} zones).")
                    StatusManager.clearZones(this, clearZones, toneGenerator, "[FCM-CLEAR]", alertData["alertId"], remoteMessage.data.toString())
                } else if (clearScope == "global") {
                    Log.i("HomeFrontAlerts", "FCM All-Clear received.")
                    StatusManager.clearAll(this, toneGenerator)
                } else {
                    Log.w("HomeFrontAlerts", "FCM CLEAR received without zones; ignoring non-global clear payload.")
                    StatusManager.maintainState(this)
                }
                if (clearScope == "global") {
                    chunkBuffers.clear()
                } else if (clearAlertId.isNotEmpty()) {
                    chunkBuffers.remove(clearAlertId)
                }
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(StatusManager.ACTION_MAP_REFRESH))
                return
            }
            
            val citiesJsonStr = alertData["cities"]
            val alertId = alertData["alertId"] ?: System.currentTimeMillis().toString()
            val chunkInfo = alertData["chunkInfo"]
            val canonicalType = alertData["canonicalType"]?.trim().orEmpty()
            val legacyTitle = alertData["legacyTitle"]?.trim().orEmpty()
            val legacyCat = alertData["legacyCat"]?.trim().orEmpty()

            if (citiesJsonStr != null) {
                try {
                    val citiesArray = JSONArray(citiesJsonStr)
                    val chunkCities = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) chunkCities.add(citiesArray.getString(i))

                    val (type, classificationPath) = when {
                        canonicalType.isNotEmpty() -> {
                            Pair(AlertStyleRegistry.getStyle("", canonicalType), "canonical")
                        }
                        legacyTitle.isNotEmpty() -> {
                            Pair(AlertStyleRegistry.getStyle(legacyCat, legacyTitle), "legacy_title")
                        }
                        legacyCat.isNotEmpty() -> {
                            Pair(AlertStyleRegistry.getStyle(legacyCat, alertType ?: ""), "legacy_cat")
                        }
                        else -> {
                            Pair(AlertStyleRegistry.getStyle("", alertType ?: ""), "legacy_type")
                        }
                    }

                    StatusManager.logFcmDiagnostic(
                        this,
                        "classificationPath=$classificationPath canonical=$canonicalType legacyTitle=${legacyTitle.take(80)} legacyCat=$legacyCat resolvedType=$type"
                    )

                    if (chunkInfo != null && chunkInfo.contains("/")) {
                        val parts = chunkInfo.split("/")
                        val chunkIdx = parts[0].toIntOrNull() ?: 1
                        val totalChunks = parts[1].toIntOrNull() ?: 1

                        if (totalChunks > 1) {
                            val buffer = chunkBuffers.computeIfAbsent(alertId) { ConcurrentHashMap() }
                            buffer[chunkIdx] = chunkCities
                            
                            val processFullPayload = Runnable {
                                val buffer = chunkBuffers.remove(alertId) ?: return@Runnable
                                val fullCities = mutableListOf<String>()
                                for (i in 1..totalChunks) { buffer[i]?.let { fullCities.addAll(it) } }
                                StatusManager.logFcmDiagnostic(this@MyFirebaseMessagingService, "Assembled ${buffer.size}/$totalChunks chunks internally")
                                StatusManager.processAlert(this@MyFirebaseMessagingService, alertId, type, fullCities, "[FCM-CHUNKS]", toneGenerator, remoteMessage.data.toString())
                                LocalBroadcastManager.getInstance(this@MyFirebaseMessagingService)
                                    .sendBroadcast(Intent(StatusManager.ACTION_MAP_REFRESH))
                            }

                            // If this is the FIRST chunk received for this alert, set a safety fallback timer
                            if (buffer.size == 1) {
                                handler.postDelayed(processFullPayload, 2000) // Wait up to 2.0s for remaining chunks before processing what we have
                            }

                            if (buffer.size == totalChunks) {
                                // We have all chunks, process immediately (the timeout runnable will become a no-op)
                                processFullPayload.run()
                            } else {
                                Log.d("HomeFrontAlerts", "Buffered chunk $chunkIdx/$totalChunks for $alertId")
                            }
                            return
                        }
                    }

                    // Single-chunk / Normal behavior
                    StatusManager.logFcmDiagnostic(this, remoteMessage.data.toString())
                    StatusManager.processAlert(this, alertId, type, chunkCities, "[FCM]", toneGenerator, remoteMessage.data.toString())
                    // Notify MapFragment to refresh immediately
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(StatusManager.ACTION_MAP_REFRESH))

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
