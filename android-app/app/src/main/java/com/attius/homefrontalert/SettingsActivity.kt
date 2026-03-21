package com.attius.homefrontalert

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Color
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private lateinit var locationManager: AppLocationManager
    private lateinit var distanceCalculator: ZoneDistanceCalculator
    private var isResolvingLocation = false
    private lateinit var sharedPrefs: android.content.SharedPreferences
    private lateinit var toneGenerator: DynamicToneGenerator
    private lateinit var tvConnectivityDetail: TextView

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shield_active" || key == "connectivity_mode") {
            Handler(Looper.getMainLooper()).post {
                updateSourceRadios()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isRtl = LocaleHelper.getLanguage(this) == "iw"
        window.decorView.layoutDirection = if (isRtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR

        setContentView(R.layout.activity_settings)

        locationManager = AppLocationManager.getInstance(this)
        distanceCalculator = ZoneDistanceCalculator(this)
        toneGenerator = DynamicToneGenerator(this)
        sharedPrefs = getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        tvConnectivityDetail = findViewById(R.id.tvConnectivityDetail)

        requestCriticalPermissions()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // 1. Location Tracking Mode
        val switchLiveGps = findViewById<Switch>(R.id.switchLiveGps)
        switchLiveGps.isChecked = locationManager.isUsingLiveGps()
        switchLiveGps.setOnCheckedChangeListener { _, isChecked ->
            locationManager.setUsingLiveGps(isChecked)
            StatusManager.syncUiComponents(this) // Force Dashboard to see the mode change
            refreshSettingsUI()
            val msg = if (isChecked) getString(R.string.mode_gps) else getString(R.string.mode_fixed)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. Zone Search (Saved Zone)
        val autoSearch = findViewById<AutoCompleteTextView>(R.id.autoCompleteZoneSearch)
        val allZones = distanceCalculator.getAllZones()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allZones)
        autoSearch.setAdapter(adapter)

        autoSearch.setOnItemClickListener { parent, _, position, _ ->
            val selection = parent.getItemAtPosition(position) as String
            val zoneHe = distanceCalculator.getZoneCoordinates(selection)?.nameHe ?: selection
            locationManager.setSavedZoneHe(zoneHe)
            
            // If user selects a zone, we assume they want to use the FIXED mode
            locationManager.setUsingLiveGps(false)
            switchLiveGps.isChecked = false
            
            Toast.makeText(this, "${getString(R.string.set_home_zone)}: $selection", Toast.LENGTH_SHORT).show()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(autoSearch.windowToken, 0)
        }

        refreshSettingsUI() // Initial state



        // 4. Connectivity Shield Toggle
        val switchShieldActive = findViewById<Switch>(R.id.switchHybridMode)
        switchShieldActive.isChecked = sharedPrefs.getBoolean("shield_active", false)
        switchShieldActive.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasLocation) {
                    Toast.makeText(this, "Location permission required for Direct Shield", Toast.LENGTH_LONG).show()
                    switchShieldActive.isChecked = false
                    requestCriticalPermissions()
                    return@setOnCheckedChangeListener
                }
                
                sharedPrefs.edit().putBoolean("shield_active", true).apply()
                val intent = Intent(this, LocalPollingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                sharedPrefs.edit().putBoolean("shield_active", false).apply()
                stopService(Intent(this, LocalPollingService::class.java))
            }
        }

        // 5. Diagnostics
        val btnCheckConnection = findViewById<Button>(R.id.btnCheckConnection)
        val tvBackendStatus = findViewById<TextView>(R.id.tvBackendStatus)
        btnCheckConnection.setOnClickListener {
            tvBackendStatus.text = "Checking integrity..."
            thread {
                val result = runUnifiedCheck()
                Handler(Looper.getMainLooper()).post {
                    tvBackendStatus.text = result
                    tvBackendStatus.setTextColor(android.graphics.Color.LTGRAY)
                }
            }
        }

        // Version footer — always matches APK filename
        val flavor = if (BuildConfig.IS_PAID) "pro" else "standard"
        findViewById<TextView>(R.id.tvAppVersion).text = "v${BuildConfig.VERSION_NAME}-$flavor"

        // 6. Advanced Section
        val switchShowAdvanced = findViewById<Switch>(R.id.switchShowAdvanced)
        val layoutAdvancedSection = findViewById<LinearLayout>(R.id.layoutAdvancedSection)
        val isAdvancedVisible = sharedPrefs.getBoolean("show_advanced_settings", false)
        switchShowAdvanced.isChecked = isAdvancedVisible
        layoutAdvancedSection.visibility = if (isAdvancedVisible) android.view.View.VISIBLE else android.view.View.GONE
        switchShowAdvanced.setOnCheckedChangeListener { _, isChecked ->
            layoutAdvancedSection.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            sharedPrefs.edit().putBoolean("show_advanced_settings", isChecked).apply()
        }

        // 6b. Alert Source Selector (PRO only)
        val cardAlertSource = findViewById<androidx.cardview.widget.CardView>(R.id.cardAlertSource)
        val rgConnectivityMode = findViewById<RadioGroup>(R.id.rgConnectivityMode)

        if (BuildConfig.IS_PAID) {
            cardAlertSource.visibility = android.view.View.VISIBLE
            updateSourceRadios()
            rgConnectivityMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = when(checkedId) {
                    R.id.rbModeAuto -> 0
                    R.id.rbModeDirect -> 1
                    R.id.rbModeCloud -> 2
                    else -> 0
                }
                sharedPrefs.edit().putInt("connectivity_mode", mode).apply()
                
                // Immediate Service Action based on selection
                when(mode) {
                    0 -> { // AUTO: Leave it as-is, let background eval handle it
                        Toast.makeText(this, "Intelligent Mode Active", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // DIRECT: Force it ON
                        sharedPrefs.edit().putBoolean("shield_active", true).apply()
                        val intent = Intent(this, LocalPollingService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        Toast.makeText(this, "Maximum Reliability Active", Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // CLOUD: Force it OFF
                        sharedPrefs.edit().putBoolean("shield_active", false).apply()
                        stopService(Intent(this, LocalPollingService::class.java))
                        Toast.makeText(this, "Battery Optimized Active", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Hide old shield toggle for Pro (cardAlertSource handles mode selection)
        val cardShield = findViewById<androidx.cardview.widget.CardView>(R.id.cardShield)
        if (BuildConfig.IS_PAID) cardShield.visibility = android.view.View.GONE



        // 7. Dynamic UI Refresh (Zone Status)
        val tvShieldLog = findViewById<TextView>(R.id.tvHybridLog)
        val tvRawHistory = findViewById<TextView>(R.id.tvRawHistory)
        val tvEmptySample = findViewById<TextView>(R.id.tvEmptySample)
        val tvFcmHistory = findViewById<TextView>(R.id.tvFcmHistory)
        val tvResolvedZone = findViewById<TextView>(R.id.tvResolvedZone)
        val tvLastSync = findViewById<TextView>(R.id.tvLastSync)
        
        val logHandler = Handler(Looper.getMainLooper())
        val logUpdater = object : Runnable {
            override fun run() {
                tvShieldLog?.text = sharedPrefs.getString("shield_last_log", "...")
                tvRawHistory?.text = sharedPrefs.getString("raw_alert_history", "...")
                val bHfc = sharedPrefs.getString("empty_sample_hfc", "Waiting...")
                val bBack = sharedPrefs.getString("empty_sample_backend", "Waiting...")
                tvEmptySample?.text = "HFC: $bHfc\nProxy: $bBack"
                tvFcmHistory?.text = sharedPrefs.getString("fcm_diagnostic_log", "Waiting for FCM...")
                
                val lastSuccess = sharedPrefs.getLong("shield_last_success_ms", 0)
                if (lastSuccess > 0) {
                    val diff = (System.currentTimeMillis() - lastSuccess) / 1000
                    tvLastSync?.text = "Last Success: ${diff}s ago"
                } else {
                    tvLastSync?.text = "Last Success: Never"
                }

                // Run location resolution in a background thread
                if (isResolvingLocation) return
                isResolvingLocation = true
                
                kotlin.concurrent.thread(start = true) {
                    try {
                        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                        val isGpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                        val locationEnabled = isGpsEnabled
    
                        val res = locationManager.resolveCurrentLocation()
                        val localizedZone = distanceCalculator.getLocalizedName(res.zoneNameHe)
                        
                        val hasLocationPerm = ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        
                        val statusText = when(res.source) {
                            "GPS" -> getString(R.string.status_gps_locked, localizedZone)
                            "SAVED" -> {
                                if (res.isFallback && res.activeMode == LocationTrackingMode.GPS_LIVE) 
                                    getString(R.string.status_gps_searching, localizedZone) 
                                else 
                                    getString(R.string.status_saved_zone, localizedZone)
                            }
                            else -> getString(R.string.status_default_zone, localizedZone)
                        }
    
                        Handler(Looper.getMainLooper()).post {
                            // SSOT Safety check: Ensure the mode hasn't changed while we were calculating
                            val currentMode = locationManager.getTrackingMode()
                            var finalRes = res
                            if (currentMode == LocationTrackingMode.FIXED_ZONE && res.activeMode == LocationTrackingMode.GPS_LIVE) {
                                // Background thread was stale, re-resolve for Fixed mode (fast)
                                finalRes = locationManager.resolveCurrentLocation()
                            }

                            val finalLocalizedZone = distanceCalculator.getLocalizedName(finalRes.zoneNameHe)
                            val finalStatusText = when(finalRes.source) {
                                "GPS" -> getString(R.string.status_gps_locked, finalLocalizedZone)
                                "SAVED" -> {
                                    if (finalRes.isFallback && finalRes.activeMode == LocationTrackingMode.GPS_LIVE) 
                                        getString(R.string.status_gps_searching, finalLocalizedZone) 
                                    else 
                                        getString(R.string.status_saved_zone, finalLocalizedZone)
                                }
                                else -> {
                                    if (finalRes.activeMode == LocationTrackingMode.GPS_LIVE)
                                        getString(R.string.status_gps_searching, finalLocalizedZone)
                                    else
                                        getString(R.string.status_default_zone, finalLocalizedZone)
                                }
                            }

                            var displayString = "$finalStatusText\n(Lat: ${String.format("%.4f", finalRes.lat)}, Lng: ${String.format("%.4f", finalRes.lng)})"
                            
                            if (finalRes.activeMode == LocationTrackingMode.GPS_LIVE) {
                                val sats = "Sats: ${AppLocationManager.satellitesUsed}/${AppLocationManager.satellitesInView}"
                                val snr = "SNR: ${String.format("%.0f", AppLocationManager.avgSnr)}"
                                val strategy = AppLocationManager.currentStrategy
                                displayString += "\n($sats | $snr | $strategy)"
                            }

                            if (finalRes.source == "GPS") {
                                displayString += "\n(${finalRes.provider} | Acc: ${String.format("%.0fm", finalRes.accuracy)})"
                            }
                            var textColor = if (finalRes.isFallback) android.graphics.Color.parseColor("#FFD60A") else android.graphics.Color.parseColor("#34C759")
    
                            if (!hasLocationPerm) {
                                displayString = "⚠️ Location Permission Denied\n(Click to fix in Settings)"
                                textColor = android.graphics.Color.parseColor("#FF5252") // Reddish
                                tvResolvedZone?.setOnClickListener { openAppSettings() }
                            } else if (!locationEnabled && currentMode == LocationTrackingMode.GPS_LIVE) {
                                displayString = "⚠️ GPS is OFF in System Settings\n(Click to fix)"
                                textColor = android.graphics.Color.RED
                                tvResolvedZone?.setOnClickListener { openLocationSettings() }
                            } else {
                                tvResolvedZone?.setOnClickListener(null)
                            }
                            
                            tvResolvedZone?.text = displayString
                            tvResolvedZone?.setTextColor(textColor)
                            refreshSettingsUI() // Keep UI synced
                        }
                    } finally {
                        isResolvingLocation = false
                    }
                }
                
                logHandler.postDelayed(this, 1500)
            }
        }
        logHandler.post(logUpdater)

        setupSoundTestSection()
        setupLanguageSwitch()
    }

    private fun runUnifiedCheck(): String {
        val rootUrl = BuildConfig.BACKEND_URL
        var backendResult = "Proxy: Checking..."
        var hfcResult = "Direct HFC: Checking..."
        var lastAlertProof = ""
        
        try {
            val url = URL("$rootUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("X-API-Key", BuildConfig.API_KEY)
            conn.setRequestProperty("User-Agent", "TzevaArtzi/${BuildConfig.VERSION_NAME}")
            conn.connectTimeout = 5000
            backendResult = if (conn.responseCode == 200) "🟢 Proxy: Online" else "🔴 Proxy: Error ${conn.responseCode}"
        } catch (e: Exception) { backendResult = "🔴 Proxy: Offline" }

        try {
            val url = URL("https://www.oref.org.il/WarningMessages/alert/Alerts.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)")
            conn.setRequestProperty("Referer", "https://www.oref.org.il/")
            conn.connectTimeout = 5000
            hfcResult = if (conn.responseCode == 200 || conn.responseCode == 204) "🟢 Direct HFC: OK" else "🟡 Direct HFC: Blocked"
        } catch (e: Exception) { hfcResult = "🔴 Direct HFC: Unavailable" }

        // Proof of Backend History Capability
        try {
            val url = URL("$rootUrl/alerts/history")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("X-API-Key", BuildConfig.API_KEY)
            conn.setRequestProperty("User-Agent", "TzevaArtzi/${BuildConfig.VERSION_NAME}")
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val last = arr.getJSONObject(0)
                    val title = if (last.has("type")) last.getString("type") else last.optString("title", "Unknown")
                    val time = if (last.has("serverTime")) last.getString("serverTime") else last.optString("time", "...")
                    lastAlertProof = "\n✨ Last Proof: $title @ $time"
                }
            }
        } catch (e: Exception) {}

        return "$backendResult\n$hfcResult$lastAlertProof"
    }

    private fun setupSoundTestSection() {
        fun vol() = sharedPrefs.getFloat("alert_volume", 0.8f)

        // CALM — Two-note resolve
        findViewById<android.widget.Button>(R.id.btnTestCalm).setOnClickListener {
            toneGenerator.playTonesForDistances(emptyList(), vol(), AlertType.CALM)
        }
        // CAUTION — Wobble Remote (isLocal = false)
        findViewById<android.widget.Button>(R.id.btnTestCautionRemote).setOnClickListener {
            toneGenerator.playTonesForDistances(emptyList(), vol(), AlertType.CAUTION, isLocal = false)
        }
        // CAUTION — Wobble Local (isLocal = true)
        findViewById<android.widget.Button>(R.id.btnTestCautionLocal).setOnClickListener {
            toneGenerator.playTonesForDistances(emptyList(), vol(), AlertType.CAUTION, isLocal = true)
        }
        // URGENT — Whisper · Small (4 zones, Ashkelon corridor)
        val urgentSmall = listOf(6.0, 14.0, 22.0, 28.0)
        findViewById<android.widget.Button>(R.id.btnTestUrgentSmall).setOnClickListener {
            toneGenerator.playTonesForDistances(urgentSmall, vol(), AlertType.URGENT)
        }
        // URGENT — Whisper · Medium (100 zones — tests high density)
        val urgentMed = (1..100).map { it * 4.0 }
        findViewById<android.widget.Button>(R.id.btnTestUrgentMed).setOnClickListener {
            toneGenerator.playTonesForDistances(urgentMed, vol(), AlertType.URGENT)
        }
        // URGENT — Whisper · Large (600 zones — tests 1s shimmer texture)
        val urgentLarge = (1..600).map { it * (5.0 / 600) + it * 0.1 } // Mix of distance ranges 
        findViewById<android.widget.Button>(R.id.btnTestUrgentLarge).setOnClickListener {
            toneGenerator.playTonesForDistances(urgentLarge, vol(), AlertType.URGENT)
        }
    }

    private fun setupLanguageSwitch() {
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguageSettings)
        val rbHebrew = findViewById<RadioButton>(R.id.rbHebrewSettings)
        val rbEnglish = findViewById<RadioButton>(R.id.rbEnglishSettings)
        val currentLang = LocaleHelper.getLanguage(this)
        if (currentLang == "iw" || currentLang == "he") rbHebrew.isChecked = true else rbEnglish.isChecked = true

        rgLanguage.setOnCheckedChangeListener { group, checkedId ->
            val selectedLang = if (checkedId == R.id.rbHebrewSettings) "iw" else "en"
            if (selectedLang != LocaleHelper.getLanguage(this)) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.lang_change_title)
                    .setMessage(R.string.lang_change_message)
                    .setPositiveButton(R.string.lang_change_confirm) { _, _ ->
                        LocaleHelper.setLocale(this, selectedLang)
                        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
                        startActivity(intent)
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> setupLanguageSwitch() }
                    .show()
            }
        }
    }

    private fun requestCriticalPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = android.net.Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun openLocationSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    override fun onResume() {
        super.onResume()
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        locationManager.startTracking()
    }

    override fun onPause() {
        super.onPause()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        locationManager.stopTracking()
    }

    private fun updateSourceRadios() {
        if (!BuildConfig.IS_PAID) return
        val rgConnectivityMode = findViewById<RadioGroup>(R.id.rgConnectivityMode)
        val rbModeAuto = findViewById<RadioButton>(R.id.rbModeAuto)
        val rbModeDirect = findViewById<RadioButton>(R.id.rbModeDirect)
        val rbModeCloud = findViewById<RadioButton>(R.id.rbModeCloud)
        
        val mode = sharedPrefs.getInt("connectivity_mode", 0)
        when(mode) {
            0 -> rbModeAuto.isChecked = true
            1 -> rbModeDirect.isChecked = true
            2 -> rbModeCloud.isChecked = true
        }

        val isShieldActive = sharedPrefs.getBoolean("shield_active", false)
        tvConnectivityDetail.text = when(mode) {
            0 -> { // AUTO
                if (isShieldActive) getString(R.string.status_intelligent_direct)
                else getString(R.string.status_intelligent_cloud)
            }
            1 -> getString(R.string.status_reliability_direct)
            2 -> getString(R.string.status_battery_cloud)
            else -> ""
        }
        val color = if (isShieldActive) Color.parseColor("#FFD60A") else Color.parseColor("#606060")
        tvConnectivityDetail.setTextColor(color)
    }

    private fun refreshSettingsUI() {
        val isGpsActive = locationManager.isUsingLiveGps()
        val autoSearch = findViewById<AutoCompleteTextView>(R.id.autoCompleteZoneSearch)
        val cardManual = findViewById<androidx.cardview.widget.CardView>(R.id.cardManualZone)
        val ivManual = findViewById<ImageView>(R.id.ivManualIcon)
        
        if (isGpsActive) {
            autoSearch.isEnabled = false
            autoSearch.alpha = 0.4f
            cardManual.alpha = 0.4f
            ivManual.alpha = 0.3f
        } else {
            autoSearch.isEnabled = true
            autoSearch.alpha = 1.0f
            cardManual.alpha = 1.0f
            ivManual.alpha = 1.0f
        }
    }
}
