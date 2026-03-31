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

    companion object {
        private val chunkBuffers = mutableMapOf<String, MutableMap<Int, List<String>>>()
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
                Log.i("HomeFrontAlerts", "FCM received while in AUTO failover! Stopping Direct Shield.")
                prefs.edit().putBoolean("shield_active", false).apply()
                stopService(Intent(this, LocalPollingService::class.java))
            }
            
            val alertType = alertData["type"]
            if (alertType == "KEEPALIVE") {
                Log.d("HomeFrontAlerts", "FCM Keepalive received. Heartbeat registered.")
                return 
            }

            if (alertType == "CLEAR") {
                Log.i("HomeFrontAlerts", "FCM All-Clear received. Clearing active threat zones via processAlert(CALM).")
                // Read all currently active zones from the threat map and clear them properly.
                // This goes through the same code path as a real HFC all-clear message:
                // it removes zones from the map, plays the all-clear sound if the user's zone
                // was affected, and updates the UI correctly.
                val prefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
                val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
                val threats = org.json.JSONObject(threatsStr)
                val activeZones = mutableListOf<String>()
                val iter = threats.keys()
                while (iter.hasNext()) {
                    val z = iter.next()
                    val rawName = threats.optJSONObject(z)?.optString("name", z) ?: z
                    activeZones.add(rawName)
                }
                if (activeZones.isNotEmpty()) {
                    val clearId = "fcm-clear-${System.currentTimeMillis()}"
                    StatusManager.processAlert(this, clearId, AlertType.CALM, activeZones, "[FCM-CLEAR]", toneGenerator, null)
                } else {
                    // No active zones to process — just ensure status is green
                    StatusManager.updateStatus(this, "GREEN")
                }
                chunkBuffers.clear()
                return
            }
            
            val citiesJsonStr = alertData["cities"]
            val alertId = alertData["alertId"] ?: System.currentTimeMillis().toString()
            val chunkInfo = alertData["chunkInfo"]

            if (citiesJsonStr != null) {
                try {
                    val citiesArray = JSONArray(citiesJsonStr)
                    val chunkCities = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) chunkCities.add(citiesArray.getString(i))

                    val type = AlertStyleRegistry.getStyle("", alertType ?: "")

                    if (chunkInfo != null && chunkInfo.contains("/")) {
                        val parts = chunkInfo.split("/")
                        val chunkIdx = parts[0].toIntOrNull() ?: 1
                        val totalChunks = parts[1].toIntOrNull() ?: 1

                        if (totalChunks > 1) {
                            chunkBuffers.getOrPut(alertId) { mutableMapOf() }[chunkIdx] = chunkCities
                            
                            val processFullPayload = Runnable {
                                val buffer = chunkBuffers.remove(alertId) ?: return@Runnable
                                val fullCities = mutableListOf<String>()
                                for (i in 1..totalChunks) { buffer[i]?.let { fullCities.addAll(it) } }
                                StatusManager.logFcmDiagnostic(this@MyFirebaseMessagingService, "Assembled ${buffer.size}/$totalChunks chunks internally")
                                StatusManager.processAlert(this@MyFirebaseMessagingService, alertId, type, fullCities, "[FCM-CHUNKS]", toneGenerator, remoteMessage.data.toString())
                            }

                            // If this is the FIRST chunk received for this alert, set a safety fallback timer
                            if (chunkBuffers[alertId]!!.size == 1) {
                                handler.postDelayed(processFullPayload, 2000) // Wait up to 2.0s for remaining chunks before processing what we have
                            }

                            val buffer = chunkBuffers[alertId]!!
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
