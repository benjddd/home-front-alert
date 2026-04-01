package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MapFragment : Fragment() {

    private lateinit var mapWebView: WebView
    private lateinit var locationManager: AppLocationManager
    private lateinit var sharedPrefs: android.content.SharedPreferences

    // Updates zone and dot immediately when GPS resolves a new zone (no tab switch required)
    private val zoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val zoneHe = intent?.getStringExtra(StatusManager.EXTRA_ZONE_HE) ?: return
            val lat    = intent.getDoubleExtra(StatusManager.EXTRA_LAT, 0.0)
            val lng    = intent.getDoubleExtra(StatusManager.EXTRA_LNG, 0.0)
            mapWebView.post {
                mapWebView.evaluateJavascript("if(window.setUserZone) window.setUserZone('${zoneHe.replace("'", "\\'")}');", null)
                if (lat != 0.0 && lng != 0.0) {
                    mapWebView.evaluateJavascript("if(window.onLocationUpdate) window.onLocationUpdate($lat,$lng);", null)
                }
            }
        }
    }

    // Triggers map data refresh when an FCM alert or CLEAR arrives
    private val mapRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mapWebView.post {
                mapWebView.evaluateJavascript("if(window.fetchAndRender) fetchAndRender();", null)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_map, container, false)
        mapWebView = root.findViewById(R.id.mapWebView)
        locationManager = AppLocationManager.getInstance(requireContext())
        sharedPrefs = requireContext().getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        setupWebView()
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = mapWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        mapWebView.setOnTouchListener { view, event ->
            // Prevent parent ViewPager2 from stealing the pinch/drag gesture
            view.parent.requestDisallowInterceptTouchEvent(true)
            false // allow the WebView to handle the actual touch/pinch
        }

        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject app language so map labels match app locale
                val lang = LocaleHelper.getLanguage(requireContext())
                view?.evaluateJavascript("if (window.setAppLanguage) window.setAppLanguage('$lang');", null)
                
                // Inject user's selected area for map focus
                val userCity = requireContext()
                    .getSharedPreferences("HomeFrontAlertsPrefs", android.content.Context.MODE_PRIVATE)
                    .getString("selected_area", null)
                if (!userCity.isNullOrEmpty()) {
                    view?.evaluateJavascript("if (window.setUserZone) window.setUserZone('$userCity');", null)
                }

                updateUserLocationOnMap()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                // Prevent navigation away from the map
                return true // block all external navigation
            }
        }

        // Map URL from Developer Settings (or hardcoded default)
        val defaultUrl = "https://homefront-map-cjnpwpm63q-zf.a.run.app/map"
        val mapUrl = sharedPrefs.getString("map_service_url", defaultUrl) ?: defaultUrl
        mapWebView.loadUrl(mapUrl)
    }

    fun updateUserLocationOnMap() {
        if (!::mapWebView.isInitialized) return
        val currentLoc = locationManager.resolveCurrentLocation()
        if (currentLoc.lat != 0.0 && currentLoc.lng != 0.0) {
            mapWebView.evaluateJavascript("if (window.onLocationUpdate) { window.onLocationUpdate(${currentLoc.lat}, ${currentLoc.lng}); }", null)
            // Also refresh zone string so badge reflects current zone on tab-return
            val zone = currentLoc.zoneNameHe.replace("'", "\\'")
            mapWebView.evaluateJavascript("if (window.setUserZone) window.setUserZone('$zone');", null)
        }
    }

    override fun onStart() {
        super.onStart()
        val lbm = LocalBroadcastManager.getInstance(requireContext())
        lbm.registerReceiver(zoneReceiver,       IntentFilter(StatusManager.ACTION_ZONE_CHANGED))
        lbm.registerReceiver(mapRefreshReceiver, IntentFilter(StatusManager.ACTION_MAP_REFRESH))
    }

    override fun onStop() {
        super.onStop()
        val lbm = LocalBroadcastManager.getInstance(requireContext())
        lbm.unregisterReceiver(zoneReceiver)
        lbm.unregisterReceiver(mapRefreshReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateUserLocationOnMap()
    }
}
