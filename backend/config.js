'use strict';

/**
 * config.js
 *
 * Single source of truth for all tuneable backend constants.
 * Change values here; nothing else needs to be touched.
 */

module.exports = {

    // ── Threat lifecycle ────────────────────────────────────────────────────

    /**
     * How long (ms) a zone must be absent from HFC data before the threat
     * is automatically cleared.  Explicit all-clear from HFC still clears
     * instantly regardless of this value.
     * Default: 30 minutes.
     */
    THREAT_EXPIRY_MS: 30 * 60 * 1000,

    /**
     * How long (ms) a CLEARING threat stays visible on the map as a green
     * fade before being fully removed from state.
     * Default: 15 minutes.
     */
    CLEARING_FADE_MS: 15 * 60 * 1000,

    /**
     * How long (ms) to throttle periodic lastSeenAt disk-writes so we don't
     * hit the filesystem on every 1-second poll tick.
     * Must be << THREAT_EXPIRY_MS.  Default: 30 seconds.
     */
    STATE_SAVE_THROTTLE_MS: 30 * 1000,

    // ── Alert history ───────────────────────────────────────────────────────

    /**
     * Rolling window (ms) used by getRecentAlertCount() for the dashboard
     * "alerts in the last N minutes" badge.
     * Default: 10 minutes.
     */
    ALERT_HISTORY_WINDOW_MS: 10 * 60 * 1000,

    // ── HFC Poller ──────────────────────────────────────────────────────────

    /**
     * How often (ms) the backend polls the HFC Alerts.json endpoint.
     * Default: 1 second.
     */
    POLL_INTERVAL_MS: 1000,

    /**
     * HTTP timeout (ms) for each HFC API request.
     * Default: 3 seconds.
     */
    HFC_REQUEST_TIMEOUT_MS: 3000,

    // ── Map clustering ──────────────────────────────────────────────────────

    /**
     * Maximum distance (km) between two zone centroids for them to be
     * placed in the same map cluster.
     * Default: 12 km.
     */
    CLUSTER_DIST_KM: 12,

    // ── Recent-zone highlight ───────────────────────────────────────────────

    /**
     * Zones added to a threat within this window (ms) are rendered with a
     * brighter fill overlay on the map to draw attention to new activity.
     * Default: 15 seconds.
     */
    RECENT_ZONE_MS: 15 * 1000,

    // ── Alert type normalization ────────────────────────────────────────────

    /**
     * SSOT mapping of raw HFC/FCM alert type strings → canonical keys.
     * Used by both index.js (normalizeAlertType) and mapState.js (_resolveType).
     * Hebrew strings: official HFC API phrases.
     * English strings: FCM canonicalType values + legacy English aliases.
     */
    ALERT_TYPE_MAP: {
        // Rocket / missile
        'ירי רקטות וטילים': 'ROCKET',
        'ירי רקטות':        'ROCKET',
        'rocket':            'ROCKET',
        'missiles':          'ROCKET',
        // "Enter the shelter now!" — treated as rocket-level urgent
        'היכנסו למרחב מוגן עכשיו!': 'ROCKET',
        // UAV / drone
        'חדירת כלי טיס עוין': 'UAV',
        'כלי טיס עוין':        'UAV',
        'uav':                  'UAV',
        'drone':                'UAV',
        // Infiltration
        'חדירת מחבלים': 'INFILTRATION',
        'infiltration':  'INFILTRATION',
        // Pre-warning (multiple HFC phrases in use)
        'התרעה מוקדמת': 'PRE_WARNING',
        // "In the next few minutes, alerts are expected in your area"
        'בדקות הקרובות צפויות להתקבל התרעות באזורך': 'PRE_WARNING',
        // "Prepare yourselves"
        'הכינו עצמכם': 'PRE_WARNING',
        'pre-warning':  'PRE_WARNING',
        'pre_warning':  'PRE_WARNING',
        // All-clear / end of event (normalise to CALM so dispatch logic catches them)
        'האירוע הסתיים':                             'CALM',
        'חזרה לשגרה':                                'CALM',
        'השוהים במרחב המוגן יכולים לצאת':            'CALM',
        'calm':                                       'CALM',
        'clear':                                      'CALM',
        'all_clear':                                  'CALM',
        // Other / natural / security events
        'רעידת אדמה':           'OTHER',
        'צונאמי':               'OTHER',
        'אירוע רדיולוגי':       'OTHER',
        'אירוע קרינה':          'OTHER',
        'אירוע חומרים מסוכנים': 'OTHER',
        'חשש לצונאמי':          'OTHER',
        'התרעה ביטחונית':       'OTHER',
    },
};
