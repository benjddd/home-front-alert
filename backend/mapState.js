'use strict';
/**
 * mapState.js
 *
 * Server-side alert state manager for the map feature.
 * Tracks active HFC alert zones with 30-minute expiration,
 * clusters them geographically, and computes union GeoJSON
 * ready for MapLibre rendering.
 *
 * Clustering thresholds calibrated from March 2026 real data:
 *   338,641 zone-alerts analysed via dleshem/israel-alerts-data.
 */

'use strict';
const turf      = require('@turf/turf');
const concaveman = require('concaveman');
const polygonCache = require('./polygonCache');

// ── Constants ──────────────────────────────────────────────────────────────

const EXPIRY_MS        = 30 * 60 * 1000; // 30 min stuck-alert expiry
const CLUSTER_DIST_KM  = 22;             // DBSCAN radius (calibrated)
const CLUSTER_SMALL    = 4;              // ≤4 zones → render individually
const CLUSTER_MEDIUM   = 12;            // 5-12 → light union + 300m buffer
// 13+ → full union + concave hull
const CLEAR_FADE_MS    = 15 * 60 * 1000; // 15-min green-fade after CALM
const RECENT_MS        = 15 * 1000;      // 15s threshold for "recently added" highlight

// ── Alert type definitions ─────────────────────────────────────────────────

const ALERT_TYPES = {
  ROCKET:      { key: 'ROCKET',      color: '#E53935', border: '#B71C1C', priority: 4 },
  UAV:         { key: 'UAV',         color: '#F4511E', border: '#BF360C', priority: 3 },
  INFILTRATION:{ key: 'INFILTRATION',color: '#7B1FA2', border: '#4A148C', priority: 5 },
  PRE_WARNING: { key: 'PRE_WARNING', color: '#FDD835', border: '#F9A825', priority: 1 },
  OTHER:       { key: 'OTHER',       color: '#546E7A', border: '#263238', priority: 2 },
};

const CATEGORY_MAP = {
  'ירי רקטות וטילים'                        : 'ROCKET',
  'חדירת כלי טיס עוין'                       : 'UAV',
  'חדירת מחבלים'                             : 'INFILTRATION',
  'בדקות הקרובות צפויות להתקבל התרעות באזורך': 'PRE_WARNING',
  'התרעה מוקדמת'                             : 'PRE_WARNING',
  'הכינו עצמכם'                              : 'PRE_WARNING',
};

function classifyAlert(categoryDesc) {
  return CATEGORY_MAP[categoryDesc?.trim()] ?? 'OTHER';
}

// ── State storage ──────────────────────────────────────────────────────────

/**
 * activeZones: Map<zoneName → { alertType, expiresAt, categoryDesc, addedAt }>
 * Pre-warning zones get promoted to ROCKET if a rocket arrives in the cluster.
 */
const activeZones = new Map();

/**
 * clearingZones: Map<zoneName → { geometry, alertType, color, clearedAt }>
 * Holds a polygon snapshot for 15 min after CALM so the client can render
 * the green → transparent fade without needing the active zone record.
 */
const clearingZones = new Map();

// ── Public API ─────────────────────────────────────────────────────────────

/**
 * Called by index.js whenever HFC pushes new alerts.
 * @param {string[]} zoneNames      Hebrew HFC zone name array
 * @param {string}   categoryDesc   HFC category_desc string
 */
function updateAlerts(zoneNames, categoryDesc) {
  const alertType = classifyAlert(categoryDesc);
  const expiresAt = Date.now() + EXPIRY_MS;
  const now = Date.now();

  for (const name of zoneNames) {
    const existing = activeZones.get(name);
    // Only upgrade priority, never downgrade
    if (!existing || ALERT_TYPES[alertType].priority > ALERT_TYPES[existing.alertType].priority) {
      activeZones.set(name, { alertType, expiresAt, categoryDesc, addedAt: now });
    } else {
      // Refresh expiry, but preserve addedAt so "recently added" logic is accurate
      existing.expiresAt = expiresAt;
    }
    // If this zone was in a clearing fade, cancel it — it's active again
    clearingZones.delete(name);
  }
}

/** Wipe all active zones (e.g. all-clear); snapshot polygons into clearingZones for fade animation. */
function clearAll() {
  const now = Date.now();
  for (const [name, info] of activeZones) {
    const feature = polygonCache.getPolygon(name);
    if (feature) {
      const typeDef = ALERT_TYPES[info.alertType] || ALERT_TYPES.OTHER;
      clearingZones.set(name, { geometry: feature.geometry, alertType: info.alertType, color: typeDef.color, clearedAt: now });
    }
  }
  activeZones.clear();
}

/** Wipe specific zones (targeted all-clear); snapshot into clearingZones for fade. */
function clearZones(zoneNames) {
  const now = Date.now();
  for (const name of zoneNames) {
    const info = activeZones.get(name);
    if (info) {
      const feature = polygonCache.getPolygon(name);
      if (feature) {
        const typeDef = ALERT_TYPES[info.alertType] || ALERT_TYPES.OTHER;
        clearingZones.set(name, { geometry: feature.geometry, alertType: info.alertType, color: typeDef.color, clearedAt: now });
      }
      activeZones.delete(name);
    }
  }
}

/**
 * Compute the full map payload ready for the client:
 * { timestamp, clusters: [ { id, alertType, color, zones, geometry, renderStyle } ] }
 */
function computeMapPayload() {
  _pruneExpired();
  _pruneClearingZones();

  // Group active zones by alertType
  const byType = {};
  for (const [name, info] of activeZones) {
    if (!byType[info.alertType]) byType[info.alertType] = [];
    byType[info.alertType].push(name);
  }

  const clusters = [];
  const now = Date.now();

  for (const [alertType, zones] of Object.entries(byType)) {
    const typeDef = ALERT_TYPES[alertType];
    const features = zones.map(z => polygonCache.getPolygon(z)).filter(Boolean);

    if (features.length === 0) continue;

    // DBSCAN clustering by centroid distance
    const groups = _dbscanCluster(features, CLUSTER_DIST_KM);

    for (const group of groups) {
      const clusterZones = group.map(f => f.properties.name);
      const size = group.length;
      let geometry, renderStyle;

      if (size <= CLUSTER_SMALL) {
        geometry = { type: 'GeometryCollection', geometries: group.map(f => f.geometry) };
        renderStyle = 'individual';
      } else if (size <= CLUSTER_MEDIUM) {
        geometry = _unionWithBuffer(group, 0.3);
        renderStyle = 'union_light';
      } else {
        geometry = _fullUnion(group);
        renderStyle = 'union_full';
      }

      // Convex hull buffered 4 km → underlying "general area" hint for the client
      const hullGeometry = _convexHullBuffered(group, 4);

      // Individual polygon geometries for zones added within the last 15 seconds
      const recentZoneGeometries = group
        .filter(f => {
          const info = activeZones.get(f.properties.name);
          return info && (now - info.addedAt) < RECENT_MS;
        })
        .map(f => f.geometry);

      clusters.push({
        id:                   `${alertType}-${Date.now()}-${Math.random().toString(36).slice(2,7)}`,
        alertType,
        color:                typeDef.color,
        border:               typeDef.border,
        priority:             typeDef.priority,
        zones:                clusterZones,
        geometry,
        renderStyle,
        hullGeometry,         // may be null for single-zone clusters
        recentZoneGeometries, // empty array if no zones < 15s old
      });
    }
  }

  // Sort by priority ascending so higher-priority types render on top in MapLibre
  clusters.sort((a, b) => a.priority - b.priority);

  // Clearing zones: snapshot of recently cleared zones for the green fade animation
  const clearingPayload = [];
  for (const [name, info] of clearingZones) {
    clearingPayload.push({
      id:        `clearing-${name.replace(/\s/g, '_')}-${info.clearedAt}`,
      geometry:  info.geometry,
      clearedAt: info.clearedAt,
    });
  }

  // Heatmap centroid points — one Point per active zone, weighted by alert priority.
  // Urgent (ROCKET/UAV/INFILTRATION) and pre-warning are separated into two
  // FeaturCollections so the client can apply different colour gradients.
  const URGENT_TYPES = ['ROCKET', 'UAV', 'INFILTRATION'];
  const urgentFeatures = [];
  const preWarningFeatures = [];
  for (const [name, info] of activeZones) {
    const feature = polygonCache.getPolygon(name);
    if (!feature) continue;
    const centroid = turf.centroid(feature);
    centroid.properties = { weight: ALERT_TYPES[info.alertType]?.priority ?? 1 };
    if (URGENT_TYPES.includes(info.alertType)) {
      urgentFeatures.push(centroid);
    } else {
      preWarningFeatures.push(centroid);
    }
  }

  return {
    timestamp:        Date.now(),
    clusters,
    clearingZones:    clearingPayload,
    urgentPoints:     { type: 'FeatureCollection', features: urgentFeatures },
    preWarningPoints: { type: 'FeatureCollection', features: preWarningFeatures },
  };
}

// ── Geometry helpers ───────────────────────────────────────────────────────

function _pruneExpired() {
  const now = Date.now();
  for (const [name, info] of activeZones) {
    if (info.expiresAt < now) activeZones.delete(name);
  }
}

function _pruneClearingZones() {
  const cutoff = Date.now() - CLEAR_FADE_MS;
  for (const [name, info] of clearingZones) {
    if (info.clearedAt < cutoff) clearingZones.delete(name);
  }
}

/** Convex hull of all feature centroids, buffered by bufferKm. Returns geometry or null. */
function _convexHullBuffered(features, bufferKm) {
  try {
    const centroids = features.map(f => turf.centroid(f));
    const hull = turf.convex(turf.featureCollection(centroids));
    if (!hull) return null;
    const buffered = turf.buffer(hull, bufferKm, { units: 'kilometers' });
    return buffered ? buffered.geometry : null;
  } catch {
    return null;
  }
}

/**
 * DBSCAN-style clustering: group features by centroid distance ≤ thresholdKm.
 * Uses union-find for O(n²) simplicity — n is always small (≤100 active zones).
 */
function _dbscanCluster(features, thresholdKm) {
  const n = features.length;
  const parent = Array.from({ length: n }, (_, i) => i);

  function find(x) {
    while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; }
    return x;
  }
  function union(a, b) { parent[find(a)] = find(b); }

  const centroids = features.map(f => turf.centroid(f));
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      const d = turf.distance(centroids[i], centroids[j], { units: 'kilometers' });
      if (d <= thresholdKm) union(i, j);
    }
  }

  const groups = {};
  for (let i = 0; i < n; i++) {
    const root = find(i);
    if (!groups[root]) groups[root] = [];
    groups[root].push(features[i]);
  }
  return Object.values(groups);
}

function _unionWithBuffer(features, bufferKm) {
  try {
    let merged = features[0];
    for (let i = 1; i < features.length; i++) {
      merged = turf.union(turf.featureCollection([merged, features[i]]));
    }
    const buffered = turf.buffer(merged, bufferKm, { units: 'kilometers' });
    return buffered.geometry;
  } catch {
    return _multiPolygon(features);
  }
}

function _fullUnion(features) {
  try {
    // Union all, then build a concave hull to fill interior gaps
    let merged = features[0];
    for (let i = 1; i < features.length; i++) {
      merged = turf.union(turf.featureCollection([merged, features[i]]));
    }
    // Extract all points for concave hull
    const points = turf.explode(merged);
    if (points.features.length < 3) return merged.geometry;
    const coords = points.features.map(f => f.geometry.coordinates);
    const hull = concaveman(coords, 2, 0); // concavity=2, lengthThreshold=0
    return { type: 'Polygon', coordinates: [hull] };
  } catch {
    return _unionWithBuffer(features, 0.3);
  }
}

function _multiPolygon(features) {
  return {
    type: 'GeometryCollection',
    geometries: features.map(f => f.geometry),
  };
}

module.exports = { updateAlerts, clearAll, clearZones, computeMapPayload, classifyAlert };
