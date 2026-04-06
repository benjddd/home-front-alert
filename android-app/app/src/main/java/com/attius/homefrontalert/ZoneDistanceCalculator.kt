package com.attius.homefrontalert

import android.content.Context
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class CityLocation(val nameHe: String, val nameEn: String, val lat: Double, val lng: Double, val countdown: Int = 0, val population: Long = 0)

data class PopulationAtRisk(
    val alert: Long = 0L,
    val preWarning: Long = 0L,
    val byType: Map<String, Long> = emptyMap()
) {
    val total: Long get() = alert + preWarning
}

/**
 * Handles the geographic distance logic between the user and active alert polygons.
 * This will parse the JSON data into a fast RAM lookup table.
 */
class ZoneDistanceCalculator(private val context: Context) {

    // A RAM cache mapping the exact Pikud HaOref zone name ("תל אביב - מזרח") to its Lat/Lng
    private val zoneCache = HashMap<String, CityLocation>()
    private val normalizedCache = HashMap<String, CityLocation>()

    private fun normalize(name: String): String {
        return name.replace(Regex("[^\\u0590-\\u05FFa-zA-Z0-9]"), "").lowercase()
    }

    init {
        try {
            val inputStream = context.resources.openRawResource(com.attius.homefrontalert.R.raw.cities)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(jsonString)
            
            for (i in 0 until jsonArray.length()) {
                val cityObj = jsonArray.getJSONObject(i)
                val zoneNameHe = cityObj.optString("name", "")
                val zoneNameEn = cityObj.optString("name_en", zoneNameHe)
                val countdown = cityObj.optInt("countdown", 0)
                val population = cityObj.optLong("population", 0L)
                val lat = cityObj.optDouble("lat", 0.0)
                val lng = cityObj.optDouble("lng", 0.0)

                if (zoneNameHe.isNotEmpty() && lat != 0.0 && lng != 0.0) {
                    val location = CityLocation(zoneNameHe, zoneNameEn, lat, lng, countdown, population)
                    zoneCache[zoneNameHe] = location
                    zoneCache[zoneNameEn] = location // Direct English lookup
                    
                    val normHe = normalize(zoneNameHe)
                    if (normHe.isNotEmpty()) normalizedCache[normHe] = location
                    
                    val normEn = normalize(zoneNameEn)
                    if (normEn.isNotEmpty()) normalizedCache[normEn] = location
                }
            }
            android.util.Log.d("HomeFrontAlerts", "Successfully loaded ${zoneCache.size} unique lookup keys")
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Failed to load cities.json", e)
        }
    }

    /**
     * Given the user's current GPS location and a single zone name, 
     * returns the distance in kilometers, or null if unknown.
     */
    fun getDistanceToZone(userLat: Double, userLng: Double, zoneName: String): Double? {
        val zoneLocation = zoneCache[zoneName] ?: normalizedCache[normalize(zoneName)]
        return if (zoneLocation != null) {
            haversineDistanceKm(userLat, userLng, zoneLocation.lat, zoneLocation.lng)
        } else null
    }

    /**
     * Given the user's current GPS location and a list of alerted zones, 
     * this returns a list of distances in kilometers to those zones.
     */
    fun calculateDistancesToAlerts(userLat: Double, userLng: Double, alertedZones: List<String>): List<Double> {
        val distancesKm = mutableListOf<Double>()

        for (zoneName in alertedZones) {
            val zoneLocation = zoneCache[zoneName] ?: normalizedCache[normalize(zoneName)]
            if (zoneLocation != null) {
                // Calculate Haversine distance
                val distance = haversineDistanceKm(
                    userLat, userLng,
                    zoneLocation.lat, zoneLocation.lng
                )
                distancesKm.add(distance)
            }
        }

        return distancesKm
    }

    /**
     * Determines the closest HFC zone name to a given coordinate.
     * Returns Pair(Name, DistanceKm)
     */
    fun getClosestZoneNameAndDistance(lat: Double, lng: Double): Pair<String, Double>? {
        var minDistance = Double.MAX_VALUE
        var closestZone: String? = null
        
        for (location in zoneCache.values) {
            val d = haversineDistanceKm(lat, lng, location.lat, location.lng)
            if (d < minDistance) {
                minDistance = d
                closestZone = location.nameHe
            }
        }
        
        Log.d("HomeFrontAlerts", "ZoneResolve: Closest is $closestZone at ${String.format("%.2f", minDistance)}km")
        
        return if (closestZone != null) Pair(closestZone, minDistance) else null
    }

    /**
     * Returns the localized name for a given Hebrew zone name.
     */
    fun getLocalizedName(hebrewName: String, isThreatPayload: Boolean = false): String {
        val loc = zoneCache[hebrewName] ?: normalizedCache[normalize(hebrewName)]
        if (loc != null) {
            val lang = LocaleHelper.getLanguage(context)
            return if (lang == "iw" || lang == "he") loc.nameHe else loc.nameEn
        }
        if (isThreatPayload) {
            // Zone is valid but absent from our coordinates database — show the HFC name directly
            return hebrewName
        }
        return hebrewName
    }

    /**
     * Returns the HFC countdown (seconds to seek shelter) for a zone.
     */
    fun getZoneCountdown(name: String): Int {
        return (zoneCache[name] ?: normalizedCache[normalize(name)])?.countdown ?: 0
    }

    /**
     * Returns all unique zone names for search/auto-complete.
     */
    fun getAllZones(): List<String> {
        val lang = LocaleHelper.getLanguage(context)
        return if (lang == "iw" || lang == "he") {
            zoneCache.values.map { it.nameHe }.distinct().sorted()
        } else {
            zoneCache.values.map { it.nameEn }.distinct().sorted()
        }
    }

    /**
     * Gets coordinates for a specific zone name (He or En).
     */
    fun getZoneCoordinates(name: String): CityLocation? {
        return zoneCache[name] ?: normalizedCache[normalize(name)]
    }

    /** Computes population at risk from the active threat map. */
    fun getPopulationAtRisk(threats: org.json.JSONObject): PopulationAtRisk {
        var alertPop = 0L
        var preWarningPop = 0L
        val byType = mutableMapOf<String, Long>()
        try {
            val iter = threats.keys()
            while (iter.hasNext()) {
                val normKey = iter.next()
                val obj = threats.getJSONObject(normKey)
                val state = obj.optString("s", "URGENT")
                if (state == "CLEARING") continue
                val city = normalizedCache[normKey] ?: continue
                val pop = city.population
                if (state == "URGENT") {
                    alertPop += pop
                    val ctype = obj.optString("ctype", "ROCKET")
                    if (ctype.isEmpty()) byType["ROCKET"] = (byType["ROCKET"] ?: 0L) + pop
                    else byType[ctype] = (byType[ctype] ?: 0L) + pop
                } else {
                    preWarningPop += pop
                    byType["CAUTION"] = (byType["CAUTION"] ?: 0L) + pop
                }
            }
        } catch (e: Exception) {
            Log.w("ZoneCalc", "getPopulationAtRisk parse error", e)
        }
        return PopulationAtRisk(alertPop, preWarningPop, byType)
    }

    /** Sum of all cached city populations (used as donut chart denominator). */
    fun getTotalPopulation(): Long {
        val seen = mutableSetOf<String>()
        var total = 0L
        for ((_, loc) in zoneCache) {
            if (seen.add(loc.nameHe)) total += loc.population
        }
        return total
    }

    /**
     * Standard implementation of the Haversine formula to calculate the
     * great-circle distance between two points on a sphere given their longitudes and latitudes.
     * 
     * @return Distance in Kilometers
     */
    private fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth's radius in kilometers

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val originLat = Math.toRadians(lat1)
        val destinationLat = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(originLat) * cos(destinationLat)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
