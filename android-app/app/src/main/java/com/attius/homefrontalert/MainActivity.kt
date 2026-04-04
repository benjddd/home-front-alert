package com.attius.homefrontalert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private lateinit var sharedPrefs: android.content.SharedPreferences
    lateinit var locationManager: AppLocationManager
    lateinit var distanceCalculator: ZoneDistanceCalculator
    lateinit var toneGenerator: DynamicToneGenerator

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val uiHandler = Handler(Looper.getMainLooper())

    private var nativeHealthCheckThread: Thread? = null
    
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shield_active") {
            uiHandler.post { 
                setVersionBadge()
                startPollingServiceIfEnabled()
            }
        }
    }
    private val stateMaintenanceRunnable = object : Runnable {
        override fun run() {
            StatusManager.maintainState(this@MainActivity)
            uiHandler.postDelayed(this, StatusManager.STATE_MAINTENANCE_INTERVAL_MS)
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

        if (BuildConfig.IS_PAID) {
            FirebaseMessaging.getInstance().subscribeToTopic("alerts")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) android.util.Log.d("HomeFrontAlerts", "FCM: Subscribed to 'alerts' topic.")
                }
            startNativeHealthCheckLoop()
        }

        performInitialSetupIfNeeded()

        if (!sharedPrefs.getBoolean("tos_accepted", false)) {
            startActivity(Intent(this, TOSActivity::class.java))
        }

        setupViewPager()
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
                .setNeutralButton("Credits / Thanks") { _, _ -> startActivity(Intent(this, ThanksActivity::class.java)) }
                .show()
        }

        setVersionBadge()
    }
    
    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) DashboardFragment() else MapFragment()
            }
        }
        viewPager.adapter = adapter
        
        val mapTitle = if (LocaleHelper.getLanguage(this) == "iw") "מפה" else "Map"
        val dashTitle = if (LocaleHelper.getLanguage(this) == "iw") "התרעות" else "Alerts"

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) dashTitle else mapTitle
        }.attach()
    }

    private fun performInitialSetupIfNeeded() {
        if (!sharedPrefs.getBoolean("initial_setup_v135", false)) {
            sharedPrefs.edit().apply {
                putFloat("alert_volume", 0.5f) 
                putString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
                putString("fixed_zone_he", AppLocationManager.DEFAULT_ZONE_HE)
                putBoolean("shield_active", !BuildConfig.IS_PAID)
                putBoolean("initial_setup_v135", true)
                putLong("dash_status_start_ms", System.currentTimeMillis())
                apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        locationManager.startTracking()
        StatusManager.maintainState(this)
        uiHandler.post(stateMaintenanceRunnable)
        
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
        uiHandler.removeCallbacks(stateMaintenanceRunnable)
    }

    override fun onDestroy() {
        nativeHealthCheckThread?.interrupt()
        nativeHealthCheckThread = null
        super.onDestroy()
    }

    fun showLocationExplanation() {
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
        tvAlphaBadge.visibility = android.view.View.VISIBLE
    }

    private fun startNativeHealthCheckLoop() {
        if (nativeHealthCheckThread?.isAlive == true) return
        nativeHealthCheckThread = kotlin.concurrent.thread(start = true) {
            while (!Thread.currentThread().isInterrupted) {
                evaluateFailoverCondition()
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
            if (sharedPrefs.getInt("connectivity_mode", 0) != 0) return
            if (sharedPrefs.getBoolean("shield_active", false)) return

            val lastFcmMs = sharedPrefs.getLong("last_fcm_heartbeat_ms", System.currentTimeMillis())
            val elapsedMs = System.currentTimeMillis() - lastFcmMs
            val timeoutMs = 20L * 60L * 1000L 
            
            if (elapsedMs > timeoutMs) {
                android.util.Log.w("HomeFrontAlerts", "FCM Heartbeat missing for ${elapsedMs/60000} mins. Suspect outage.")
                
                val jitterMs = (0..300000).random().toLong()
                Thread.sleep(jitterMs)
                
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
                    sharedPrefs.edit().putLong("last_fcm_heartbeat_ms", System.currentTimeMillis()).apply()
                } else {
                    android.util.Log.w("HomeFrontAlerts", "HealthCheck: Proxy is DEAD. Triggering failover to Direct HFC Shield!")
                    sharedPrefs.edit().putBoolean("shield_active", true).apply()
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
            
            return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }
}
