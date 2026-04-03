package com.attius.homefrontalert

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DashboardFragment : Fragment() {

    private lateinit var tvDashStatus: TextView
    private lateinit var tvDashTimer: TextView
    private lateinit var tvLocationBadge: TextView
    private lateinit var tvLocationZone: TextView
    private lateinit var ivLocationStatus: ImageView
    private lateinit var vStatusDot: View
    private lateinit var ivShield: ImageView
    private lateinit var vStatusRing: View
    private lateinit var layoutDashboardContent: ConstraintLayout
    private lateinit var tvDashCountdown: TextView
    private lateinit var tvRecentAlertsSummary: TextView

    private lateinit var tvLastAlertZones: TextView
    private lateinit var tvLastAlertInfo: TextView
    private lateinit var cardLastAlert: CardView

    private lateinit var sharedPrefs: android.content.SharedPreferences
    private lateinit var locationManager: AppLocationManager
    private lateinit var distanceCalculator: ZoneDistanceCalculator

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isResolvingLocation = false
    private var isLastAlertZonesExpanded = false

    // Immediately refreshes the full UI when StatusManager reports a state change.
    // Registered/unregistered with the fragment's active lifecycle (onResume/onPause).
    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshDashboardState()
        }
    }

    // Runs every 1s while fragment is visible — drives countdown/elapsed timer display only.
    // Full state reads are handled by statusReceiver; this just advances the clock string.
    private val uiUpdater = object : Runnable {
        override fun run() {
            refreshTimerDisplay()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_dashboard, container, false)
        
        sharedPrefs = requireContext().getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        locationManager = AppLocationManager.getInstance(requireContext())
        distanceCalculator = ZoneDistanceCalculator(requireContext())

        bindViews(root)
        
        return root
    }

    private fun bindViews(root: View) {
        tvDashStatus = root.findViewById(R.id.tvDashStatus)
        tvDashTimer = root.findViewById(R.id.tvDashTimer)
        tvLocationBadge = root.findViewById(R.id.tvLocationBadge)
        tvLocationZone = root.findViewById(R.id.tvLocationZone)
        ivLocationStatus = root.findViewById(R.id.ivLocationStatus)
        vStatusDot = root.findViewById(R.id.vStatusDot)
        ivShield = root.findViewById(R.id.ivShield)
        vStatusRing = root.findViewById(R.id.vStatusRing)
        layoutDashboardContent = root.findViewById(R.id.layoutDashboardContent)
        tvDashCountdown = root.findViewById(R.id.tvDashCountdown)
        tvRecentAlertsSummary = root.findViewById(R.id.tvRecentAlertsSummary)
        
        tvLastAlertZones = root.findViewById(R.id.tvLastAlertZones)
        tvLastAlertInfo = root.findViewById(R.id.tvLastAlertInfo)
        cardLastAlert = root.findViewById(R.id.cardLastAlert)

        tvLocationBadge.setOnClickListener { (activity as? MainActivity)?.showLocationExplanation() }
        tvLocationZone.setOnClickListener { (activity as? MainActivity)?.showLocationExplanation() }
        ivLocationStatus.setOnClickListener { (activity as? MainActivity)?.showLocationExplanation() }
    }

    override fun onResume() {
        super.onResume()
        // Event-driven: immediate update on any status change from FCM or local polling
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(statusReceiver, IntentFilter(StatusManager.ACTION_STATUS_CHANGED))
        StatusManager.maintainState(requireContext())
        refreshDashboardState()     // sync on tab-return
        uiHandler.post(uiUpdater)   // start 1s timer ticker
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(statusReceiver)
        uiHandler.removeCallbacks(uiUpdater)
    }

    /**
     * Lightweight 1s tick — only advances the "Monitoring for / Active for" timer string.
     * Also enforces the UI-side 30-min failover (third independent guard alongside
     * the backend timer and recalculateStatus()).
     * Full dashboard refresh happens via statusReceiver on STATUS_CHANGED broadcast.
     */
    private fun refreshTimerDisplay() {
        if (!isAdded) return
        val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
        val startTime = sharedPrefs.getLong("dash_status_start_ms", System.currentTimeMillis())

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
        val unitS = getString(R.string.unit_seconds_short)
        val unitM = getString(R.string.unit_minutes_short)
        val unitH = getString(R.string.unit_hours_short)
        val dispTimer = when {
            elapsedSec < 60   -> "${elapsedSec}$unitS"
            elapsedSec < 3600 -> String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60)
            else              -> String.format("%d$unitH %d$unitM", elapsedSec / 3600, (elapsedSec % 3600) / 60)
        }
        val timerPrefix = if (status == "GREEN") getString(R.string.monitoring_for) else getString(R.string.active_for)
        tvDashTimer.text = "$timerPrefix $dispTimer"
    }

    private fun refreshDashboardState() {
        val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
        val startTime = sharedPrefs.getLong("dash_status_start_ms", System.currentTimeMillis())

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
        val unitS = getString(R.string.unit_seconds_short)
        val unitM = getString(R.string.unit_minutes_short)
        val unitH = getString(R.string.unit_hours_short)
        
        val dispTimer = when {
            elapsedSec < 60 -> "${elapsedSec}$unitS"
            elapsedSec < 3600 -> String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60)
            else -> String.format("%d$unitH %d$unitM", elapsedSec / 3600, (elapsedSec % 3600) / 60)
        }
        
        val timerPrefix = if (status == "GREEN") getString(R.string.monitoring_for) else getString(R.string.active_for)
        tvDashTimer.text = "$timerPrefix $dispTimer"

        val snapshot = StatusManager.getActiveThreatsSnapshot(requireContext())
        val alertsCount10m = snapshot.active10mCount
        val closestDist10m = snapshot.closestDist10m

        if (alertsCount10m > 0) {
            tvRecentAlertsSummary.visibility = View.VISIBLE
            val distText = if (closestDist10m == Double.MAX_VALUE) getString(R.string.remote_alert) else String.format("%.1f km", closestDist10m)
            tvRecentAlertsSummary.text = getString(R.string.recent_alerts_summary, alertsCount10m, distText)
        } else {
            tvRecentAlertsSummary.visibility = View.GONE
        }
        
        val statusColor = AlertColors.fromStatus(status)

        updateDashboardBackground(statusColor)
        updateStatusTextAndIcons(status, statusColor)
        refreshLocationStatus()
        refreshLastAlertHistory()
    }

    private fun refreshLocationStatus() {
        if (isResolvingLocation) return
        isResolvingLocation = true
        
        kotlin.concurrent.thread(start = true) {
            try {
                if (!isAdded) return@thread
                val res = locationManager.resolveCurrentLocation()
                val localizedName = distanceCalculator.getLocalizedName(res.zoneNameHe)
                
                uiHandler.post {
                    tvLocationZone.text = localizedName
                    
                    if (res.source == "GPS") {
                        ivLocationStatus.setImageResource(R.drawable.ic_gps_antenna)
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#34C759"))
                        tvLocationBadge.visibility = View.GONE
                    } else if (res.activeMode == LocationTrackingMode.GPS_LIVE) {
                        ivLocationStatus.setImageResource(R.drawable.ic_gps_antenna)
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(AlertColors.THREAT)
                        tvLocationBadge.visibility = View.VISIBLE
                        tvLocationBadge.text = getString(R.string.status_gps_searching_short)
                        tvLocationBadge.setTextColor(AlertColors.THREAT)
                    } else {
                        ivLocationStatus.setImageResource(R.drawable.ic_manual_location)
                        val color = if (res.source == "SAVED") Color.parseColor("#808080") else Color.parseColor("#424242")
                        ivLocationStatus.imageTintList = ColorStateList.valueOf(color)
                        tvLocationBadge.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // ignored
            } finally {
                isResolvingLocation = false
            }
        }
    }

    private fun updateDashboardBackground(statusColor: Int) {
        val cardBg = Color.parseColor("#181818")
        val borderPx = (2 * resources.displayMetrics.density).toInt()
        val border = GradientDrawable().apply {
            setColor(cardBg)
            setStroke(borderPx, statusColor)
            cornerRadius = 24f * resources.displayMetrics.density
        }
        layoutDashboardContent.background = border

        val glowAlpha = if (statusColor == Color.parseColor("#34C759")) 20 else 50
        val glowColor = Color.argb(glowAlpha, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
        vStatusRing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(glowColor)
        }
    }

    private fun updateStatusTextAndIcons(status: String, statusColor: Int) {
        tvDashStatus.text = when(status) {
            "RED" -> getString(R.string.critical_status)
            "ORANGE" -> getString(R.string.warning_status)
            "YELLOW" -> getString(R.string.threat_status)
            else -> getString(R.string.no_alerts)
        }
        tvDashStatus.setTextColor(statusColor)
        vStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        
        if (status == "GREEN") {
            ivShield.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.2f) })
            ivShield.alpha = 0.7f
        } else {
            ivShield.clearColorFilter()
            ivShield.alpha = 1.0f 
        }
    }

    private fun refreshLastAlertHistory() {
        if (!isAdded) return
        val zones = sharedPrefs.getString("last_alert_zones", null)
        val dist = sharedPrefs.getFloat("last_alert_dist", -1f)
        val time = sharedPrefs.getLong("last_alert_time", 0L)
        val alertType = sharedPrefs.getString("last_alert_type", "") ?: ""
        
        if (zones != null && time > 0) {
            cardLastAlert.visibility = View.VISIBLE
            val rawZoneList = zones.split(", ")
            
            val top10CitiesHe = listOf("ירושלים", "תל אביב", "חיפה", "ראשון לציון", "פתח תקווה", "אשדוד", "נתניה", "בני ברק", "באר שבע", "חולון")
            val res = locationManager.resolveCurrentLocation()
            val sortedByDistance = rawZoneList.sortedBy { z -> distanceCalculator.getDistanceToZone(res.lat, res.lng, z) ?: Double.MAX_VALUE }
            
            val topCitiesInPayload = rawZoneList.filter { z -> top10CitiesHe.any { city -> z.contains(city) } }.toSet()
            val nearest5 = sortedByDistance.take(5).toSet()
            
            val prioritySet = mutableSetOf<String>().apply {
                addAll(topCitiesInPayload)
                addAll(nearest5)
            }
            
            val remainingSorted = sortedByDistance.filter { !prioritySet.contains(it) }
            val finalPriorityList = prioritySet.toMutableList()
            var fillIndex = 0
            while (finalPriorityList.size < 10 && fillIndex < remainingSorted.size) {
                finalPriorityList.add(remainingSorted[fillIndex])
                fillIndex++
            }
            
            val tail = remainingSorted.drop(fillIndex)
            val displayList = finalPriorityList.map { distanceCalculator.getLocalizedName(it, true) }.toMutableList()
            
            var collapsedTail = ""
            var expandedTail = ""
            
            if (tail.size <= 5) {
                tail.forEach { displayList.add(distanceCalculator.getLocalizedName(it, true)) }
            } else {
                val tailLoc = tail.map { distanceCalculator.getLocalizedName(it, true) }
                val moreText = if (LocaleHelper.getLanguage(requireContext()) == "iw" || LocaleHelper.getLanguage(requireContext()) == "he") "עוד" else "more"
                collapsedTail = "... (+${tail.size} $moreText)"
                expandedTail = "... ${tailLoc.joinToString(", ")}"
            }
            
            val collapsedText = displayList.joinToString("\n") + (if (collapsedTail.isEmpty()) "" else "\n$collapsedTail")
            val expandedText = displayList.joinToString("\n") + (if (expandedTail.isEmpty()) "" else "\n$expandedTail")
            
            tvLastAlertZones.text = if (isLastAlertZonesExpanded) expandedText else collapsedText
            tvLastAlertZones.setOnClickListener {
                if (expandedTail.isNotEmpty()) {
                    isLastAlertZonesExpanded = !isLastAlertZonesExpanded
                    tvLastAlertZones.text = if (isLastAlertZonesExpanded) expandedText else collapsedText
                }
            }
            
            val diffMs = System.currentTimeMillis() - time
            val diffMin = diffMs / 60000
            val timeText = when {
                diffMin < 1 -> getString(R.string.just_now)
                diffMin < 60 -> getString(R.string.detected_ago, "${diffMin}${getString(R.string.unit_minutes_short)}")
                else -> android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date(time))
            }
            val distText = if (dist < 0) getString(R.string.remote_alert) else getString(R.string.distance, String.format("%.1f", dist))
            
            val translatedType = LocaleHelper.translateAlertType(requireContext(), alertType)
            val prefix = if (translatedType.isNotEmpty()) "$translatedType • " else ""
            tvLastAlertInfo.text = "$prefix$timeText • $distText"
        } else {
            cardLastAlert.visibility = View.GONE
        }
    }
}
