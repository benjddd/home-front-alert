package com.attius.homefrontalert

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ThanksActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isRtl = LocaleHelper.getLanguage(this) == "iw"
        window.decorView.layoutDirection = if (isRtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        
        setContentView(R.layout.activity_thanks)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val content = """
            <b>Polygon Data</b>
            Thanks to Amit Finkelstein (amitfin/oref_alert) for providing the foundational polygon data used to render geographical map boundaries accurately.

            <b>Historical Data</b>
            Thanks to Dedy Leshem (dleshem/israel-alerts-data) for the comprehensive archive of historical Home Front Command alerts, which was invaluable for calibrating the alert map clustering algorithms.

            <b>Inspiration</b>
            Thanks to Maor Cohen (maorcc/oref-map) whose live alert map was the key inspiration for building the map feature in this app.

            <b>Dependencies</b>
            - MapLibre GL JS for map rendering
            - Turf.js for geographical processing
            - Firebase Cloud Messaging for critical real-time deliveries
        """.trimIndent()

        findViewById<TextView>(R.id.tvThanksContent).also {
            it.text = android.text.Html.fromHtml(content.replace("\n", "<br/>"))
        }
    }
}
