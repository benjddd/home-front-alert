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
            if (!targetThreat.zoneSeenAt) targetThreat.zoneSeenAt = new Map([...targetThreat.zoneAddedAt.entries()]);
            zones.forEach(z => {
                if (!targetThreat.zones.has(z)) {
                    targetThreat.zones.add(z);
                    targetThreat.zoneAddedAt.set(z, now);
                    newInSnapshot++;
                }
                targetThreat.zoneSeenAt.set(z, now);
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
                zoneSeenAt: new Map(zones.map(z => [z, now])),
                startTime: now,
                lastSeenAt: now,
                status: THREAT_STATUS.ACTIVE
            };
            this.activeThreats.set(newId, newThreat);
            newInSnapshot = zones.length;
            console.log(`[threatManager] New Threat detected: ${newId} (${zones.length} zones)`);
        }

        if (newInSnapshot > 0) {
            this.saveState();
        }
        return newInSnapshot;
    }

    /**
     * Explicit All-Clear handler.
     * CALM/CLEAR type clears matching active threats for the provided zones.
     * Other types only clear matching threats of the same type for those zones.
     */
    handleExplicitClear(zones, type) {
        const now = Date.now();
        const requestedZones = new Set(Array.isArray(zones) ? zones : []);
        let changed = false;
        const clearingThreats = [];
        const clearAnyType = !type || type === 'CALM' || type === 'CLEAR' || type.toUpperCase().includes('CALM') || type.toUpperCase().includes('CLEAR');
        if (requestedZones.size === 0) return false;
        for (const [id, threat] of this.activeThreats) {
            if (threat.status !== THREAT_STATUS.ACTIVE) {
                continue;
            }
            const typeMatch = clearAnyType || threat.type === type;
            if (!typeMatch) {
                continue;
            }
            const matchedZones = [...threat.zones].filter(zone => requestedZones.has(zone));
            if (matchedZones.length === 0) {
                continue;
            }
            if (matchedZones.length === threat.zones.size) {
                threat.status = THREAT_STATUS.CLEARING;
                threat.clearedAt = now;
                console.log(`[threatManager] Explicit clear: ${id} (triggered by ${type})`);
                changed = true;
                continue;
            }
            matchedZones.forEach(zone => {
                threat.zones.delete(zone);
                if (threat.zoneAddedAt) threat.zoneAddedAt.delete(zone);
                if (threat.zoneSeenAt) threat.zoneSeenAt.delete(zone);
            });
            const clearingId = `${id}_CLEAR_${now}_${clearingThreats.length}`;
            clearingThreats.push({
                id: clearingId,
                type: threat.type,
                zones: new Set(matchedZones),
                zoneAddedAt: new Map(),
                zoneSeenAt: new Map(),
                startTime: threat.startTime,
                lastSeenAt: threat.lastSeenAt,
                status: THREAT_STATUS.CLEARING,
                clearedAt: now
            });
            console.log(`[threatManager] Explicit clear: ${id} cleared ${matchedZones.length} zones (triggered by ${type})`);
            changed = true;
        }
        clearingThreats.forEach(threat => this.activeThreats.set(threat.id, threat));
        if (changed) this.saveState();
        return changed;
    }

    /**
     * Managed lifecycle transitions.
     */
    tick() {
        const now = Date.now();
        let changed = false;

        for (const [id, threat] of this.activeThreats) {
            if (threat.status === THREAT_STATUS.ACTIVE) {
                if (!threat.zoneAddedAt) threat.zoneAddedAt = new Map();
                if (!threat.zoneSeenAt) threat.zoneSeenAt = new Map([...threat.zoneAddedAt.entries()]);
                const expiredZones = [...threat.zones].filter(zone => {
                    const lastSeen = threat.zoneSeenAt.get(zone) || threat.lastSeenAt || threat.startTime || 0;
                    return now > lastSeen + config.THREAT_EXPIRY_MS;
                });
                if (expiredZones.length > 0) {
                    expiredZones.forEach(zone => {
                        threat.zones.delete(zone);
                        threat.zoneAddedAt.delete(zone);
                        threat.zoneSeenAt.delete(zone);
                    });
                    console.log(`[threatManager] Threat ${id} silently expired ${expiredZones.length} zones after ${config.THREAT_EXPIRY_MS / 60000}m.`);
                    changed = true;
                }
                if (threat.zones.size === 0) {
                    this.activeThreats.delete(id);
                    continue;
                }
                const zoneTimes = [...threat.zoneSeenAt.values()];
                if (zoneTimes.length > 0) {
                    threat.lastSeenAt = Math.max(...zoneTimes);
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
        const now = Date.now();
        const cutoff = now - config.ALERT_HISTORY_WINDOW_MS;
        let count = 0;
        for (const threat of this.activeThreats.values()) {
            if (threat.status !== THREAT_STATUS.ACTIVE) {
                continue;
            }
            const zoneSeenAt = threat.zoneSeenAt || threat.zoneAddedAt;
            if (!zoneSeenAt) {
                continue;
            }
            for (const timestamp of zoneSeenAt.values()) {
                if (timestamp > cutoff) {
                    count++;
                }
            }
        }
        return count;
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
                zoneSeenAt: t.zoneSeenAt ? [...t.zoneSeenAt.entries()] : [],
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
                    t.zoneSeenAt = new Map(t.zoneSeenAt || t.zoneAddedAt || []);
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
