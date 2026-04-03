'use strict';

const fs = require('fs');
const path = require('path');
const config = require('./config');

/**
 * threatManager.js
 * 
 * Layer 2: Normalized State Machine.
 * Manages "Threat" objects as the Single Source of Truth.
 */

const STATE_FILE = path.join(__dirname, 'state.json');

const THREAT_STATUS = {
    ACTIVE: 'ACTIVE',
    CLEARING: 'CLEARING'
};

class ThreatManager {
    constructor() {
        this.activeThreats = new Map(); // id -> Threat object
        this.alertHistory = []; // Array of { timestamp, zoneCount }
        this._lastSaveAt = 0;  // Throttle disk writes for lastSeenAt refreshes
        this.loadState();
    }

    /**
     * Entry point for HFC Poller snapshots.
     * Normalizes a collection of zones + category into a single Threat entity.
     */
    updateFromSnapshot(zones, type) {
        if (!zones || zones.length === 0) return 0;

        const now = Date.now();
        let targetThreat = [...this.activeThreats.values()].find(t => 
            t.type === type && t.status === THREAT_STATUS.ACTIVE
        );

        let newInSnapshot = 0;
        if (targetThreat) {
            if (!targetThreat.zoneAddedAt) targetThreat.zoneAddedAt = new Map();
            zones.forEach(z => {
                if (!targetThreat.zones.has(z)) {
                    targetThreat.zones.add(z);
                    targetThreat.zoneAddedAt.set(z, now);
                    newInSnapshot++;
                }
            });
            targetThreat.lastSeenAt = now;
            // Persist refreshed lastSeenAt at most once every STATE_SAVE_THROTTLE_MS
            // so server restarts don't see a stale timestamp and trigger premature expiry.
            if (now - this._lastSaveAt > config.STATE_SAVE_THROTTLE_MS) {
                this._lastSaveAt = now;
                this.saveState();
            }
        } else {
            const newId = `THREAT_${type}_${now}`;
            const newThreat = {
                id: newId,
                type,
                zones: new Set(zones),
                zoneAddedAt: new Map(zones.map(z => [z, now])),
                startTime: now,
                lastSeenAt: now,
                status: THREAT_STATUS.ACTIVE
            };
            this.activeThreats.set(newId, newThreat);
            newInSnapshot = zones.length;
            console.log(`[threatManager] New Threat detected: ${newId} (${zones.length} zones)`);
        }

        if (newInSnapshot > 0) {
            this.alertHistory.push({ timestamp: now, count: newInSnapshot });
            this.saveState();
        }
        return newInSnapshot;
    }

    /**
     * Explicit All-Clear handler.
     * CALM/CLEAR type clears ALL active threats (end-of-event is a global signal).
     * Other types only clear matching threats.
     */
    handleExplicitClear(zones, type) {
        const now = Date.now();
        let changed = false;
        const isGlobalClear = !type || type === 'CALM' || type === 'CLEAR' || type.toUpperCase().includes('CALM') || type.toUpperCase().includes('CLEAR');
        for (const [id, threat] of this.activeThreats) {
            const typeMatch = isGlobalClear || threat.type === type;
            if (typeMatch && threat.status === THREAT_STATUS.ACTIVE) {
                threat.status = THREAT_STATUS.CLEARING;
                threat.clearedAt = now;
                console.log(`[threatManager] Explicit clear: ${id} (triggered by ${type})`);
                changed = true;
            }
        }
        if (changed) this.saveState();
        return changed;
    }

    /**
     * Managed lifecycle transitions.
     */
    tick() {
        const now = Date.now();
        let changed = false;

        // Cleanup history older than ALERT_HISTORY_WINDOW_MS
        const cutoff = now - config.ALERT_HISTORY_WINDOW_MS;
        this.alertHistory = this.alertHistory.filter(h => h.timestamp > cutoff);

        for (const [id, threat] of this.activeThreats) {
            if (threat.status === THREAT_STATUS.ACTIVE) {
                // Auto-expire after THREAT_EXPIRY_MS of HFC silence.
                // Per spec: silent expiry removes zones immediately (no green fade).
                // Green fade is reserved for explicit all-clears only.
                if (now > threat.lastSeenAt + config.THREAT_EXPIRY_MS) {
                    this.activeThreats.delete(id);
                    console.log(`[threatManager] Threat ${id} silently expired after ${config.THREAT_EXPIRY_MS / 60000}m — removed immediately.`);
                    changed = true;
                    continue;
                }
            } else if (threat.status === THREAT_STATUS.CLEARING) {
                // Remove entirely after CLEARING_FADE_MS (green fade window)
                if (now > threat.clearedAt + config.CLEARING_FADE_MS) {
                    this.activeThreats.delete(id);
                    changed = true;
                }
            }
        }

        if (changed) this.saveState();
        return changed;
    }

    getRecentAlertCount() {
        return this.alertHistory.reduce((sum, h) => sum + h.count, 0);
    }

    /**
     * Force clear all active threats (e.g. from Internal API).
     */
    clearAll() {
        const now = Date.now();
        for (const threat of this.activeThreats.values()) {
            if (threat.status === THREAT_STATUS.ACTIVE) {
                threat.status = THREAT_STATUS.CLEARING;
                threat.clearedAt = now;
            }
        }
        this.saveState();
    }

    getStates() {
        return [...this.activeThreats.values()];
    }

    // --- Persistence ---

    saveState() {
        try {
            const data = JSON.stringify([...this.activeThreats.values()].map(t => ({
                ...t,
                zones: [...t.zones], // Serialize Set to Array
                zoneAddedAt: t.zoneAddedAt ? [...t.zoneAddedAt.entries()] : [], // Serialize Map to [[k,v]]
            })), null, 2);
            fs.writeFileSync(STATE_FILE, data);
        } catch (e) {
            console.error('[threatManager] Failed to save state:', e.message);
        }
    }

    loadState() {
        try {
            if (fs.existsSync(STATE_FILE)) {
                const data = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
                this.activeThreats.clear();
                data.forEach(t => {
                    t.zones = new Set(t.zones); // Deserialize Array to Set
                    t.zoneAddedAt = new Map(t.zoneAddedAt || []); // Deserialize [[k,v]] to Map
                    this.activeThreats.set(t.id, t);
                });
                console.log(`[threatManager] Loaded ${this.activeThreats.size} active threats from state.json`);
            }
        } catch (e) {
            console.error('[threatManager] Failed to load state:', e.message);
        }
    }
}

module.exports = new ThreatManager();
