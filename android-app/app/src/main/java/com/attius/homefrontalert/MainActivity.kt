package com.attius.homefrontalert

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.attius.homefrontalert.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging
import androidx.cardview.widget.CardView

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private lateinit var tvDashStatus: TextView
    private lateinit var tvDashTimer: TextView
    private lateinit var tvLocationBadge: TextView
    private lateinit var tvLocationZone: TextView
    private lateinit var ivLocationStatus: ImageView
    private lateinit var vStatusDot: android.view.View
    private lateinit var ivShield: ImageView
    private lateinit var vStatusRing: android.view.View
    private lateinit var layoutDashboardContent: ConstraintLayout
    private lateinit var tvDashCountdown: TextView

    private lateinit var tvLastAlertZones: TextView
    private lateinit var tvLastAlertInfo: TextView
    private lateinit var cardLastAlert: androidx.cardview.widget.CardView
    private lateinit var sharedPrefs: android.content.SharedPreferences
    private lateinit var locationManager: AppLocationManager
    private lateinit var distanceCalculator: ZoneDistanceCalculator
    private lateinit var toneGenerator: DynamicToneGenerator


    private val uiHandler = Handler(Looper.getMainLooper())
    private var isResolvingLocation = false
    
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shield_active") {
            uiHandler.post { 
                refreshDashboardState()
                setVersionBadge()
                startPollingServiceIfEnabled()
            }
        }
    }

    private val uiUpdater = object : Runnable {
        override fun run() {
            refreshDashboardState()
            uiHandler.postDelayed(this, 1000)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isRtl = LocaleHelper.getLanguage(this) == "iw"
        window.decorView.layoutDirection = if (isRtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        locationManager = AppLocationManager.getInstance(this)
        distanceCalculator = ZoneDistanceCalculator(this)
        toneGenerator = DynamicToneGenerator(this)

        // Subscribe to FCM alerts topic — Pro only (primary delivery path)
        if (BuildConfig.IS_PAID) {
            FirebaseMessaging.getInstance().subscribeToTopic("alerts")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) android.util.Log.d("HomeFrontAlerts", "FCM: Subscribed to 'alerts' topic.")
                }
                
            // Start the periodic health check (Auto-Failover) using a simple background thread
            // to bypass the WorkManager PKIX certification download issues on this network.
            startNativeHealthCheckLoop()
        }

        performInitialSetupIfNeeded()

        if (!sharedPrefs.getBoolean("tos_accepted", false)) {
            startActivity(Intent(this, TOSActivity::class.java))
        }

        bindViews()
        startPollingServiceIfEnabled()

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnVolume).setOnClickListener {
            showVolumeDialog()
        }

        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(android.text.Html.fromHtml(getString(R.string.help_content)))
                .setPositiveButton("OK", null)
                .show()
        }

        refreshDashboardState()
        setVersionBadge()
    }

    private fun performInitialSetupIfNeeded() {
        if (!sharedPrefs.getBoolean("initial_setup_v135", false)) {
            sharedPrefs.edit().apply {
                putFloat("alert_volume", 0.5f) // As agreed
                putString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
                putString("fixed_zone_he", AppLocationManager.DEFAULT_ZONE_HE)
                putBoolean("shield_active", !BuildConfig.IS_PAID) // Pro: FCM (false) | Standard: Direct HFC (true)
                putBoolean("initial_setup_v135", true)
                putLong("dash_status_start_ms", System.currentTimeMillis())
                apply()
            }
        }
    }

    private fun bindViews() {
        tvDashStatus = findViewById(R.id.tvDashStatus)
        tvDashTimer = findViewById(R.id.tvDashTimer)
        tvLocationBadge = findViewById(R.id.tvLocationBadge)
        tvLocationZone = findViewById(R.id.tvLocationZone)
        ivLocationStatus = findViewById(R.id.ivLocationStatus)
        vStatusDot = findViewById(R.id.vStatusDot)
        ivShield = findViewById(R.id.ivShield)
        vStatusRing = findViewById(R.id.vStatusRing)
        layoutDashboardContent = findViewById(R.id.layoutDashboardContent)
        tvDashCountdown = findViewById(R.id.tvDashCountdown)
        
        tvLastAlertZones = findViewById(R.id.tvLastAlertZones)
        tvLastAlertInfo = findViewById(R.id.tvLastAlertInfo)
        cardLastAlert = findViewById(R.id.cardLastAlert)

        tvLocationBadge.setOnClickListener { showLocationExplanation() }
        tvLocationZone.setOnClickListener { showLocationExplanation() }
        ivLocationStatus.setOnClickListener { showLocationExplanation() }
    }

    override fun onResume() {
        super.onResume()
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        locationManager.startTracking()
        uiHandler.post(uiUpdater)
        
        // Proactive evaluation of heartbeat upon returning to app
        if (BuildConfig.IS_PAID && !sharedPrefs.getBoolean("shield_active", false)) {
            val lastFcmMs = sharedPrefs.getLong("last_fcm_heartbeat_ms", System.currentTimeMillis())
            if (System.currentTimeMillis() - lastFcmMs > 20 * 60 * 1000L) {
                android.util.Log.i("HomeFrontAlerts", "Heartbeat stale on resume. Eval immediately.")
                evaluateFailoverCondition()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        uiHandler.removeCallbacks(uiUpdater)
    }

    private fun refreshDashboardState() {
        val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
        val startTime = sharedPrefs.getLong("dash_status_start_ms", System.currentTimeMillis())
        
        if (status != "GREEN" && System.currentTimeMillis() - startTime > 1800000) {
            StatusManager.updateStatus(this, "GREEN")
            return
        }

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
        val unitS = getString(R.string.unit_seconds_short)
        val unitM = getString(R.string.unit_minutes_short)
        val unitH = getString(R.string.unit_hours_short)
        
        val dispTimer = when {
            elapsedSec < 60 -> "${elapsedSec}$unitS"
            elapsedSec < 3600 -> String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60)
            else -> String.format("%d$unitH %d$unitM", elapsedSec / 3600, (elapsedSec % 3600) / 60)
        }
        
        val timerPrefix = if (status == "GREEN") getString(R.string.monitoring_for) else getString(R.string.active_for)
        tvDashTimer.text = "$timerPrefix $dispTimer"

        // Centralized SSOT parsing
        val snapshot = StatusManager.getActiveThreatsSnapshot(this)
        val localRemaining = snapshot.localRemaining
        val alertsCount10m = snapshot.active10mCount
        val closestDist10m = snapshot.closestDist10m

        val tvRecentAlertsSummary = findViewById<TextView>(R.id.tvRecentAlertsSummary)
        if (alertsCount10m > 0) {
            tvRecentAlertsSummary.visibility = android.view.View.VISIBLE
            val distText = if (closestDist10m == Double.MAX_VALUE) getString(R.string.remote_alert) else String.format("%.1f km", closestDist10m)
            tvRecentAlertsSummary.text = getString(R.string.recent_alerts_summary, alertsCount10m, distText)
        } else {
            tvRecentAlertsSummary.visibility = android.view.View.GONE
        }
        
        val statusColor = when(status) {
            "RED" -> Color.parseColor("#FF3B30")
            "ORANGE" -> Color.parseColor("#FF9500")
            "YELLOW" -> Color.parseColor("#FFD60A")
            else -> Color.parseColor("#34C759")
        }

        updateDashboardBackground(statusColor)
        updateStatusTextAndIcons(status, statusColor)
        refreshLocationStatus()
        refreshLastAlertHistory()
        StatusWidgetProvider.updateAllWidgets(this)
    }

    private fun refreshLocationStatus() {
        if (isResolvingLocation) return
        isResolvingLocation = true
        
        // Run location resolution in a background thread
        kotlin.concurrent.thread(start = true) {
            try {
                val res = locationManager.resolveCurrentLocation()
                val localizedName = distanceCalculator.getLocalizedName(res.zoneNameHe)
                
                uiHandler.post {
                    tvLocationZone.text = localizedName
                    
                    if (res.source == "GPS") {
                        // Real-time Locked GPS
                        ivLocationStatus.setImageResource(R.drawable.ic_gps_antenna)
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#34C759"))
                        tvLocationBadge.visibility = android.view.View.GONE
                    } else if (res.activeMode == LocationTrackingMode.GPS_LIVE) {
                        // GPS Mode but using a fallback (Searching/Stale)
                        ivLocationStatus.setImageResource(R.drawable.ic_gps_antenna)
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#FFD60A"))
                        tvLocationBadge.visibility = android.view.View.VISIBLE
                        tvLocationBadge.text = getString(R.string.status_gps_searching_short) // Need to add this
                        tvLocationBadge.setTextColor(Color.parseColor("#FFD60A"))
                    } else {
                        // Fixed / Manual Mode
                        ivLocationStatus.setImageResource(R.drawable.ic_manual_location)
                        val color = if (res.source == "SAVED") Color.parseColor("#808080") else Color.parseColor("#424242")
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(color)
                        tvLocationBadge.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFrontAlerts", "Location thread failed", e)
            } finally {
                isResolvingLocation = false
            }
        }
    }

    private fun showLocationExplanation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || 
                          lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        kotlin.concurrent.thread(start = true) {
            val res = locationManager.resolveCurrentLocation()
            uiHandler.post {
                var title = when(res.source) {
                    "GPS" -> "Live GPS Active"
                    "SAVED" -> if (res.isFallback) "Using Saved Location" else "Fixed mode"
                    else -> "Default Location"
                }
                
                val localizedZone = distanceCalculator.getLocalizedName(res.zoneNameHe)
                var message = when(res.source) {
                    "GPS" -> "Your location is being updated in real-time.\n(Source: ${res.provider}, Accuracy: ${String.format("%.1fm", res.accuracy)})"
                    "SAVED" -> if (res.isFallback) "Unable to get a fresh GPS lock. Using last known location:\n${localizedZone} (Source: ${res.provider})" else "Using the manually selected zone."
                    else -> "No location set. Using Jerusalem as default."
                }

                if (!isGpsEnabled && locationManager.isUsingLiveGps()) {
                    title = "GPS is Disabled"
                    message = "System-level GPS is turned off. The app cannot track your location automatically.\n\nClick 'Fix' to turn it on."
                }

                val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                
                if (!isGpsEnabled && locationManager.isUsingLiveGps()) {
                    builder.setPositiveButton("Fix") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    builder.setNegativeButton("Close", null)
                } else {
                    builder.setPositiveButton(R.string.settings_title) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                    builder.setNegativeButton("OK", null)
                }
                builder.show()
            }
        }
    }

    private fun updateDashboardBackground(statusColor: Int) {
        val cardBg = Color.parseColor("#181818")
        val borderPx = (2 * resources.displayMetrics.density).toInt()
        val border = GradientDrawable().apply {
            setColor(cardBg)
            setStroke(borderPx, statusColor)
            cornerRadius = 24f * resources.displayMetrics.density
        }
        layoutDashboardContent.background = border

        val glowAlpha = if (statusColor == Color.parseColor("#34C759")) 20 else 50
        val glowColor = Color.argb(glowAlpha, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        vStatusRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(glowColor)
        }
    }

    private fun updateStatusTextAndIcons(status: String, statusColor: Int) {
        tvDashStatus.text = when(status) {
            "RED" -> getString(R.string.critical_status)
            "ORANGE" -> getString(R.string.warning_status)
            "YELLOW" -> getString(R.string.threat_status)
            else -> getString(R.string.no_alerts)
        }
        tvDashStatus.setTextColor(statusColor)
        vStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(statusColor)
        
        if (status == "GREEN") {
            ivShield.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.2f) })
            ivShield.alpha = 0.7f
        } else {
            ivShield.clearColorFilter()
            ivShield.alpha = 1.0f 
        }
    }


    private fun startPollingServiceIfEnabled() {
        val hasTos = sharedPrefs.getBoolean("tos_accepted", false)
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasTos && hasLocation && sharedPrefs.getBoolean("shield_active", false)) {
            val intent = Intent(this, LocalPollingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun setVersionBadge() {
        val tvVersionBadge = findViewById<TextView>(R.id.tvVersionBadge)
        val tvAlphaBadge = findViewById<TextView>(R.id.tvAlphaBadge)
        
        if (BuildConfig.IS_PAID) {
            tvVersionBadge.visibility = android.view.View.VISIBLE
            tvVersionBadge.text = "PRO"
        } else {
            tvVersionBadge.visibility = android.view.View.GONE
        }

        // Always show alpha badge for internal testing phase
        tvAlphaBadge.visibility = android.view.View.VISIBLE
        
        // Failover Badge logic completely removed.
    }

    private fun refreshLastAlertHistory() {
        val zones = sharedPrefs.getString("last_alert_zones", null)
        val dist = sharedPrefs.getFloat("last_alert_dist", -1f)
        val time = sharedPrefs.getLong("last_alert_time", 0L)
        val alertType = sharedPrefs.getString("last_alert_type", "") ?: ""
        
        if (zones != null && time > 0) {
            cardLastAlert.visibility = android.view.View.VISIBLE
            val rawZoneList = zones.split(", ")
            
            val top10CitiesHe = listOf("ירושלים", "תל אביב", "חיפה", "ראשון לציון", "פתח תקווה", "אשדוד", "נתניה", "בני ברק", "באר שבע", "חולון")
            val res = locationManager.resolveCurrentLocation()
            val sortedByDistance = rawZoneList.sortedBy { z -> distanceCalculator.getDistanceToZone(res.lat, res.lng, z) ?: Double.MAX_VALUE }
            
            val topCitiesInPayload = rawZoneList.filter { z -> top10CitiesHe.any { city -> z.contains(city) } }.toSet()
            val nearest5 = sortedByDistance.take(5).toSet()
            
            val prioritySet = mutableSetOf<String>().apply {
                addAll(topCitiesInPayload)
                addAll(nearest5)
            }
            
            val remainingSorted = sortedByDistance.filter { !prioritySet.contains(it) }
            val finalPriorityList = prioritySet.toMutableList()
            var fillIndex = 0
            while (finalPriorityList.size < 10 && fillIndex < remainingSorted.size) {
                finalPriorityList.add(remainingSorted[fillIndex])
                fillIndex++
            }
            
            val tail = remainingSorted.drop(fillIndex)
            val displayList = finalPriorityList.map { distanceCalculator.getLocalizedName(it, true) }.toMutableList()
            
            var collapsedTail = ""
            var expandedTail = ""
            
            if (tail.size <= 5) {
                tail.forEach { displayList.add(distanceCalculator.getLocalizedName(it, true)) }
            } else {
                val tailLoc = tail.map { distanceCalculator.getLocalizedName(it, true) }
                val moreText = if (LocaleHelper.getLanguage(this) == "iw" || LocaleHelper.getLanguage(this) == "he") "עוד" else "more"
                collapsedTail = "... (+${tail.size} $moreText)"
                expandedTail = "... ${tailLoc.joinToString(", ")}"
            }
            
            val collapsedText = if (collapsedTail.isEmpty()) displayList.joinToString("\n") else displayList.joinToString("\n") + "\n" + collapsedTail
            val expandedText = if (expandedTail.isEmpty()) displayList.joinToString("\n") else displayList.joinToString("\n") + "\n" + expandedTail
            
            tvLastAlertZones.text = collapsedText
            var isExpanded = false
            tvLastAlertZones.setOnClickListener {
                if (expandedTail.isNotEmpty()) {
                    isExpanded = !isExpanded
                    tvLastAlertZones.text = if (isExpanded) expandedText else collapsedText
                }
            }
            
            val diffMs = System.currentTimeMillis() - time
            val diffMin = diffMs / 60000
            val timeText = when {
                diffMin < 1 -> getString(R.string.just_now)
                diffMin < 60 -> getString(R.string.detected_ago, "${diffMin}${getString(R.string.unit_minutes_short)}")
                else -> android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(time))
            }
            val distText = if (dist < 0) getString(R.string.remote_alert) else getString(R.string.distance, String.format("%.1f", dist))
            
            val translatedType = LocaleHelper.translateAlertType(this, alertType)
            val prefix = if (translatedType.isNotEmpty()) "$translatedType • " else ""
            tvLastAlertInfo.text = "$prefix$timeText • $distText"
        } else {
            cardLastAlert.visibility = android.view.View.GONE
        }
    }

    private fun startNativeHealthCheckLoop() {
        kotlin.concurrent.thread(start = true) {
            while (true) {
                evaluateFailoverCondition()
                
                // Sleep for 2 minutes before evaluating the heartbeat timer again
                try {
                    Thread.sleep(2 * 60 * 1000L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun evaluateFailoverCondition() {
        try {
            // Only evaluate failover if we are in AUTO mode (0)
            if (sharedPrefs.getInt("connectivity_mode", 0) != 0) return
            
            val isCurrentlyInFailover = sharedPrefs.getBoolean("shield_active", false)
            if (isCurrentlyInFailover) return

            // The backend sends a KEEPALIVE FCM every 10 minutes.
            // We wait 20 minutes before assuming it's dead.
            val lastFcmMs = sharedPrefs.getLong("last_fcm_heartbeat_ms", System.currentTimeMillis())
            val elapsedMs = System.currentTimeMillis() - lastFcmMs
            val timeoutMs = 20L * 60L * 1000L // 20 minutes
            
            if (elapsedMs > timeoutMs) {
                // We haven't heard from FCM in 20+ minutes. Suspect outage.
                android.util.Log.w("HomeFrontAlerts", "FCM Heartbeat missing for ${elapsedMs/60000} mins. Suspect outage.")
                
                // Jitter: 0 to 5 minutes random sleep to prevent Thundering Herd DDoS on the backend
                val jitterMs = (0..300000).random().toLong()
                android.util.Log.w("HomeFrontAlerts", "Applying jitter of ${jitterMs/1000}s before checking health.")
                Thread.sleep(jitterMs)
                
                // Now do the ONE single HTTP ping to confirm it's actually dead
                var isHealthy = false
                try {
                    val url = java.net.URL("${BuildConfig.BACKEND_URL}/health")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("X-API-Key", BuildConfig.API_KEY)
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        isHealthy = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeFrontAlerts", "Fallback health check failed: ${e.message}")
                }

                if (isHealthy) {
                    android.util.Log.i("HomeFrontAlerts", "HealthCheck: Proxy is HEALTHY. (FCM might be delayed but backend is up).")
                    // We can reset the heartbeat to prevent immediately pinging again
                    sharedPrefs.edit().putLong("last_fcm_heartbeat_ms", System.currentTimeMillis()).apply()
                } else {
                    android.util.Log.w("HomeFrontAlerts", "HealthCheck: Proxy is DEAD. Triggering failover to Direct HFC Shield!")
                    sharedPrefs.edit().putBoolean("shield_active", true).apply()
                    // UI listener handles starting the service
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Failover eval error", e)
        }
    }


    private fun showVolumeDialog() {
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val seekVolume = android.widget.SeekBar(this).apply {
            max = 100
            progress = (sharedPrefs.getFloat("alert_volume", 1.0f) * 100).toInt()
        }
        val btnTest = android.widget.Button(this).apply {
            text = "TEST BEEP"
            textSize = 12f
            setOnClickListener {
                val vol = sharedPrefs.getFloat("alert_volume", 1.0f)
                toneGenerator.playTonesForDistances(listOf(5.0), vol)
            }
        }
        linearLayout.addView(seekVolume)
        linearLayout.addView(btnTest)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.volume_control_title)
            .setView(linearLayout)
            .setPositiveButton("OK", null)
            .create()

        seekVolume.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val vol = progress / 100f
                    sharedPrefs.edit().putFloat("alert_volume", vol).apply()
                    toneGenerator.updateLiveVolume(vol)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            var currentVol = sharedPrefs.getFloat("alert_volume", 1.0f)
            val isUp = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
            currentVol += if (isUp) 0.05f else -0.05f
            currentVol = kotlin.math.max(0.0f, kotlin.math.min(1.0f, currentVol))
            sharedPrefs.edit().putFloat("alert_volume", currentVol).apply()
            toneGenerator.updateLiveVolume(currentVol)
            
            // Do not consume the event, allow the system to adjust actual media volume as well
            return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

}
