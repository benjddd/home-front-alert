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
    ROCKET:       { key: 'ROCKET',      priority: 4, color: '#EF4444', border: '#DC2626' },
    UAV:          { key: 'UAV',         priority: 3, color: '#F97316', border: '#EA580C' },
    INFILTRATION: { key: 'INFILTRATION',priority: 5, color: '#A855F7', border: '#9333EA' },
    PRE_WARNING:  { key: 'PRE_WARNING', priority: 1, color: '#FBBF24', border: '#F59E0B' },
    OTHER:        { key: 'OTHER',       priority: 2, color: '#64748B', border: '#475569' },
};

// Defensive normalization: uses the same ALERT_TYPE_MAP as index.js (SSOT in config).
function _resolveType(raw) {
    if (ALERT_TYPES[raw]) return raw; // already canonical
    const map = config.ALERT_TYPE_MAP;
    if (map[raw]) return map[raw];
    const lower = raw.toLowerCase();
    if (map[lower]) return map[lower];
    for (const [pattern, canonical] of Object.entries(map)) {
        if (raw.includes(pattern)) return canonical;
    }
    return raw; // caller falls back to OTHER via ALERT_TYPES[type] || ALERT_TYPES.OTHER
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
    const allStates = threatManager.getStates();

    const rawClusters = [];
    const urgentFeatures = [];
    const preWarningFeatures = [];
    const clearingZones = [];

    // 1. Process ACTIVE Threats -> Clusters & Heatmap
    const activeThreats = allStates.filter(t => t.status === 'ACTIVE');

    // Build a flat zone→addedAt lookup across all active threats for recent-highlight
    const zoneAddedAt = new Map();
    activeThreats.forEach(t => {
        if (t.zoneAddedAt) {
            for (const [name, ts] of t.zoneAddedAt) zoneAddedAt.set(name, ts);
        }
    });

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
            } else if (group.length <= 80) {
                geometry = _fullUnion(group);
                renderStyle = 'union_full';
            } else {
                // Very large clusters (100s of zones): skip expensive union,
                // use a GeometryCollection so we don't block the event loop.
                geometry = { type: 'GeometryCollection', geometries: group.map(f => f.geometry) };
                renderStyle = 'individual';
            }

            const recentZoneGeometries = group
                .filter(f => {
                    const ts = zoneAddedAt.get(f.properties.name);
                    return ts && (now - ts) < config.RECENT_ZONE_MS;
                })
                .map(f => f.geometry);

            rawClusters.push({
                id: `${type}-${now}-${Math.random().toString(36).slice(2,7)}`,
                alertType: type,
                color: typeDef.color,
                border: typeDef.border,
                priority: typeDef.priority,
                zones: clusterZones,
                geometry,
                renderStyle,
                recentZoneGeometries,
            });
        }
    }

    rawClusters.sort((a, b) => b.priority - a.priority);
    const clusters = [];
    let occupiedGeometry = null;

    for (const cluster of rawClusters) {
        const clippedGeometry = _clipGeometry(cluster.geometry, occupiedGeometry);
        if (!clippedGeometry) {
            continue;
        }

        const clippedRecentZoneGeometries = cluster.recentZoneGeometries
            .map(geometry => _clipGeometry(geometry, occupiedGeometry))
            .filter(Boolean);

        clusters.push({
            ...cluster,
            geometry: clippedGeometry,
            recentZoneGeometries: clippedRecentZoneGeometries,
        });

        occupiedGeometry = _mergeOccupiedGeometry(occupiedGeometry, clippedGeometry);
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
    function merge(a, b) { parent[find(a)] = find(b); }

    // Pre-compute centroids as raw [lng, lat] — avoids turf.centroid + turf.distance overhead
    const coords = features.map(f => {
        const c = turf.centroid(f);
        return c.geometry.coordinates; // [lng, lat]
    });

    // Fast haversine-approximation distance (km) — accurate enough for clustering
    const DEG2RAD = Math.PI / 180;
    const R = 6371; // Earth radius km
    function fastDistKm(a, b) {
        const dLat = (b[1] - a[1]) * DEG2RAD;
        const dLng = (b[0] - a[0]) * DEG2RAD;
        const avgLat = (a[1] + b[1]) * 0.5 * DEG2RAD;
        const x = dLng * Math.cos(avgLat);
        return Math.sqrt(x * x + dLat * dLat) * R;
    }

    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            if (fastDistKm(coords[i], coords[j]) <= thresholdKm) merge(i, j);
        }
    }
    const groups = {};
    for (let i = 0; i < n; i++) { const root = find(i); if (!groups[root]) groups[root] = []; groups[root].push(features[i]); }
    return Object.values(groups);
}

function _unionWithBuffer(features, bufferKm) {
    try {
        let merged = features[0];
        for (let i = 1; i < features.length; i++) merged = turf.union(merged, features[i]);
        return turf.buffer(merged, bufferKm, { units: 'kilometers' }).geometry;
    } catch { return { type: 'GeometryCollection', geometries: features.map(f => f.geometry) }; }
}

function _fullUnion(features) {
    try {
        let merged = features[0];
        for (let i = 1; i < features.length; i++) merged = turf.union(merged, features[i]);
        const points = turf.explode(merged);
        if (points.features.length < 3) return merged.geometry;
        const hull = concaveman(points.features.map(f => f.geometry.coordinates), 2, 0);
        return { type: 'Polygon', coordinates: [hull] };
    } catch { return _unionWithBuffer(features, 0.3); }
}

function _toPolygonFeature(geometry) {
    if (!geometry) return null;
    if (geometry.type === 'Feature') {
        return _toPolygonFeature(geometry.geometry);
    }
    if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
        return turf.feature(geometry);
    }
    if (geometry.type === 'GeometryCollection') {
        const polygonGeometries = geometry.geometries.filter(g => g.type === 'Polygon' || g.type === 'MultiPolygon');
        if (polygonGeometries.length === 0) return null;
        // Skip expensive sequential union for large collections — return first polygon
        // as representative so clip/merge logic has something to work with without
        // blocking the event loop for seconds.
        if (polygonGeometries.length > 80) return turf.feature(polygonGeometries[0]);
        let merged = turf.feature(polygonGeometries[0]);
        for (let i = 1; i < polygonGeometries.length; i++) {
            const next = turf.feature(polygonGeometries[i]);
            const unioned = turf.union(merged, next);
            if (!unioned) return merged;
            merged = unioned;
        }
        return merged;
    }
    return null;
}

function _clipGeometry(geometry, occupiedGeometry) {
    const polygonFeature = _toPolygonFeature(geometry);
    if (!polygonFeature) return geometry;
    if (!occupiedGeometry) return polygonFeature.geometry;
    try {
        const clipped = turf.difference(polygonFeature, occupiedGeometry);
        return clipped ? clipped.geometry : null;
    } catch {
        return polygonFeature.geometry;
    }
}

function _mergeOccupiedGeometry(occupiedGeometry, geometry) {
    const polygonFeature = _toPolygonFeature(geometry);
    if (!polygonFeature) return occupiedGeometry;
    if (!occupiedGeometry) return polygonFeature;
    try {
        return turf.union(occupiedGeometry, polygonFeature) || occupiedGeometry;
    } catch {
        return occupiedGeometry;
    }
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
