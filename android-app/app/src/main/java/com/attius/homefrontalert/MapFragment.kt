package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject

/**
 * MapFragment — displays a MapLibre alert map from a bundled local HTML asset.
 *
 * Threat data is injected from the Android SSOT (active_threat_map in SharedPrefs)
 * via the JS bridge. Polygons are loaded once from the bundled/cached zip.
 * No network dependency for map rendering.
 */
class MapFragment : Fragment() {

    companion object {
        private const val TAG = "MapFragment"
        private const val LOCAL_MAP_URL = "file:///android_asset/map/map.html"
    }

    private lateinit var mapWebView: WebView
    private lateinit var locationManager: AppLocationManager
    private val zoneCalculator by lazy { ZoneDistanceCalculator(requireContext()) }
    @Volatile private var pageReady = false

    private val zoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val zoneHe = intent?.getStringExtra(StatusManager.EXTRA_ZONE_HE) ?: return
            val lat = intent.getDoubleExtra(StatusManager.EXTRA_LAT, 0.0)
            val lng = intent.getDoubleExtra(StatusManager.EXTRA_LNG, 0.0)
            mapWebView.post {
                mapWebView.evaluateJavascript("if(window.setUserZone) window.setUserZone(${JSONObject.quote(zoneHe)});", null)
                if (lat != 0.0 && lng != 0.0 && lat.isFinite() && lng.isFinite()) {
                    mapWebView.evaluateJavascript("if(window.onLocationUpdate) window.onLocationUpdate(${lat.coerceIn(-90.0, 90.0)},${lng.coerceIn(-180.0, 180.0)});", null)
                }
            }
        }
    }

    private val mapRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            injectThreatData()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_map, container, false)
        mapWebView = root.findViewById(R.id.mapWebView)
        locationManager = AppLocationManager.getInstance(requireContext())
        setupWebView()
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = mapWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = true
        mapWebView.webChromeClient = WebChromeClient()

        mapWebView.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true

                // Inject language
                val lang = LocaleHelper.getLanguage(requireContext())
                view?.evaluateJavascript("if(window.setAppLanguage) window.setAppLanguage(${JSONObject.quote(lang)});", null)

                // Inject user zone
                val prefs = requireContext().getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
                val userCity = prefs.getString("selected_area", null)
                if (!userCity.isNullOrEmpty()) {
                    view?.evaluateJavascript("if(window.setUserZone) window.setUserZone(${JSONObject.quote(userCity)});", null)
                }

                // Inject user location
                updateUserLocationOnMap()

                // Load basemap + polygons on background thread, then inject all data
                val ctx = requireContext().applicationContext
                Thread {
                    try {
                        // Read basemap assets from bundled files
                        val outline = ctx.assets.open("map/israel-outline.json").bufferedReader().readText()
                        val extras = ctx.assets.open("map/geo-extras.json").bufferedReader().readText()
                        val polygons = PolygonManager.getPolygonsJson(ctx)
                        mapWebView.post {
                            if (!isAdded || view == null) return@post
                            mapWebView.evaluateJavascript("if(window.loadBasemap) window.loadBasemap($outline, $extras);", null)
                            mapWebView.evaluateJavascript("if(window.loadPolygons) window.loadPolygons($polygons);", null)
                            injectThreatData()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load map assets", e)
                        mapWebView.post {
                            if (!isAdded) return@post
                            mapWebView.evaluateJavascript(
                                "if(window.showError) window.showError(${JSONObject.quote(e.message ?: "Unknown error")});",
                                null
                            )
                        }
                    }

                    // Background polygon refresh (non-blocking, daily)
                    PolygonManager.refreshInBackground(ctx)
                }.start()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean = true
        }

        mapWebView.loadUrl(LOCAL_MAP_URL)
    }

    /**
     * Inject the current threat map from SharedPreferences SSOT into the WebView.
     * Lightweight call — only zone names + states (~few KB).
     */
    private fun injectThreatData() {
        if (!pageReady || !::mapWebView.isInitialized) return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val threatMap = prefs.getString("active_threat_map", "{}") ?: "{}"
        val status = StatusManager.getCurrentStatusString(ctx)
        val popData = zoneCalculator.getPopulationAtRisk(threatMap)
        val byType = JSONObject()
        popData.forEach { (k, v) ->
            if (k != "alert" && k != "preWarning" && k != "total") byType.put(k, v)
        }
        val popJson = JSONObject().apply {
            put("alert", popData["alert"] ?: 0L)
            put("preWarning", popData["preWarning"] ?: 0L)
            put("total", popData["total"] ?: 0L)
            put("totalPopulation", zoneCalculator.getTotalPopulation())
            put("byType", byType)
        }
        val json = JSONObject().apply {
            put("threats", JSONObject(threatMap))
            put("status", status)
            put("ts", System.currentTimeMillis())
            put("populationAtRisk", popJson)
        }.toString()
        mapWebView.post {
            mapWebView.evaluateJavascript("if(window.onThreatUpdate) window.onThreatUpdate($json);", null)
        }
    }

    private fun updateUserLocationOnMap() {
        if (!::mapWebView.isInitialized) return
        val currentLoc = locationManager.resolveCurrentLocation()
        if (currentLoc.lat != 0.0 && currentLoc.lng != 0.0 && currentLoc.lat.isFinite() && currentLoc.lng.isFinite()) {
            mapWebView.evaluateJavascript(
                "if(window.onLocationUpdate) window.onLocationUpdate(${currentLoc.lat.coerceIn(-90.0, 90.0)},${currentLoc.lng.coerceIn(-180.0, 180.0)});",
                null
            )
            mapWebView.evaluateJavascript(
                "if(window.setUserZone) window.setUserZone(${JSONObject.quote(currentLoc.zoneNameHe)});",
                null
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val lbm = LocalBroadcastManager.getInstance(requireContext())
        lbm.registerReceiver(zoneReceiver, IntentFilter(StatusManager.ACTION_ZONE_CHANGED))
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
        injectThreatData()
    }

    override fun onDestroyView() {
        pageReady = false
        mapWebView.stopLoading()
        mapWebView.loadUrl("about:blank")
        mapWebView.webChromeClient = null
        mapWebView.webViewClient = WebViewClient()
        (mapWebView.parent as? ViewGroup)?.removeView(mapWebView)
        mapWebView.removeAllViews()
        mapWebView.destroy()
        super.onDestroyView()
    }
}
