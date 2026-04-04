package com.attius.homefrontalert

/**
 * Maps Home Front Command category IDs and titles to internal AlertType (URGENT, CAUTION, CALM).
 * Prioritizes full-string matching based on documented official phrases and captured telemetry.
 */
object AlertStyleRegistry {

    fun getStyle(catId: String, title: String): AlertType {
        val trimmedTitle = title.trim()
        val lowerTitle = trimmedTitle.lowercase()

        // --- 0th PRIORITY: Canonical backend relay keys (v1.7.7+) ---
        if (trimmedTitle == "ROCKET" || trimmedTitle == "UAV" || trimmedTitle == "INFILTRATION") {
            return AlertType.URGENT
        }
        if (trimmedTitle == "PRE_WARNING") {
            return AlertType.CAUTION
        }
        if (trimmedTitle == "CALM") {
            return AlertType.CALM
        }
        if (trimmedTitle == "OTHER") {
            // Known secondary events (earthquake, radiation, tsunami) — beep (CAUTION) is appropriate.
            return AlertType.CAUTION
        }
        
        // --- 1st PRIORITY: OFFICIAL CAPTURED PHRASES (Source: User App Screenshots) ---
        
        // URGENT PHRASES
        if (trimmedTitle == "ירי רקטות וטילים" ||
            trimmedTitle == "חדירת כלי טיס עוין" ||
            trimmedTitle == "חדירת מחבלים" ||
            trimmedTitle == "היכנסו למרחב מוגן עכשיו!") {
            return AlertType.URGENT
        }

        // CALM / ALL-CLEAR PHRASES (documented HFC phrases)
        if (trimmedTitle == "האירוע הסתיים" ||
            trimmedTitle.contains("השוהים במרחב המוגן יכולים לצאת") ||
            trimmedTitle == "חזרה לשגרה") {
            return AlertType.CALM
        }

        // CAUTION / PRE-WARNING PHRASES
        if (trimmedTitle == "בדקות הקרובות צפויות להתקבל התרעות באזורך" ||
            trimmedTitle == "הכינו עצמכם" ||
            trimmedTitle == "התרעה מוקדמת") {
            return AlertType.CAUTION
        }


        // --- 2nd PRIORITY: ALTERNATIVE RESEARCHED PHRASES ---
        if (trimmedTitle == "אירוע חומרים מסוכנים" ||
            trimmedTitle == "רעידת אדמה" ||
            trimmedTitle == "צונאמי" ||
            trimmedTitle == "אירוע קרינה" ||
            trimmedTitle == "אירוע רדיולוגי" ||
            trimmedTitle == "חשש לצונאמי" ||
            trimmedTitle == "התרעה ביטחונית") {
            return AlertType.CAUTION
        }


        // --- 3rd PRIORITY: KEYWORD FALLBACK ---

        // CALM Keywords
        if (lowerTitle.contains("רגיעה") || 
            lowerTitle.contains("סיום") || 
            lowerTitle.contains("clear") || 
            lowerTitle.contains("הסתיים") ||
            lowerTitle.contains("הסתיים האירוע")) {
            return AlertType.CALM
        }

        // CAUTION Keywords
        if (lowerTitle.contains("חשש") || 
            lowerTitle.contains("משוער") || 
            lowerTitle.contains("התרע") || 
            lowerTitle.contains("צפויות") || 
            lowerTitle.contains("הסברו") ||
            lowerTitle.contains("caution") || 
            lowerTitle.contains("approx")) {
            return AlertType.CAUTION
        }

        // URGENT Keywords
        if (lowerTitle.contains("ירי") || 
            lowerTitle.contains("כלי טיס") || 
            lowerTitle.contains("חדירה") || 
            lowerTitle.contains("rocket") || 
            lowerTitle.contains("missile") || 
            lowerTitle.contains("uav") || 
            lowerTitle.contains("drone") || 
            lowerTitle.contains("infiltration")) {
            return AlertType.URGENT
        }

        // --- 4th PRIORITY: CATEGORY FALLBACK (Legacy/Alternative) ---
        return when (catId) {
            "1", "2", "3", "4", "5", "6", "7", "8", "10", "11", "12" -> AlertType.URGENT
            "13" -> AlertType.CALM
            "14" -> AlertType.CAUTION
            "all_clear" -> AlertType.CALM
            else -> AlertType.SILENT // Unclassified HFC category — silent, no state change, logged
        }
    }
}
