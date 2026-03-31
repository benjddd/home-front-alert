package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment

class MapFragment : Fragment() {

    private lateinit var mapWebView: WebView
    private lateinit var locationManager: AppLocationManager

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

        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateUserLocationOnMap()
            }
        }

        // The URL of the map service. For now, we use a placeholder or the backend's map endpoint
        // It relies on the standalone map service now.
        val mapUrl = "https://homefront-map-271569424602.me-west1.run.app/map" // Placeholder
        mapWebView.loadUrl(mapUrl)
    }

    fun updateUserLocationOnMap() {
        if (!::mapWebView.isInitialized) return
        val currentLoc = locationManager.resolveCurrentLocation()
        if (currentLoc.lat != 0.0 && currentLoc.lng != 0.0) {
            mapWebView.evaluateJavascript("if (window.onLocationUpdate) { window.onLocationUpdate(${currentLoc.lat}, ${currentLoc.lng}); }", null)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserLocationOnMap()
    }
}
