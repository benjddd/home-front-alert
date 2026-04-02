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
     * Default: 22 km.
     */
    CLUSTER_DIST_KM: 22,
};
