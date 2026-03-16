package com.attius.homefrontalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks

enum class LocationTrackingMode {
    GPS_LIVE,
    FIXED_ZONE
}

data class ResolvedLocation(
    val lat: Double,
    val lng: Double,
    val zoneNameHe: String,
    val isFallback: Boolean,
    val source: String,      // "GPS", "SAVED", "DEFAULT"
    val activeMode: LocationTrackingMode,
    val provider: String = "Unknown",
    val accuracy: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
    val isMock: Boolean = false
)

class AppLocationManager(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val distanceCalculator = ZoneDistanceCalculator(context)

    // The Israel Museum, Jerusalem (Hardcoded Default)
    companion object {
        const val DEFAULT_LAT = 31.7719
        const val DEFAULT_LNG = 35.2017
        const val DEFAULT_ZONE_HE = "מוזיאון ישראל, ירושלים"
        
        @Volatile
        private var INSTANCE: AppLocationManager? = null

        fun getInstance(context: Context): AppLocationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppLocationManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Static memory cache shared across all instances (SSOT)
        @Volatile
        private var sharedCachedLocation: ResolvedLocation? = null
        
        // Deep Telemetry
        @Volatile var satellitesInView = 0
        @Volatile var satellitesUsed = 0
        @Volatile var avgSnr = 0f
        
        @Volatile var currentStrategy = "INITIALIZING"
    }

    private enum class Strategy { BRUTAL, MOBILE, STATIONARY }
    private var activeStrategy: Strategy? = null

    // Zone stability buffer — prevents flicker from unreliable network fixes
    private var pendingZone: String? = null
    private var pendingZoneCount: Int = 0

    private var isTracking = false
    private val looper = Looper.getMainLooper()

    private val gnssStatusListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val count = status.satelliteCount
                var used = 0
                var snrSum = 0f
                for (i in 0 until count) {
                    if (status.usedInFix(i)) used++
                    snrSum += status.getCn0DbHz(i)
                }
                satellitesInView = count
                satellitesUsed = used
                avgSnr = if (count > 0) snrSum / count else 0f
            }
        }
    } else null

    private val fusedLocationCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { handleNewLocation(it, "fused_adaptive") }
        }
    }

    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location, location.provider ?: "sensor")
        }
        override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
        override fun onProviderEnabled(p0: String) {}
        override fun onProviderDisabled(p0: String) {}
    }

    private fun handleNewLocation(location: Location, providerLabel: String) {
        val speedKmh = location.speed * 3.6f
        Log.d("HomeFrontAlerts", "LOCK: $providerLabel @ Spd: ${String.format("%.1f", speedKmh)}km/h, Acc: ${location.accuracy}m")
        
        val mode = getTrackingMode()
        if (mode == LocationTrackingMode.FIXED_ZONE) return

        // 1. Adaptive Intelligence
        val newStrategy = when {
            location.accuracy > 50 -> Strategy.BRUTAL 
            speedKmh > 10 -> Strategy.MOBILE        
            speedKmh < 2 -> Strategy.STATIONARY    
            else -> Strategy.MOBILE
        }
        if (newStrategy != activeStrategy) applyStrategy(newStrategy)

        validateAndCommitLocation(location, providerLabel)
    }

    private fun validateAndCommitLocation(location: Location, providerLabel: String): ResolvedLocation? {
        val mode = getTrackingMode()
        
        // 1. Plausibility gate: reject wild jumps.
        // Network-based location (especially with VPNs or certain routers) can lie about accuracy (e.g. 40m).
        // If we are stationary (speed < 2) or the jump is physically impossible (>50km), discard.
        val prev = sharedCachedLocation
        if (prev != null) {
            val jumpKm = haversineKm(prev.lat, prev.lng, location.latitude, location.longitude)
            
            // Tight Gate: If Accuracy is > 40m (Typical Network/Fused) and jump is > 10km, reject.
            // Professional GPS fixes use satellites and have high confidence; Network fixes are guesses.
            if (location.accuracy > 40f && jumpKm > 10.0) {
                 Log.w("HomeFrontAlerts", "REJECTED suspected Network jump: ${jumpKm.toInt()}km jump at ${location.accuracy}m accuracy from $providerLabel")
                 return null
            }
            
            // Hard Limit: Reject anything > 50km jump unless satellites are actually being used.
            if (jumpKm > 50.0 && satellitesUsed == 0) {
                 Log.w("HomeFrontAlerts", "REJECTED wild teleport: ${jumpKm.toInt()}km jump with 0 sats from $providerLabel")
                 return null
            }
        }

        // 2. Resolve to Zone
        val zoneInfo = distanceCalculator.getClosestZoneNameAndDistance(location.latitude, location.longitude) ?: return null
        val newZone = zoneInfo.first
        val committedZone = sharedCachedLocation?.zoneNameHe

        // 3. Zone stability buffer.
        // When accuracy is poor (>50m), require 2 consecutive readings in the same zone.
        if (newZone != committedZone) {
            if (location.accuracy > 50f) {
                if (newZone == pendingZone) {
                    pendingZoneCount++
                } else {
                    pendingZone = newZone
                    pendingZoneCount = 1
                }
                if (pendingZoneCount < 2) {
                    Log.d("HomeFrontAlerts", "BUFFERING zone → $newZone (${pendingZoneCount}/2, acc=${location.accuracy}m)")
                    return null 
                }
                Log.d("HomeFrontAlerts", "CONFIRMED zone → $newZone after buffering")
            }
            pendingZone = null
            pendingZoneCount = 0
        } else {
            pendingZone = null
            pendingZoneCount = 0
        }

        val res = ResolvedLocation(
            location.latitude, 
            location.longitude, 
            newZone, 
            false, 
            "GPS", 
            mode, 
            providerLabel, 
            location.accuracy,
            System.currentTimeMillis(),
            location.isFromMockProvider
        )
        updateSessionCache(res)
        savePersistentLocation(res.lat, res.lng, res.zoneNameHe)
        return res
    }

    /** Haversine distance in km between two GPS coordinates. */
    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    @SuppressLint("MissingPermission")
    private fun applyStrategy(newStrategy: Strategy) {
        if (!isTracking) return
        activeStrategy = newStrategy
        currentStrategy = newStrategy.name
        
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            
            // 1. Clean up old requests
            lm.removeUpdates(locationListener)
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
            
            // 2. Deploy Dual-Engine requests
            when(newStrategy) {
                Strategy.BRUTAL -> {
                    // Raw Hardware Fix (Always on, high frequency)
                    lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, looper)
                    lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener, looper)
                }
                Strategy.MOBILE -> {
                    // Hybrid: Fused for general, throttled GPS for precision while moving
                    val req = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                        .setMinUpdateIntervalMillis(2000L)
                        .setWaitForAccurateLocation(false)
                        .build()
                    fusedLocationClient.requestLocationUpdates(req, fusedLocationCallback, looper)
                    
                    lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 10000L, 20f, locationListener, looper)
                }
                Strategy.STATIONARY -> {
                    // Battery Saver: Fused only (Passive location where possible)
                    val req = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30000L)
                        .setMinUpdateIntervalMillis(15000L)
                        .build()
                    fusedLocationClient.requestLocationUpdates(req, fusedLocationCallback, looper)
                }
            }
            Log.i("HomeFrontAlerts", "Engine optimized for $newStrategy")
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Failed to apply engine strategy", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            isTracking = true
            applyStrategy(Strategy.BRUTAL) // Always start brutal to get initial fix
            
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusListener != null) {
                lm.registerGnssStatusCallback(gnssStatusListener, Handler(Looper.getMainLooper()))
            }
            Log.i("HomeFrontAlerts", "Location tracking started (Aggressive Initial)")
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Failed to start tracking", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.removeUpdates(locationListener)
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusListener != null) {
                lm.unregisterGnssStatusCallback(gnssStatusListener)
            }
            isTracking = false
            Log.i("HomeFrontAlerts", "Location tracking stopped")
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "Failed to stop tracking", e)
        }
    }

    fun getTrackingMode(): LocationTrackingMode {
        val modeStr = sharedPreferences.getString("tracking_mode", LocationTrackingMode.GPS_LIVE.name)
        return try { LocationTrackingMode.valueOf(modeStr!!) } catch (e: Exception) { LocationTrackingMode.GPS_LIVE }
    }

    fun setTrackingMode(mode: LocationTrackingMode) {
        sharedPreferences.edit().putString("tracking_mode", mode.name).apply()
        // Clear session cache when switching modes to prevent SSOT violations (e.g. old GPS showing in Fixed mode)
        sharedCachedLocation = null
    }

    /**
     * Legacy support for the boolean switch used in older versions
     */
    fun isUsingLiveGps(): Boolean {
        return getTrackingMode() == LocationTrackingMode.GPS_LIVE
    }

    fun setUsingLiveGps(use: Boolean) {
        if (!use) {
            // Captured switch: Live -> Manual. Retain last known as hard manual.
            val current = resolveCurrentLocation()
            if (current.source == "GPS") {
                setSavedZoneHe(current.zoneNameHe)
            }
        }
        setTrackingMode(if (use) LocationTrackingMode.GPS_LIVE else LocationTrackingMode.FIXED_ZONE)
    }

    fun getSavedZoneHe(): String? {
        return sharedPreferences.getString("fixed_zone_he", null)
    }

    fun setSavedZoneHe(zoneHe: String?) {
        val editor = sharedPreferences.edit()
        editor.putString("fixed_zone_he", zoneHe)
        
        zoneHe?.let {
            val loc = distanceCalculator.getZoneCoordinates(it)
            if (loc != null) {
                editor.putString("manual_lat", loc.lat.toString())
                editor.putString("manual_lng", loc.lng.toString())
                savePersistentLocation(loc.lat, loc.lng, it)
            }
        }
        editor.apply()
    }

    private fun getSessionCache(): ResolvedLocation? {
        val cache = sharedCachedLocation
        if (cache != null && (System.currentTimeMillis() - cache.timestampMs) < 120000) { // 2 min TTL
            return cache
        }
        return null
    }

    private fun updateSessionCache(loc: ResolvedLocation) {
        sharedCachedLocation = loc
    }

    /**
     * Resolves the location based on current settings and environmental availability.
     */
    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(): ResolvedLocation {
        val mode = getTrackingMode()
        val savedZoneHe = getSavedZoneHe()
        
        // 1. FIXED MODE: Priority Fallback
        if (mode == LocationTrackingMode.FIXED_ZONE) {
            // A. Explicit Manual Selection
            if (savedZoneHe != null) {
                val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
                if (loc != null) {
                    return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, false, "SAVED", mode, "Manual")
                }
            }
            
            // B. User Preference: Last Successful GPS/Live Location (Retention)
            val pLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
            val pLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
            val pZone = sharedPreferences.getString("last_known_zone_he", null)
            if (pLat != null && pLng != null && pZone != null) {
                return ResolvedLocation(pLat, pLng, pZone, false, "SAVED", mode, "Retained_GPS")
            }

            return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode, "Hard_Default")
        }

        // 2. LIVE GPS MODE: Check Active Listener Data first
        try {
            // A. Recent Session Cache from Listener (Fastest)
            getSessionCache()?.let { return it.copy(activeMode = mode) }

            // B. Short-wait for any system fix if cache empty
            Log.d("HomeFrontAlerts", "Cache empty, trying one-shot Fused...")
            val freshTask = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val freshLoc: Location? = Tasks.await(freshTask, 3, java.util.concurrent.TimeUnit.SECONDS)
            if (freshLoc != null) {
                validateAndCommitLocation(freshLoc, freshLoc.provider ?: "fused-oneshot")?.let { return it }
            }
        } catch (e: Exception) {
            Log.e("HomeFrontAlerts", "GPS FastResolve failed", e)
        }

        // 3. FAILBACKS (In GPS mode but searching failed)
        
        // 3a. Persistent Persistence (Last known successfully verified location - Retained indefinitely per user request)
        val pLat = sharedPreferences.getString("last_known_lat", null)?.toDoubleOrNull()
        val pLng = sharedPreferences.getString("last_known_lng", null)?.toDoubleOrNull()
        val pZone = sharedPreferences.getString("last_known_zone_he", null)
        
        if (pLat != null && pLng != null && pZone != null) {
            Log.d("HomeFrontAlerts", "GPS Fail: Using persistent fallback: $pZone")
            return ResolvedLocation(pLat, pLng, pZone, true, "SAVED", mode, "Stored_GPS", -1.0f)
        }

        // 3b. Manual Zone Fallback
        if (savedZoneHe != null) {
            val loc = distanceCalculator.getZoneCoordinates(savedZoneHe)
            if (loc != null) {
                return ResolvedLocation(loc.lat, loc.lng, savedZoneHe, true, "SAVED", mode, "Manual_Fallback", 0f)
            }
        }

        return ResolvedLocation(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZONE_HE, true, "DEFAULT", mode, "Absolute_Default", 0f)
    }

    private fun savePersistentLocation(lat: Double, lng: Double, zoneHe: String) {
        StatusManager.updateLocation(context, zoneHe, lat, lng)
    }

    /**
     * Compatibility wrapper for existing background service logic
     */
    fun getCurrentLocationSync(): Pair<Double, Double> {
        val res = resolveCurrentLocation()
        return Pair(res.lat, res.lng)
    }
    
    // Legacy support for UI
    fun getManualLat(): String? = sharedPreferences.getString("manual_lat", null)
    fun getManualLng(): String? = sharedPreferences.getString("manual_lng", null)
}
