package com.attius.homefrontalert

import android.graphics.Color

/**
 * AlertColors — single source of truth for the alert status palette.
 * Mirrors the backend ALERT_TYPES + status dot colors in mapState.js / map.html.
 * All Android surfaces (notification, widget, dashboard) must import from here.
 */
object AlertColors {
    /** Local urgent alert — rocket/infiltration in user's zone */
    val CRITICAL = Color.parseColor("#C93545")
    /** Hostile UAV in user's zone */
    val UAV      = Color.parseColor("#8B5CF6")
    /** Pre-warning in user's zone */
    val WARNING  = Color.parseColor("#D4A030")
    /** Remote active threat, not in user's zone */
    val THREAT   = Color.parseColor("#D4A030")
    /** No active threats */
    val CALM     = Color.parseColor("#22C55E")

    /** Map from SharedPrefs dash_status string → color int */
    fun fromStatus(status: String): Int = when (status) {
        "RED"    -> CRITICAL
        "ORANGE" -> WARNING
        "YELLOW" -> THREAT
        else     -> CALM
    }
}
