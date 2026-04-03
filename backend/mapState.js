'use strict';

/**
 * mapState.js
 * 
 * Layer 3: Projection / View Layer.
 * Derives the Map UI and Dashboard status from the ThreatManager (SSOT).
 * No internal state storage.
 */

const turf = require('@turf/turf');
const concaveman = require('concaveman');
const polygonCache = require('./polygonCache');
const threatManager = require('./threatManager');
const config = require('./config');


const ALERT_TYPES = {
    ROCKET:       { key: 'ROCKET',      priority: 4, color: '#D32F2F', border: '#B71C1C' },
    UAV:          { key: 'UAV',         priority: 3, color: '#D84315', border: '#BF360C' },
    INFILTRATION: { key: 'INFILTRATION',priority: 5, color: '#6A1B9A', border: '#4A148C' },
    PRE_WARNING:  { key: 'PRE_WARNING', priority: 1, color: '#F9A825', border: '#F57F17' },
    OTHER:        { key: 'OTHER',       priority: 2, color: '#546E7A', border: '#37474F' },
};

// Defensive normalization: Hebrew HFC types → canonical keys (in case upstream didn't normalize)
const _TYPE_FALLBACK = {
    'ירי רקטות וטילים': 'ROCKET', 'ירי רקטות': 'ROCKET',
    'חדירת כלי טיס עוין': 'UAV', 'כלי טיס עוין': 'UAV',
    'חדירת מחבלים': 'INFILTRATION',
    'התרעה מוקדמת': 'PRE_WARNING',
};
function _resolveType(raw) {
    if (ALERT_TYPES[raw]) return raw;
    if (_TYPE_FALLBACK[raw]) return _TYPE_FALLBACK[raw];
    for (const [pattern, canonical] of Object.entries(_TYPE_FALLBACK)) {
        if (raw.includes(pattern)) return canonical;
    }
    return raw; // let caller fall back to OTHER
}

/**
 * SSOT for Dashboard, Map Badge, and sounds.
 * Derives status from all currently ACTIVE threats in the ThreatManager.
 */
function getSystemStatus(userZone) {
    const threats = threatManager.getStates().filter(t => t.status === 'ACTIVE');
    
    let hasLocalUrgent = false;
    let hasLocalPreWarning = false;
    let hasAnyActive = threats.length > 0;

    for (const threat of threats) {
        const isUrgent = ['ROCKET', 'UAV', 'INFILTRATION'].includes(threat.type);
        const isLocal = threat.zones.has(userZone);

        if (isLocal) {
            if (isUrgent) hasLocalUrgent = true;
            else if (threat.type === 'PRE_WARNING') hasLocalPreWarning = true;
        }
    }

    if (hasLocalUrgent)     return 'CRITICAL';
    if (hasLocalPreWarning) return 'WARNING';
    if (hasAnyActive)       return 'THREAT';
    return 'CALM';
}

/**
 * Main View Projection for the Map UI.
 */
function computeMapPayload(userZone) {
    const now = Date.now();
    threatManager.tick(); // Ensure logical states are fresh
    const allStates = threatManager.getStates();

    const clusters = [];
    const urgentFeatures = [];
    const preWarningFeatures = [];
    const clearingZones = [];

    // 1. Process ACTIVE Threats -> Clusters & Heatmap
    const activeThreats = allStates.filter(t => t.status === 'ACTIVE');
    
    // Group zones by type for clustering
    const byType = {};
    activeThreats.forEach(t => {
        if (!byType[t.type]) byType[t.type] = [];
        byType[t.type].push(...t.zones);
    });

    for (const [rawType, zoneList] of Object.entries(byType)) {
        const type = _resolveType(rawType);
        const typeDef = ALERT_TYPES[type] || ALERT_TYPES.OTHER;
        const features = [...new Set(zoneList)].map(name => {
            const feat = polygonCache.getPolygon(name);
            if (feat) {
                // Add to Heatmap points while we're at it
                const centroid = turf.centroid(feat);
                centroid.properties = { weight: typeDef.priority };
                if (['ROCKET', 'UAV', 'INFILTRATION'].includes(type)) {
                    urgentFeatures.push(centroid);
                } else {
                    preWarningFeatures.push(centroid);
                }
                return feat;
            }
            return null;
        }).filter(f => f);

        if (features.length === 0) continue;

        // Perform Clustering
        const groups = _dbscanCluster(features, config.CLUSTER_DIST_KM);
        for (const group of groups) {
            const clusterZones = group.map(f => f.properties.name);
            let geometry, renderStyle;

            if (group.length <= 4) {
                geometry = { type: 'GeometryCollection', geometries: group.map(f => f.geometry) };
                renderStyle = 'individual';
            } else if (group.length <= 12) {
                geometry = _unionWithBuffer(group, 0.3);
                renderStyle = 'union_light';
            } else {
                geometry = _fullUnion(group);
                renderStyle = 'union_full';
            }

            clusters.push({
                id: `${type}-${now}-${Math.random().toString(36).slice(2,7)}`,
                alertType: type,
                color: typeDef.color,
                border: typeDef.border,
                priority: typeDef.priority,
                zones: clusterZones,
                geometry,
                renderStyle,
                hullGeometry: _convexHullBuffered(group, 0.1),
            });
        }
    }

    clusters.sort((a, b) => a.priority - b.priority);

    // 2. Process CLEARING Threats -> Fading Polygons
    allStates.filter(t => t.status === 'CLEARING').forEach(t => {
        t.zones.forEach(name => {
            const feat = polygonCache.getPolygon(name);
            if (feat) {
                clearingZones.push({
                    name,
                    geometry: feat.geometry,
                    clearedAt: t.clearedAt
                });
            }
        });
    });

    return {
        timestamp: now,
        systemStatus: getSystemStatus(userZone),
        recentAlertCount: threatManager.getRecentAlertCount(),
        clusters,
        clearingZones,
        urgentPoints: { type: 'FeatureCollection', features: urgentFeatures },
        preWarningPoints: { type: 'FeatureCollection', features: preWarningFeatures },
    };
}

// --- DB-SCAN Clustering Internal Logic (Unchanged from prev turn) ---

function _dbscanCluster(features, thresholdKm) {
    const n = features.length;
    const parent = Array.from({ length: n }, (_, i) => i);
    function find(x) { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    function union(a, b) { parent[find(a)] = find(b); }
    const centroids = features.map(f => turf.centroid(f));
    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            if (turf.distance(centroids[i], centroids[j], { units: 'kilometers' }) <= thresholdKm) union(i, j);
        }
    }
    const groups = {};
    for (let i = 0; i < n; i++) { const root = find(i); if (!groups[root]) groups[root] = []; groups[root].push(features[i]); }
    return Object.values(groups);
}

function _unionWithBuffer(features, bufferKm) {
    try {
        let merged = features[0];
        for (let i = 1; i < features.length; i++) merged = turf.union(turf.featureCollection([merged, features[i]]));
        return turf.buffer(merged, bufferKm, { units: 'kilometers' }).geometry;
    } catch { return { type: 'GeometryCollection', geometries: features.map(f => f.geometry) }; }
}

function _fullUnion(features) {
    try {
        let merged = features[0];
        for (let i = 1; i < features.length; i++) merged = turf.union(turf.featureCollection([merged, features[i]]));
        const points = turf.explode(merged);
        if (points.features.length < 3) return merged.geometry;
        const hull = concaveman(points.features.map(f => f.geometry.coordinates), 2, 0);
        return { type: 'Polygon', coordinates: [hull] };
    } catch { return _unionWithBuffer(features, 0.3); }
}

function _convexHullBuffered(features, bufferKm) {
    try {
        const hull = turf.convex(turf.featureCollection(features.map(f => turf.centroid(f))));
        return hull ? turf.buffer(hull, bufferKm, { units: 'kilometers' }).geometry : null;
    } catch { return null; }
}

/**
 * Called by map-server's /internal/alert route when the backend relay
 * sends a new alert. Delegates straight to ThreatManager (SSOT).
 */
function updateAlerts(zones, categoryDesc) {
    if (!zones || zones.length === 0) return;
    threatManager.updateFromSnapshot(zones, categoryDesc);
}

/**
 * Called by map-server's /internal/alert route for per-zone clears.
 * Delegates to ThreatManager explicit-clear logic.
 */
function clearZones(zones, type) {
    if (!zones || zones.length === 0) return;
    // type may be omitted; fall back to clearing any active threat containing these zones.
    if (type) {
        threatManager.handleExplicitClear(zones, type);
    } else {
        // No type given: explicitly clear any threat that owns one of these zones
        for (const threat of threatManager.getStates()) {
            if (threat.status === 'ACTIVE' && zones.some(z => threat.zones.has(z))) {
                threatManager.handleExplicitClear([...threat.zones], threat.type);
            }
        }
    }
}

module.exports = { 
    getSystemStatus, 
    computeMapPayload,
    updateAlerts,
    clearZones,
    getRecentAlertCount: () => threatManager.getRecentAlertCount(),
    tick: () => threatManager.tick(),
    clearAll: () => threatManager.clearAll()
};
