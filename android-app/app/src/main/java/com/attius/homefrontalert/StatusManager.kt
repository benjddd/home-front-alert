package com.attius.homefrontalert

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Single Source of Truth for the application's alert status and current location.
 * Tracks active threats per-zone with a 30-minute stateful persistence.
 */
object StatusManager {
    private const val PREFS_NAME = "HomeFrontAlertsPrefs"
    private const val THREAT_TIMEOUT_MS = 30 * 60 * 1000L
    // Must match backend/config.js CLEARING_FADE_MS (15 minutes)
    private const val CLEARING_FADE_MS = 15 * 60 * 1000L
    private const val STATE_CLEARING = "CLEARING"
    const val ACTION_STATUS_CHANGED = "com.attius.homefrontalert.STATUS_CHANGED"
    const val ACTION_ZONE_CHANGED   = "com.attius.homefrontalert.ZONE_CHANGED"
    const val ACTION_MAP_REFRESH    = "com.attius.homefrontalert.MAP_REFRESH"
    const val EXTRA_ZONE_HE         = "zone_he"
    const val EXTRA_LAT             = "lat"
    const val EXTRA_LNG             = "lng"
    private val signaledCitiesPerAlert = mutableMapOf<String, MutableSet<String>>()
    private val globalSignaledCities = mutableMapOf<String, Long>()
    
    data class HandledAlert(
        val id: String,
        val type: AlertType,
        val cities: List<String>,
        val source: String
    )

    data class ActiveThreatsSnapshot(
        val active10mCount: Int,
        val closestDist10m: Double,
        val localRemaining: Long,
        val homeThreatObj: org.json.JSONObject?,
        val recentZones: List<Pair<String, Long>>
    )

    fun getActiveThreatsSnapshot(context: Context): ActiveThreatsSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
        
        var active10mCount = 0
        var closestDist10m = Double.MAX_VALUE
        var localRemaining = -1L
        var homeThreatObj: org.json.JSONObject? = null
        val recentZones = mutableListOf<Pair<String, Long>>()

        try {
            val threats = org.json.JSONObject(threatsStr)
            val res = AppLocationManager.getInstance(context).resolveCurrentLocation()
            val homeZone = normalizeCity(res.zoneNameHe)
            
            val iter = threats.keys()
            val tenMinsMs = 10 * 60 * 1000L
            val now = System.currentTimeMillis()
            
            while(iter.hasNext()) {
                val z = iter.next()
                val obj = threats.getJSONObject(z)
                val state = obj.optString("s", "URGENT")
                if (state == STATE_CLEARING) continue

                val t = obj.optLong("t", 0L)
                val isHome = normalizeCity(z) == homeZone
                
                if (isHome) {
                    homeThreatObj = obj
                    val duration = obj.optInt("c", 0)
                    if (t > 0 && duration > 0 && state == "URGENT") {
                        val rem = duration - (now - t) / 1000
                        if (rem > 0) localRemaining = rem
                    }
                }
                
                if (now - t <= tenMinsMs) {
                    active10mCount++
                    recentZones.add(Pair(obj.optString("name", z), t))
                }
            }
            
            if (recentZones.isNotEmpty()) {
                val calc = ZoneDistanceCalculator(context)
                val justNames = recentZones.map { it.first }
                val dists = calc.calculateDistancesToAlerts(res.lat, res.lng, justNames)
                if (dists.isNotEmpty()) closestDist10m = dists.min()
            }
        } catch(e: Exception) {}

        return ActiveThreatsSnapshot(active10mCount, closestDist10m, localRemaining, homeThreatObj, recentZones)
    }

    fun updateStatus(context: Context, newStatus: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldStatus = prefs.getString("dash_status", "GREEN")
        
        if (newStatus == oldStatus) return

        prefs.edit().apply {
            putString("dash_status", newStatus)
            putLong("dash_status_start_ms", System.currentTimeMillis())
            apply()
        }

        // Notify DashboardFragment immediately (works in both FCM and Direct modes)
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(ACTION_STATUS_CHANGED).putExtra("status", newStatus))

        syncUiComponents(context)
    }

    fun updateLocation(context: Context, zoneHe: String, lat: Double, lng: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val prevZone = prefs.getString("current_home_zone", null)

        prefs.edit().apply {
            putString("current_home_zone", zoneHe)
            putString("last_known_lat", lat.toString())
            putString("last_known_lng", lng.toString())
            putString("last_known_zone_he", zoneHe)
            putLong("last_location_update_ms", System.currentTimeMillis())
            apply()
        }

        // Re-evaluate threat status whenever zone changes — moving from a threatened zone
        // to a safe zone (or vice versa) must immediately update the dashboard.
        if (prevZone != zoneHe) {
            // Notify MapFragment so badge/dot update immediately without tab switch
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent(ACTION_ZONE_CHANGED)
                    .putExtra(EXTRA_ZONE_HE, zoneHe)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LNG, lng))
            recalculateStatus(context)  // calls updateStatus → syncUiComponents
        } else {
            syncUiComponents(context)
        }
    }


    fun normalizeCity(city: String): String {
        return city.replace(Regex("[^\\u0590-\\u05FF0-9]"), "")
    }

    /**
     * Re-calculates status based on active threat map.
     * Threats expire after 30 minutes unless cleared explicitly.
     */
    fun recalculateStatus(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
        var threats = org.json.JSONObject(threatsStr)
        val homeZone = normalizeCity(prefs.getString("current_home_zone", "") ?: "")
        
        val now = System.currentTimeMillis()
        
        val iter = threats.keys()
        while(iter.hasNext()) {
            val z = iter.next()
            val obj = threats.getJSONObject(z)
            val state = obj.optString("s", "URGENT")

            if (state == STATE_CLEARING) {
                val clearedAt = obj.optLong("ct", 0L)
                if (clearedAt <= 0L || now - clearedAt > CLEARING_FADE_MS) {
                    iter.remove()
                }
            } else {
                // Remove active threat if it's past the 30-min window
                if (now - obj.optLong("t", now) > THREAT_TIMEOUT_MS) {
                    iter.remove()
                }
            }
        }
        
        // Persist cleaned map only if changed
        val cleanedThreatsStr = threats.toString()
        val mapChanged = cleanedThreatsStr != threatsStr
        if (mapChanged) {
            prefs.edit().putString("active_threat_map", cleanedThreatsStr).apply()
        }
        
        // Simpler PRUNING: If registry gets too large, clear oldest entries to maintain memory safety
        if (signaledCitiesPerAlert.size > 100) {
           val oldestKey = signaledCitiesPerAlert.keys.firstOrNull()
           if (oldestKey != null) signaledCitiesPerAlert.remove(oldestKey)
        }
        
        var hasActiveThreats = false
        var newStatus = "GREEN"
        val iterKeys = threats.keys()
        while(iterKeys.hasNext()) {
            val zoneKey = iterKeys.next()
            val obj = threats.getJSONObject(zoneKey)
            val style = obj.optString("s", "URGENT")
            if (style == STATE_CLEARING) continue

            hasActiveThreats = true
            if (newStatus == "GREEN") newStatus = "YELLOW"

            // Use normalized check
            if (zoneKey == homeZone || normalizeCity(zoneKey) == homeZone) {
                if (style == "URGENT") {
                    newStatus = "RED"
                    break
                } else if (newStatus != "RED") {
                    newStatus = "ORANGE"
                }
            }
        }

        if (!hasActiveThreats) {
            newStatus = "GREEN"
        }

        val previousStatus = prefs.getString("dash_status", "GREEN") ?: "GREEN"
        updateStatus(context, newStatus)
        val statusChanged = previousStatus != newStatus

        return mapChanged || statusChanged
    }

    fun syncUiComponents(context: Context) {
        StatusWidgetProvider.updateAllWidgets(context)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("shield_active", false)) {
            val status = prefs.getString("dash_status", "GREEN") ?: "GREEN"
            val intent = android.content.Intent(context, LocalPollingService::class.java).apply {
                action = "UPDATE_NOTIFICATION"
                putExtra("status", status)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("HomeFrontAlerts", "UI Sync failed", e)
            }
        }
        
        updateActiveAlertNotification(context)
    }
    
    // Handler to flip RED state notifications from Countdown to Count-up
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flipRunnable: Runnable? = null

    private fun updateActiveAlertNotification(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val status = prefs.getString("dash_status", "GREEN") ?: "GREEN"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (status == "GREEN") {
            manager.cancel(LocalPollingService.ALERT_NOTIFICATION_ID)
            flipRunnable?.let { uiHandler.removeCallbacks(it) }
            YeelightController.triggerOff(context)
            return
        }

        val intent = android.content.Intent(context, MainActivity::class.java).apply { 
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK 
        }
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, 
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)

        val snapshot = getActiveThreatsSnapshot(context)
        val distStr = if (snapshot.closestDist10m != Double.MAX_VALUE) String.format("%.1f km", snapshot.closestDist10m) else "Remote"
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, LocalPollingService.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // Crucial: Only ding the very first time it pops or escalates
            .setUsesChronometer(true)
            .setAutoCancel(true)

        flipRunnable?.let { uiHandler.removeCallbacks(it) }

        when (status) {
            "YELLOW" -> {
                builder.setContentTitle("Remote Threat Active")
                builder.setContentText("Closest: $distStr | Total Active: ${snapshot.active10mCount}")
                builder.setColor(AlertColors.THREAT)
                builder.setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW) // Swipeable, silent in some OS
                builder.setWhen(prefs.getLong("dash_status_start_ms", System.currentTimeMillis()))
            }
            "ORANGE" -> {
                builder.setContentTitle("⚠️ Local Pre-Warning")
                builder.setContentText("Alerts are expected in a few minutes in your area.")
                builder.setColor(AlertColors.WARNING)
                builder.setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH) // Lock screen visible
                builder.setWhen(snapshot.homeThreatObj?.optLong("t", System.currentTimeMillis()) ?: System.currentTimeMillis())
            }
            "RED" -> {
                builder.setContentTitle("🚨 URGENT: SEEK SHELTER")
                builder.setColor(AlertColors.CRITICAL)
                builder.setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                builder.setOngoing(true) // Non-swipeable
                
                val t = snapshot.homeThreatObj?.optLong("t", System.currentTimeMillis()) ?: System.currentTimeMillis()
                val c = snapshot.homeThreatObj?.optInt("c", 0) ?: 0
                val endTime = t + (c * 1000L)
                val remSeconds = (endTime - System.currentTimeMillis()) / 1000L
                
                if (remSeconds > 0) {
                    // Stage 1: Countdown
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        builder.setChronometerCountDown(true)
                    }
                    builder.setWhen(endTime)
                    builder.setContentText("Local Alert! Time to shelter:")
                    builder.setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("Local Alert! Time to shelter.\nTotal Zones Affected: ${snapshot.active10mCount}"))
                    
                    // Schedule flip to count-up when countdown hits zero
                    flipRunnable = Runnable { updateActiveAlertNotification(context) }
                    uiHandler.postDelayed(flipRunnable!!, (remSeconds * 1000) + 500)
                } else {
                    // Stage 2: Count-up since alert
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        builder.setChronometerCountDown(false)
                    }
                    builder.setWhen(t)
                    builder.setContentText("Local Alert Active for:")
                    builder.setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("Local Alert Active for:\nTotal Zones Affected: ${snapshot.active10mCount}"))
                }
            }
        }
        
        manager.notify(LocalPollingService.ALERT_NOTIFICATION_ID, builder.build())
    }


    /**
     * Unified Polling Engine: Can be called by Service or Activity.
     * Processes alerts, updates history, and maintains the baseline.
     */
    fun runPollCycle(context: android.content.Context, toneGenerator: DynamicToneGenerator? = null) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        var hfcStatus = "Pending"
        var success = false

        // Direct HFC poll — the only polling path. FCM handles PRO primary delivery server-side.
        try {
            val hfcUrl = java.net.URL("https://www.oref.org.il/WarningMessages/alert/Alerts.json")
            val conn = hfcUrl.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)")
            conn.setRequestProperty("Referer", "https://www.oref.org.il/")

            val code = conn.responseCode
            hfcStatus = "HFC: $code"

            if (code == 200 || code == 204) {
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                success = handlePollResult(context, body, "[HFC]", hfcStatus, toneGenerator)
                if (success) {
                    val changed = recalculateStatus(context)
                    if (changed) {
                        LocalBroadcastManager.getInstance(context)
                            .sendBroadcast(Intent(ACTION_MAP_REFRESH))
                    }
                }
            }
        } catch (e: Exception) { hfcStatus = "HFC: Fail" }

        if (!success) {
            sharedPrefs.edit().putString("shield_last_log", "[$now] Shield Offline | $hfcStatus").apply()
        }
    }

    private fun handlePollResult(context: android.content.Context, body: String, sourceTag: String, _statusInfo: String, toneGenerator: DynamicToneGenerator?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val clean = body.trim()
        var isEffectivelyEmpty = clean.isEmpty() || clean == "null" || clean == "[]" || clean == "{}"
        
        // Deep check for Backend structured format: {"active": {}, ...}
        if (!isEffectivelyEmpty && clean.startsWith("{")) {
            try {
                val jsonObj = org.json.JSONObject(clean)
                val active = if (jsonObj.has("active")) jsonObj.get("active") else null
                
                // baseline if missing 'active' or if 'active' is an empty object/array
                val isEmptyActive = active == null || 
                                    (active is org.json.JSONObject && active.length() == 0) || 
                                    (active is org.json.JSONArray && active.length() == 0) ||
                                    active.toString() == "{}" || active.toString() == "[]"
                
                if (isEmptyActive) {
                    isEffectivelyEmpty = true
                }
            } catch (e: Exception) {}
        }
        
        if (isEffectivelyEmpty) {
            val baselineKey = if (sourceTag.contains("HFC")) "empty_sample_hfc" else "empty_sample_backend"
            prefs.edit().putString("shield_last_log", "[$nowTime] $sourceTag OK (Baseline)").apply()
            val baselineRep = if (clean.isEmpty()) "[EMPTY]" else if (clean.length > 50) clean.take(47) + "..." else clean
            prefs.edit().putString(baselineKey, baselineRep).apply()
            prefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
            return true
        }

        // --- Data Found ---
        prefs.edit().putLong("shield_last_success_ms", System.currentTimeMillis()).apply()
        
        try {
            val root = org.json.JSONObject(body)
            val jsonObject = if (root.has("active") && !root.isNull("active")) {
                val active = root.get("active")
                if (active is org.json.JSONObject) active 
                else if (active is org.json.JSONArray && active.length() > 0) active.getJSONObject(0) 
                else null
            } else if (root.has("cat")) root else null

            if (jsonObject != null) {
                val cat = jsonObject.optString("cat", "")
                val title = if (jsonObject.has("title")) jsonObject.getString("title") else jsonObject.optString("type", "")
                val citiesArray = jsonObject.optJSONArray("cities") ?: jsonObject.optJSONArray("data")
                val alertId = jsonObject.optString("id", System.currentTimeMillis().toString())

                if (citiesArray != null && citiesArray.length() > 0) {
                    val cities = mutableListOf<String>()
                    for (i in 0 until citiesArray.length()) cities.add(citiesArray.getString(i))
                    
                    val type = AlertStyleRegistry.getStyle(cat, title)
                    
                    // If it's an "All Clear" (CALM) event, it's effectively a baseline for telemetry purposes
                    if (type == AlertType.CALM) {
                        val baselineKey = if (sourceTag.contains("HFC")) "empty_sample_hfc" else "empty_sample_backend"
                        prefs.edit().putString("shield_last_log", "[$nowTime] $sourceTag OK (All-Clear)").apply()
                        prefs.edit().putString(baselineKey, "ALL-CLEAR @ $nowTime").apply()
                    }

                    processAlert(context, alertId, type, cities, sourceTag, toneGenerator, body)
                    
                    // Update log after processing
                    if (type != AlertType.CALM) {
                        prefs.edit().putString("shield_last_log", "[$nowTime] $sourceTag DATA!").apply()
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Poll result processing error: ${e.message}")
        }
        
        return true
    }

    /**
     * Clears all active threats — for use by FCM CLEAR handler.
     * Internally reads the threat map and routes through processAlert(CALM)
     * so the calm tone plays and zones enter the CLEARING fade state.
     */
    fun clearAll(context: Context, toneGenerator: DynamicToneGenerator?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
        val threats = org.json.JSONObject(threatsStr)
        val activeZones = mutableListOf<String>()
        val iter = threats.keys()
        while (iter.hasNext()) {
            val z = iter.next()
            val obj = threats.optJSONObject(z) ?: continue
            if (obj.optString("s") != STATE_CLEARING) {
                activeZones.add(obj.optString("name", z))
            }
        }
        if (activeZones.isNotEmpty()) {
            processAlert(context, "clear-${System.currentTimeMillis()}", AlertType.CALM, activeZones, "[FCM-CLEAR]", toneGenerator, null)
        } else {
            updateStatus(context, "GREEN")
        }
    }

    fun processAlert(context: Context, id: String, type: AlertType, cities: List<String>, source: String, toneGenerator: DynamicToneGenerator?, rawBody: String? = null) {
        // SILENT: unclassified HFC category — log only, no audio, no state change, no notification
        if (type == AlertType.SILENT) {
            Log.w("HomeFrontAlerts", "[$source] Unclassified alert type received — suppressing. Cities: ${cities.take(5)}")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        // 1. Centralized incremental signaling (dedup within IDs and global TTL)
        val signaledSet = signaledCitiesPerAlert.getOrPut(id) { mutableSetOf() }
        val ttlSeconds = prefs.getLong("alert_ttl_seconds", 180L)
        val ttlMs = ttlSeconds * 1000L
        val nowMs = System.currentTimeMillis()
        val forceAudioZones = mutableSetOf<String>()
        
        // 2. Update Raw History Log (Diagnostics SSOT)
        val history = prefs.getString("raw_alert_history", "") ?: ""
        val displayBody = rawBody?.trim()?.take(250)?.replace("\n", " ") ?: "$type @ ${cities.take(3).joinToString(", ")}"
        val entry = "[$nowTime] $source: $displayBody"
        
        if (!history.contains(id.take(10))) {
            val lines = history.split("\n").filter { it.isNotEmpty() }.toMutableList()
            lines.add(0, entry)
            prefs.edit().putString("raw_alert_history", lines.take(10).joinToString("\n")).apply()
        }

        // 3. Update SSOT threat map
        val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
        val threats = org.json.JSONObject(threatsStr)
        val calculator = ZoneDistanceCalculator(context)

        cities.forEach { zone ->
            val normZone = normalizeCity(zone)
            if (type == AlertType.CALM) {
                // Explicit CALM transitions matching active zones into CLEARING (fade phase).
                val existing = threats.optJSONObject(normZone)
                if (existing != null) {
                    existing.put("s", STATE_CLEARING)
                    existing.put("ct", nowMs)
                    threats.put(normZone, existing)
                }
                // Cleanup legacy raw-keyed entries if they exist.
                threats.remove(zone)
            } else {
                val existing = threats.optJSONObject(normZone)
                val existingState = existing?.optString("s", "CAUTION") ?: ""
                val existingSeverity = when (existingState) {
                    "URGENT" -> 1
                    "CAUTION" -> 0
                    else -> -1
                }
                val incomingSeverity = if (type == AlertType.URGENT) 1 else 0
                val isEscalation = incomingSeverity > existingSeverity
                val isReactivation = existingState == STATE_CLEARING

                if (isEscalation || isReactivation) {
                    forceAudioZones.add(normZone)
                }

                val obj = org.json.JSONObject()
                obj.put("t", nowMs)
                obj.put("c", calculator.getZoneCountdown(zone))
                obj.put("name", zone) // Store raw name for display if needed

                if (existing != null && !isReactivation && incomingSeverity <= existingSeverity) {
                    // Preserve one-way severity (never downgrade).
                    obj.put("s", existingState)
                    val prevClearedAt = existing.optLong("ct", 0L)
                    if (prevClearedAt > 0L) obj.put("ct", prevClearedAt)
                } else {
                    // New threat / escalation / reactivation.
                    obj.put("s", type.name)
                    obj.put("ct", org.json.JSONObject.NULL)
                }
                threats.put(normZone, obj)
            }
        }
        prefs.edit().putString("active_threat_map", threats.toString()).apply()

        val newCitiesForAudio = cities.filter { city ->
            val norm = normalizeCity(city)
            val shouldForceAudio = forceAudioZones.contains(norm)
            if (shouldForceAudio) return@filter true

            val isNewInThisAlert = !signaledSet.contains(norm)
            if (!isNewInThisAlert) return@filter false

            val globalKey = "$norm:${type.name}"
            val lastNotified = globalSignaledCities[globalKey] ?: 0L
            val isGlobalCooledDown = (nowMs - lastNotified) > ttlMs

            // Default dedup path: new in this alert ID + global cooldown passed.
            isGlobalCooledDown
        }

        Log.i("HomeFrontAlerts", "🚨 PROCESSING: $id | $type | Total: ${cities.size} | New: ${newCitiesForAudio.size} | Source: $source")

        // 4. Calculate Audio/UI distance metrics
        val locationManager = AppLocationManager.getInstance(context)
        val loc = locationManager.resolveCurrentLocation()
        val userZone = normalizeCity(loc.zoneNameHe)
        
        // For Audio: Only consider the DELTA (new cities)
        val newNormalized = newCitiesForAudio.map { normalizeCity(it) }
        val isLocalInDelta = userZone.isNotEmpty() && newNormalized.contains(userZone)
        val distancesForAudio = calculator.calculateDistancesToAlerts(loc.lat, loc.lng, newCitiesForAudio)

        // For UI: Use the full set for accurate distance display on dashboard
        val allNormalized = cities.map { normalizeCity(it) }
        val distancesTotal = calculator.calculateDistancesToAlerts(loc.lat, loc.lng, cities)
        val minDistance = if (distancesTotal.isNotEmpty()) distancesTotal.min() else -1.0

        // 5. Update UI Metadata (ONLY for real threats)
        if (type != AlertType.CALM) {
            var alertTypeStr = ""
            try {
                if (rawBody != null) {
                    val root = org.json.JSONObject(rawBody)
                    val jsonObject = if (root.has("active") && !root.isNull("active")) {
                        val active = root.get("active")
                        if (active is org.json.JSONObject) active 
                        else if (active is org.json.JSONArray && active.length() > 0) active.getJSONObject(0) 
                        else root
                    } else root
                    
                    alertTypeStr = if (jsonObject.has("title")) jsonObject.getString("title") else jsonObject.optString("cat", "")
                }
            } catch(e: Exception) {}

            prefs.edit()
                .putString("last_alert_zones", cities.joinToString(", "))
                .putFloat("last_alert_dist", minDistance.toFloat())
                .putLong("last_alert_time", System.currentTimeMillis())
                .putString("last_alert_type", alertTypeStr)
                .apply()
        }

        // 6. Trigger Audio (Only if Delta exists or All-Clear)
        if (toneGenerator != null) {
            val volume = prefs.getFloat("alert_volume", 1.0f)
            
            if (type == AlertType.CALM) {
                // All-clear is handled separately (mapped to local zone check)
                val isLocalTotal = userZone.isNotEmpty() && allNormalized.contains(userZone)
                if (isLocalTotal) {
                    val norm = normalizeCity(userZone)
                    val globalKey = "$norm:${type.name}"
                    val lastNotified = globalSignaledCities[globalKey] ?: 0L
                    
                    // Reduced 60-second TTL special window for All-Clear events 
                    // (Prevents jitter/echoes without locking out the next real resolution)
                    val isCooledDown = (nowMs - lastNotified) > 60 * 1000L
                    
                    if (isCooledDown) {
                        Log.i("HomeFrontAlerts", "🔊 AUDIO: $id | All-Clear sound triggered for $norm")
                        toneGenerator.playTonesForDistances(emptyList(), volume, type, true)
                        YeelightController.triggerAlert(context, type, true)
                        globalSignaledCities[globalKey] = nowMs
                    } else {
                        Log.d("HomeFrontAlerts", "🔊 AUDIO: $id | All-Clear suppressed (within 60s cooldown)")
                    }
                }
            } else if (newCitiesForAudio.isNotEmpty() || type == AlertType.CAUTION) {
                val homeZone = normalizeCity(userZone)
                val alreadyLocal = signaledSet.contains(homeZone)
                
                if (alreadyLocal) {
                    Log.i("HomeFrontAlerts", "🔊 AUDIO: $id | Suppressing chunk audio, local siren already triggered.")
                    newCitiesForAudio.forEach { signaledSet.add(normalizeCity(it)) }
                } else if (isLocalInDelta) {
                    Log.i("HomeFrontAlerts", "🔊 AUDIO: $id | Escalating to LOCAL siren!")
                    newCitiesForAudio.forEach { signaledSet.add(normalizeCity(it)) }
                    toneGenerator.playTonesForDistances(distancesForAudio, volume, type, true)
                    YeelightController.triggerAlert(context, type, true)
                } else {
                    val audioDistances = if (type == AlertType.CAUTION && newCitiesForAudio.isEmpty()) distancesTotal else distancesForAudio
                    Log.i("HomeFrontAlerts", "🔊 AUDIO: $id | Type: $type | New: ${newCitiesForAudio.size} | Local: $isLocalInDelta")
                    if (audioDistances.isNotEmpty()) {
                        newCitiesForAudio.forEach { 
                            val norm = normalizeCity(it)
                            signaledSet.add(norm)
                            globalSignaledCities["$norm:${type.name}"] = nowMs
                        }
                        toneGenerator.playTonesForDistances(audioDistances, volume, type, false)
                        YeelightController.triggerAlert(context, type, false)
                    }
                }
            }
        }

        // 6b. Periodically clean up global TTL map (older than 1 hour is safe to prune)
        if (globalSignaledCities.size > 200) {
            val pruneTime = nowMs - (60 * 60 * 1000L)
            globalSignaledCities.entries.removeIf { it.value < pruneTime }
        }

        // 7. Refresh SSOT status
        recalculateStatus(context)

        // 8. Refresh map in both FCM and direct-HFC paths
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(ACTION_MAP_REFRESH))
    }

    fun logFcmDiagnostic(context: Context, rawData: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val history = prefs.getString("fcm_diagnostic_log", "") ?: ""
        val entry = "[$nowTime] $rawData"
        
        val lines = history.split("\n").filter { it.isNotEmpty() }.toMutableList()
        lines.add(0, entry)
        
        prefs.edit().putString("fcm_diagnostic_log", lines.take(10).joinToString("\n")).apply()
    }
}
