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
            <b>Inspiration and Testing</b>
            Special thanks to Amit (amitfin/oref_alert) for providing the foundational scripts and polygon data used to render the geographical map boundaries accurately.

            <b>Historical Data</b>
            Thanks to Dedy Leshem (dleshem/israel-alerts-data) for the comprehensive archive of historical Home Front Command alerts, which was invaluable for calibrating the alert map clustering algorithms.

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
