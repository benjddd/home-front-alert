'use strict';

/**
 * dedup.js
 *
 * Simple zone+type TTL map for FCM dispatch deduplication.
 * HFC returns the same alert data every 1s for the duration of an event.
 * Without dedup, we'd send 411 zones via FCM every second for 10+ minutes.
 *
 * Android has its own independent dedup layer (globalSignaledCities, 180s TTL)
 * that handles both FCM and direct-HFC modes. This backend dedup is solely
 * to prevent FCM traffic flooding.
 */

const TTL_MS = 3 * 60 * 1000; // 3 minutes — matches Android's default alert_ttl_seconds

// "zone:type" → dispatchedAt timestamp
const dispatched = new Map();

/**
 * Returns the subset of zones that have NOT been dispatched recently
 * for the given type. Records the dispatch for future calls.
 * @param {string[]} zones - zone names
 * @param {string} type - canonical alert type (ROCKET, UAV, etc.)
 * @returns {string[]} new zones that should be dispatched
 */
function filterNew(zones, type) {
    const now = Date.now();
    const newZones = [];
    for (const zone of zones) {
        const key = `${zone}:${type}`;
        const prev = dispatched.get(key);
        if (prev && now - prev < TTL_MS) continue;
        dispatched.set(key, now);
        newZones.push(zone);
    }
    return newZones;
}

/**
 * Prune expired entries to prevent unbounded memory growth.
 */
function prune() {
    const cutoff = Date.now() - TTL_MS;
    for (const [key, ts] of dispatched) {
        if (ts < cutoff) dispatched.delete(key);
    }
}

// Auto-prune every 30 seconds
setInterval(prune, 30 * 1000);

module.exports = { filterNew, prune };
