package com.attius.homefrontalert

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews

import android.os.Bundle
import android.view.View

class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val sharedPrefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            val status = sharedPrefs.getString("dash_status", "GREEN") ?: "GREEN"
            val rawZone = sharedPrefs.getString("current_home_zone", "Detecting...") ?: "Detecting..."
            
            val calc = ZoneDistanceCalculator(context)
            val localizedZone = calc.getLocalizedName(rawZone)

            val statusColor = when (status) {
                "RED" -> Color.parseColor("#FF3B30")
                "ORANGE" -> Color.parseColor("#FF9500")
                "YELLOW" -> Color.parseColor("#FFD60A")
                else -> Color.parseColor("#34C759")
            }

            val statusResId = when (status) {
                "RED" -> R.string.critical_status
                "ORANGE" -> R.string.warning_status
                "YELLOW" -> R.string.threat_status
                else -> R.string.no_alerts
            }

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            views.setTextViewText(R.id.widget_status_text, context.getString(statusResId))
            views.setTextColor(R.id.widget_status_text, statusColor)
            
            val zoneLabel = context.getString(R.string.status_saved_zone).split(":")[0]
            views.setTextViewText(R.id.widget_zone_text, "$zoneLabel: $localizedZone")

            val bgAlpha = if (status == "GREEN") 20 else 50
            val bgColor = Color.argb(bgAlpha, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor))
            views.setInt(R.id.widget_container, "setBackgroundColor", bgColor)

            // Responsive Layout Logic
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            // Very narrow (1x1 mode) -> hide text container, just show colored icon
            if (minWidth < 100) {
                views.setViewVisibility(R.id.widget_text_container, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_text_container, View.VISIBLE)
            }

            // Tall enough -> show the mini-log
            if (minHeight >= 100 && minWidth >= 100) {
                views.setViewVisibility(R.id.widget_mini_log, View.VISIBLE)
                val recentLog = buildRecentLog(context)
                views.setTextViewText(R.id.widget_mini_log, recentLog)
            } else {
                views.setViewVisibility(R.id.widget_mini_log, View.GONE)
            }

            val intent = Intent(context, MainActivity::class.java).apply { 
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        
        private fun buildRecentLog(context: Context): String {
            val prefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            val threatsStr = prefs.getString("active_threat_map", "{}") ?: "{}"
            if (threatsStr == "{}" || threatsStr.isEmpty()) return "Recent Threats:\nSystem clear."
            
            try {
                val threats = org.json.JSONObject(threatsStr)
                if (threats.length() == 0) return "Recent Threats:\nSystem clear."
                
                val iter = threats.keys()
                val logLines = mutableListOf<String>()
                while(iter.hasNext() && logLines.size < 3) {
                    val z = iter.next()
                    val obj = threats.getJSONObject(z)
                    val name = obj.optString("name", z)
                    val t = obj.optLong("t", 0L)
                    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
                    logLines.add("• $timeFmt: $name")
                }
                return "Recent Threats:\n" + logLines.joinToString("\n")
            } catch (e: Exception) {
                return "Recent Threats:\nData unavailable."
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = android.content.ComponentName(context, StatusWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            for (id in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, id)
            }
        }
    }
}
