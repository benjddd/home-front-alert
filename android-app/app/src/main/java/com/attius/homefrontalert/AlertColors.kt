package com.attius.homefrontalert

import android.graphics.Color

/**
 * AlertColors — single source of truth for the alert status palette.
 * Mirrors the backend ALERT_TYPES + status dot colors in mapState.js / map.html.
 * All Android surfaces (notification, widget, dashboard) must import from here.
 */
object AlertColors {
    /** Local urgent alert — rocket/UAV/infiltration in user's zone */
    val CRITICAL = Color.parseColor("#EF4444")
    /** Pre-warning in user's zone */
    val WARNING  = Color.parseColor("#FBBF24")
    /** Remote active threat, not in user's zone */
    val THREAT   = Color.parseColor("#FFD740")
    /** No active threats */
    val CALM     = Color.parseColor("#34C759")

    /** Map from SharedPrefs dash_status string → color int */
    fun fromStatus(status: String): Int = when (status) {
        "RED"    -> CRITICAL
        "ORANGE" -> WARNING
        "YELLOW" -> THREAT
        else     -> CALM
    }
}
