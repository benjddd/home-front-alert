package com.attius.homefrontalert

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * PolygonManager — loads zone polygons from a bundled ZIP asset and provides
 * the raw JSON string for injection into the map WebView.
 *
 * The bundled polygons.zip is the fallback; a background daily refresh
 * downloads the latest from GitHub and caches to internal storage.
 */
object PolygonManager {
    private const val TAG = "PolygonManager"
    private const val BUNDLED_ASSET = "polygons.zip"
    private const val CACHE_FILE = "polygons.json"
    private const val CACHE_TS_KEY = "polygon_cache_ts"
    private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val DOWNLOAD_URL =
        "https://raw.githubusercontent.com/amitfin/oref_alert/main/" +
        "custom_components/oref_alert/metadata/area_to_polygon.json.zip"

    @Volatile
    private var cachedJson: String? = null
    @Volatile private var zoneCount: Int = 0

    /**
     * Returns the polygon JSON string. Loads from internal cache if fresh,
     * otherwise extracts from the bundled asset. Always synchronous and safe
     * to call from any thread.
     */
    fun getPolygonsJson(context: Context): String {
        cachedJson?.let { return it }

        val appContext = context.applicationContext
        val cacheFile = File(appContext.filesDir, CACHE_FILE)
        val prefs = appContext.getSharedPreferences("polygon_prefs", Context.MODE_PRIVATE)
        val lastRefresh = prefs.getLong(CACHE_TS_KEY, 0L)

        // Try cached file first
        if (cacheFile.exists() && cacheFile.length() > 100) {
            try {
                val json = cacheFile.readText()
                cachedJson = json
                zoneCount = countZones(json)
                Log.i(TAG, "Loaded $zoneCount zones from cache (age: ${(System.currentTimeMillis() - lastRefresh) / 60000}m)")
                return json
            } catch (e: Exception) {
                Log.w(TAG, "Cache read failed, falling back to bundled asset", e)
            }
        }

        // Extract from bundled asset
        val json = extractFromAsset(appContext)
        cachedJson = json
        zoneCount = countZones(json)
        Log.i(TAG, "Loaded $zoneCount zones from bundled asset")

        // Write to cache for faster next load
        try {
            cacheFile.writeText(json)
            prefs.edit().putLong(CACHE_TS_KEY, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write polygon cache", e)
        }

        return json
    }

    /** Zone count for diagnostics */
    fun getZoneCount(): Int = zoneCount

    /** Cache age in ms, or -1 if no cache */
    fun getCacheAgeMs(context: Context): Long {
        val prefs = context.getSharedPreferences("polygon_prefs", Context.MODE_PRIVATE)
        val ts = prefs.getLong(CACHE_TS_KEY, 0L)
        return if (ts > 0) System.currentTimeMillis() - ts else -1
    }

    /**
     * Background refresh — downloads the latest polygon zip from GitHub.
     * Call from a background thread (e.g., thread {} or coroutine).
     */
    fun refreshInBackground(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("polygon_prefs", Context.MODE_PRIVATE)
        val lastRefresh = prefs.getLong(CACHE_TS_KEY, 0L)

        if (System.currentTimeMillis() - lastRefresh < REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Polygon cache is fresh, skipping refresh")
            return
        }

        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        try {
            Log.i(TAG, "Downloading latest polygons from GitHub...")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) {
                Log.w(TAG, "Polygon download failed: HTTP ${conn.responseCode}")
                return
            }

            val zipStream = ZipInputStream(conn.inputStream)
            val entry = zipStream.nextEntry
            if (entry == null) {
                Log.w(TAG, "No entry in polygon zip")
                return
            }

            val json = zipStream.bufferedReader().readText()
            zipStream.close()

            if (json.length < 1000) {
                Log.w(TAG, "Downloaded polygon JSON too small (${json.length} chars)")
                return
            }

            // Validate and roundtrip JSON to prevent JS injection via compromised upstream.
            // JSONObject.toString() guarantees safe JS-expression output on all engines.
            val sanitized: String
            try {
                sanitized = JSONObject(json).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Downloaded polygon data is not valid JSON, rejecting", e)
                return
            }

            val cacheFile = File(appContext.filesDir, CACHE_FILE)
            val tmpFile = File(appContext.filesDir, "$CACHE_FILE.tmp")
            tmpFile.writeText(sanitized)
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
            }

            cachedJson = sanitized
            zoneCount = countZones(sanitized)
            prefs.edit().putLong(CACHE_TS_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "Polygon refresh complete: $zoneCount zones")
        } catch (e: Exception) {
            Log.w(TAG, "Polygon refresh failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun extractFromAsset(context: Context): String {
        val zipStream = ZipInputStream(context.assets.open(BUNDLED_ASSET))
        val entry = zipStream.nextEntry
            ?: throw IllegalStateException("No entry in bundled polygon zip")
        val json = zipStream.bufferedReader().readText()
        zipStream.close()
        return json
    }

    private fun countZones(json: String): Int {
        // Fast approximate count: number of top-level keys
        var count = 0
        var depth = 0
        for (c in json) {
            when (c) {
                '{' -> depth++
                '}' -> depth--
                ':' -> if (depth == 1) count++
            }
        }
        return count
    }
}
