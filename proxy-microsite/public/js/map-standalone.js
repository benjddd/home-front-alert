'use strict';

/**
 * map-standalone.js — Standalone web version of the alert map.
 * Extracted from android-app/app/src/main/assets/map/map.html
 * with Android WebView bridge calls replaced by HTTP fetches.
 */

// ── Config ──────────────────────────────────────────────────────────────
const CLEAR_FADE_MS = 15 * 60 * 1000;
const CLEAR_BASE_OPACITY = 0.45;
const CLEAR_BORDER_OPACITY = 0.6;
const RECENT_ZONE_MS = 15 * 1000;
const URGENT_BASE_OPACITY = 0.5;
const CAUTION_BASE_OPACITY = 0.45;
const URGENT_BORDER_OPACITY = 0.9;
const CAUTION_BORDER_OPACITY = 0.8;

const ALERT_COLORS = {
  ROCKET:       { fill: '#C93545', border: '#A52835' },
  UAV:          { fill: '#8B5CF6', border: '#7340D8' },
  INFILTRATION: { fill: '#D97706', border: '#B45F05' },
  PRE_WARNING:  { fill: '#D4A030', border: '#B8892A' },
  URGENT:       { fill: '#C93545', border: '#A52835' },
  CAUTION:      { fill: '#D4A030', border: '#B8892A' },
};

const STATUS_LABELS = {
  he: { no_alerts: '\u05D0\u05D9\u05DF \u05D4\u05EA\u05E8\u05E2\u05D5\u05EA', threat: '\u05D0\u05D9\u05D5\u05DD \u05DE\u05E8\u05D5\u05D7\u05E7', warning: '\u05D0\u05D6\u05D4\u05E8\u05D4', critical: '\u05E7\u05E8\u05D9\u05D8\u05D9' },
  en: { no_alerts: 'NO ALERTS', threat: 'REMOTE THREAT', warning: 'WARNING', critical: 'CRITICAL' },
};

const ALERT_LABELS = {
  he: { ROCKET: '\u05D9\u05E8\u05D9 \u05E8\u05E7\u05D8\u05D5\u05EA \u05D5\u05D8\u05D9\u05DC\u05D9\u05DD', UAV: '\u05DB\u05DC\u05D9 \u05D8\u05D9\u05E1 \u05E2\u05D5\u05D9\u05DF', INFILTRATION: '\u05D7\u05D3\u05D9\u05E8\u05EA \u05DE\u05D7\u05D1\u05DC\u05D9\u05DD', PRE_WARNING: '\u05D4\u05EA\u05E8\u05E2\u05D4 \u05DE\u05D5\u05E7\u05D3\u05DE\u05EA', URGENT: '\u05D0\u05D9\u05D5\u05DD \u05D3\u05D7\u05D5\u05E3', CAUTION: '\u05D4\u05EA\u05E8\u05E2\u05D4 \u05DE\u05D5\u05E7\u05D3\u05DE\u05EA' },
  en: { ROCKET: 'Rockets / Missiles', UAV: 'Hostile UAV', INFILTRATION: 'Infiltration', PRE_WARNING: 'Pre-Warning', URGENT: 'Urgent Threat', CAUTION: 'Pre-Warning' },
};

let appLang = 'en';
const L = () => STATUS_LABELS[appLang];
const AL = () => ALERT_LABELS[appLang];

// ── Population Donut ───────────────────────────────────────────────────
let TOTAL_POPULATION = 10178000;
const POP_LABELS = {
  he: { ROCKET: '\u05E8\u05E7\u05D8\u05D5\u05EA', UAV: '\u05DB\u05DC\u05D9 \u05D8\u05D9\u05E1', INFILTRATION: '\u05D7\u05D3\u05D9\u05E8\u05D4', CAUTION: '\u05D4\u05EA\u05E8\u05E2\u05D4 \u05DE\u05D5\u05E7\u05D3\u05DE\u05EA', PRE_WARNING: '\u05D4\u05EA\u05E8\u05E2\u05D4 \u05DE\u05D5\u05E7\u05D3\u05DE\u05EA', URGENT: '\u05D0\u05D9\u05D5\u05DD \u05D3\u05D7\u05D5\u05E3' },
  en: { ROCKET: 'Rockets', UAV: 'UAV', INFILTRATION: 'Infiltration', CAUTION: 'Pre-Warning', PRE_WARNING: 'Pre-Warning', URGENT: 'Urgent' },
};
const PL = () => POP_LABELS[appLang];
let lastPopData = null;

function formatPopulation(n) {
  if (n >= 1_000_000) return '~' + (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
  if (n >= 10_000) return '~' + Math.round(n / 1000) + 'K';
  if (n >= 1_000) return '~' + (n / 1000).toFixed(1).replace(/\.0$/, '') + 'K';
  return '~' + n.toLocaleString();
}

function updatePopulationChart(popAtRisk) {
  const chartEl = document.getElementById('pop-chart');
  const svg = document.getElementById('pop-svg');
  if (!popAtRisk || popAtRisk.total <= 0) {
    chartEl.style.display = 'none';
    return;
  }
  if (popAtRisk.totalPopulation > 0) TOTAL_POPULATION = popAtRisk.totalPopulation;
  svg.querySelectorAll('.pop-arc').forEach(el => el.remove());
  const r = 17;
  const circumference = 2 * Math.PI * r;
  const byType = popAtRisk.byType || {};
  const types = Object.entries(byType).filter(([_, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  let offset = 0;
  const centerG = svg.querySelector('g');
  for (const [type, pop] of types) {
    const arcLen = (pop / TOTAL_POPULATION) * circumference;
    const colors = ALERT_COLORS[type] || ALERT_COLORS['URGENT'];
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('class', 'pop-arc');
    circle.setAttribute('cx', '22');
    circle.setAttribute('cy', '22');
    circle.setAttribute('r', String(r));
    circle.setAttribute('fill', 'none');
    circle.setAttribute('stroke', colors.fill);
    circle.setAttribute('stroke-width', '5');
    circle.setAttribute('stroke-dasharray', `${arcLen} ${circumference - arcLen}`);
    circle.setAttribute('stroke-dashoffset', String(-offset));
    circle.setAttribute('transform', 'rotate(-90 22 22)');
    svg.insertBefore(circle, centerG);
    offset += arcLen;
  }
  chartEl.style.display = 'block';
}

// Pop chart click → tooltip with breakdown
document.addEventListener('DOMContentLoaded', () => {
  const popChart = document.getElementById('pop-chart');
  if (popChart) {
    popChart.addEventListener('click', function(e) {
      e.stopPropagation();
      if (!lastPopData || lastPopData.total <= 0) return;
      const byType = lastPopData.byType || {};
      const totalLabel = appLang === 'he' ? '\u05E1\u05D4"\u05DB' : 'Total';
      let html = '<div class="pop-total">' + formatPopulation(lastPopData.total) + ' ' + totalLabel + '</div>';
      for (const [type, pop] of Object.entries(byType).sort((a, b) => b[1] - a[1])) {
        if (pop > 0) {
          const color = (ALERT_COLORS[type] || ALERT_COLORS['URGENT']).fill;
          const label = PL()[type] || type.replace(/[<>&"']/g, '');
          html += '<div class="pop-line"><span style="color:' + color + ';">\u25CF</span> '
                + formatPopulation(pop) + ' ' + label + '</div>';
        }
      }
      ttZone.innerHTML = html;
      ttType.textContent = '';
      tooltip.style.display = 'block';
      clearTimeout(tooltipTimer);
      tooltipTimer = setTimeout(() => { tooltip.style.display = 'none'; }, 5000);
    });
  }
});

// ── Language ───────────────────────────────────────────────────────────
window.setAppLanguage = (lang) => {
  appLang = (lang && (lang.startsWith('iw') || lang === 'he')) ? 'he' : 'en';
  document.documentElement.lang = appLang;
  document.documentElement.dir = appLang === 'he' ? 'rtl' : 'ltr';
  // Re-render status with current language
  if (typeof updateStatusBadge === 'function' && dot) {
    const cls = dot.className;
    if (cls === 'alert') text.textContent = L().critical;
    else if (cls === 'threat') text.textContent = L().threat;
    else if (cls === 'pre-warning') text.textContent = L().warning;
    else text.textContent = L().no_alerts;
  }
};

// ── Zone Name Translation (Hebrew → English) ─────────────────────────
const zoneNameEn = {}; // populated from zone-names-en.json

function getLocalizedZoneName(hebrewName) {
  if (appLang === 'he') return hebrewName;
  return zoneNameEn[hebrewName] || hebrewName;
}

// ── Polygon Index ─────────────────────────────────────────────────────
const polygonIndex = new Map();

function loadPolygons(json) {
  const raw = typeof json === 'string' ? JSON.parse(json) : json;
  let count = 0;
  for (const [name, coords] of Object.entries(raw)) {
    if (!Array.isArray(coords) || coords.length < 3) continue;
    const ring = coords.map(([lat, lng]) => [lng, lat]);
    const first = ring[0], last = ring[ring.length - 1];
    if (first[0] !== last[0] || first[1] !== last[1]) ring.push([...first]);
    polygonIndex.set(name, {
      type: 'Feature',
      properties: { name },
      geometry: { type: 'Polygon', coordinates: [ring] },
    });
    count++;
  }
  console.log('[map] Loaded ' + count + ' polygons');
}

// ── Map Setup ───────────────────────────────────────────────────────────
// Israel bounding box: [west, south, east, north]
const ISRAEL_BOUNDS = [[34.22, 29.49], [35.90, 33.35]];

const map = new maplibregl.Map({
  container: 'map',
  style: {
    version: 8,
    sources: {},
    layers: [
      { id: 'bg', type: 'background', paint: { 'background-color': '#0a0a18' } },
    ],
  },
  center: [34.85, 31.4],
  zoom: 7,
  minZoom: 5,
  maxZoom: 17,
  attributionControl: false,
});

// Fit entire country in view with padding for navbar + banner
map.fitBounds(ISRAEL_BOUNDS, { padding: { top: 70, bottom: 50, left: 20, right: 20 }, duration: 0 });

map.dragRotate.disable();
map.touchZoomRotate.disableRotation();

const dot  = document.getElementById('status-dot');
const text = document.getElementById('status-text');

// ── Tooltip ─────────────────────────────────────────────────────────────
const tooltip = document.getElementById('tooltip');
const ttZone  = document.getElementById('tt-zone');
const ttType  = document.getElementById('tt-type');
let tooltipTimer = null;

function showTooltip(zoneName, alertType) {
  ttZone.textContent = getLocalizedZoneName(zoneName);
  ttType.textContent = AL()[alertType] || alertType;
  tooltip.style.display = 'block';
  clearTimeout(tooltipTimer);
  tooltipTimer = setTimeout(() => { tooltip.style.display = 'none'; }, 4000);
}

// ── Data Sources ──────────────────────────────────────────────────────
const EMPTY_FC = { type: 'FeatureCollection', features: [] };
let mapReady = false;
let pendingUpdate = null;

let clearingFeatures = [];
let alertUrgentFeatures = [];
let alertCautionFeatures = [];
let recentExpireTimer = null;

// ── Basemap ──────────────────────────────────────────────────────────
let basemapLoaded = false;

function loadBasemap(outline, extras) {
  try {
    const outlineData = typeof outline === 'string' ? JSON.parse(outline) : outline;
    const extrasData = typeof extras === 'string' ? JSON.parse(extras) : extras;

    if (outlineData) {
      map.addSource('country-src', { type: 'geojson', data: outlineData });
      map.addLayer({
        id: 'country-fill', type: 'fill', source: 'country-src',
        paint: { 'fill-color': '#18182e', 'fill-opacity': 1 },
      }, firstAlertLayerId());
    }

    if (extrasData) {
      map.addSource('extras-src', { type: 'geojson', data: extrasData });
      map.addLayer({ id: 'water-fill', type: 'fill', source: 'extras-src',
        filter: ['==', ['get', 'type'], 'water'],
        paint: { 'fill-color': '#0a0a18', 'fill-opacity': 0.8 } });
      map.addLayer({ id: 'city-dots', type: 'circle', source: 'extras-src',
        filter: ['==', ['get', 'type'], 'city'],
        paint: {
          'circle-radius': ['match', ['get', 'rank'], 1, 3, 2],
          'circle-color': ['match', ['get', 'rank'], 1, '#9aa3ad', '#6f7780'],
          'circle-opacity': ['match', ['get', 'rank'], 1, 0.8, 0.6],
        } });
      map.addLayer({ id: 'neighbor-dots', type: 'circle', source: 'extras-src',
        filter: ['==', ['get', 'type'], 'neighbor'],
        paint: { 'circle-radius': 1.6, 'circle-color': '#5f6770', 'circle-opacity': 0.55 } });
    }

    basemapLoaded = true;
    console.log('[map] Basemap loaded');
  } catch (e) {
    console.error('[map] loadBasemap error:', e);
  }
}

function firstAlertLayerId() {
  for (const id of ['heat-caution', 'heat-urgent', 'clearing-fill']) {
    if (map.getLayer(id)) return id;
  }
  return undefined;
}

// ── Map Load: add layers + fetch data ──────────────────────────────────
map.on('load', () => {
  // Alert layers
  map.addSource('clearing-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'clearing-fill', type: 'fill', source: 'clearing-src',
    paint: { 'fill-color': '#2EA876', 'fill-opacity': ['number', ['get', 'opacity'], 0.45] } });
  map.addLayer({ id: 'clearing-border', type: 'line', source: 'clearing-src',
    paint: { 'line-color': '#34D399', 'line-width': 1.5, 'line-opacity': ['number', ['get', 'borderOpacity'], 0.6] } });

  map.addSource('caution-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'caution-fill', type: 'fill', source: 'caution-src',
    paint: { 'fill-color': ['string', ['get', 'fillColor'], '#D4A030'], 'fill-opacity': ['number', ['get', 'opacity'], CAUTION_BASE_OPACITY] } });
  map.addLayer({ id: 'caution-border', type: 'line', source: 'caution-src',
    paint: { 'line-color': ['string', ['get', 'borderColor'], '#B8892A'], 'line-width': 1.5, 'line-opacity': ['number', ['get', 'borderOpacity'], CAUTION_BORDER_OPACITY] } });

  map.addSource('urgent-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'urgent-fill', type: 'fill', source: 'urgent-src',
    paint: { 'fill-color': ['string', ['get', 'fillColor'], '#C93545'], 'fill-opacity': ['number', ['get', 'opacity'], URGENT_BASE_OPACITY] } });
  map.addLayer({ id: 'urgent-border', type: 'line', source: 'urgent-src',
    paint: { 'line-color': ['string', ['get', 'borderColor'], '#A52835'], 'line-width': 1.5, 'line-opacity': ['number', ['get', 'borderOpacity'], URGENT_BORDER_OPACITY] } });

  // Recent-zone highlight
  map.addSource('recent-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'recent-fill', type: 'fill', source: 'recent-src',
    paint: { 'fill-color': '#FFFFFF', 'fill-opacity': 0.25 } });
  map.addLayer({ id: 'recent-border', type: 'line', source: 'recent-src',
    paint: { 'line-color': '#FFFFFF', 'line-width': 2.5, 'line-opacity': 0.7 } });

  // Heatmap layers
  map.addSource('heat-urgent-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'heat-urgent', type: 'heatmap', source: 'heat-urgent-src',
    paint: {
      'heatmap-weight': ['get', 'weight'], 'heatmap-intensity': 0.6,
      'heatmap-radius': 30, 'heatmap-opacity': 0.5,
      'heatmap-color': ['interpolate', ['linear'], ['heatmap-density'],
        0, 'rgba(0,0,0,0)', 0.2, 'rgba(201,53,69,0.18)',
        0.5, 'rgba(201,53,69,0.38)', 0.8, 'rgba(165,40,53,0.58)', 1.0, 'rgba(140,30,40,0.72)'],
    } }, 'clearing-fill');

  map.addSource('heat-caution-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'heat-caution', type: 'heatmap', source: 'heat-caution-src',
    paint: {
      'heatmap-weight': ['get', 'weight'], 'heatmap-intensity': 0.5,
      'heatmap-radius': 30, 'heatmap-opacity': 0.45,
      'heatmap-color': ['interpolate', ['linear'], ['heatmap-density'],
        0, 'rgba(0,0,0,0)', 0.2, 'rgba(212,160,48,0.16)',
        0.5, 'rgba(212,160,48,0.38)', 0.8, 'rgba(184,137,42,0.58)', 1.0, 'rgba(184,137,42,0.72)'],
    } }, 'clearing-fill');

  map.addSource('heat-uav-src', { type: 'geojson', data: EMPTY_FC });
  map.addLayer({ id: 'heat-uav', type: 'heatmap', source: 'heat-uav-src',
    paint: {
      'heatmap-weight': ['get', 'weight'], 'heatmap-intensity': 0.55,
      'heatmap-radius': 30, 'heatmap-opacity': 0.5,
      'heatmap-color': ['interpolate', ['linear'], ['heatmap-density'],
        0, 'rgba(0,0,0,0)', 0.2, 'rgba(139,92,246,0.18)',
        0.5, 'rgba(139,92,246,0.38)', 0.8, 'rgba(115,64,216,0.58)', 1.0, 'rgba(100,50,200,0.72)'],
    } }, 'clearing-fill');

  // Click handlers for tooltips
  for (const layerId of ['urgent-fill', 'caution-fill', 'clearing-fill']) {
    map.on('click', layerId, (e) => {
      if (e.features && e.features[0]) {
        const p = e.features[0].properties;
        showTooltip(p.name || '', p.alertType || p.state || '');
      }
    });
  }

  mapReady = true;
  if (pendingUpdate) {
    renderFromThreatMap(pendingUpdate.threats, pendingUpdate.status, pendingUpdate.populationAtRisk || null);
    pendingUpdate = null;
  }

  requestAnimationFrame(tickClearingFade);
  requestAnimationFrame(tickAlertFade);
  requestAnimationFrame(tickRecentPulse);

  // Fetch geo data via HTTP (instead of Android JS bridge)
  initMapData();
});

// ── Fetch basemap fast, then preload polygons in background ────────────
let polygonsLoaded = false;

async function initMapData() {
  try {
    // Basemap + zone names (~20KB total) — map outline appears immediately
    const [outlineRes, extrasRes, namesRes] = await Promise.all([
      fetch('data/israel-outline.json'),
      fetch('data/geo-extras.json'),
      fetch('data/zone-names-en.json'),
    ]);
    const outline = await outlineRes.json();
    const extras = await extrasRes.json();
    loadBasemap(outline, extras);

    // Populate English zone name lookup
    const names = await namesRes.json();
    Object.assign(zoneNameEn, names);

    // Preload polygons in background (1.8MB) — ready when alerts arrive
    const polyRes = await fetch('data/polygons.json');
    const polyData = await polyRes.json();
    await new Promise(r => setTimeout(r, 0)); // yield before heavy processing
    loadPolygons(polyData);
    polygonsLoaded = true;
    console.log('[map] Polygons preloaded');
    // Re-render if an alert arrived while polygons were loading
    if (pendingUpdate) {
      renderFromThreatMap(pendingUpdate.threats, pendingUpdate.status, pendingUpdate.populationAtRisk || null);
      pendingUpdate = null;
    }
  } catch (e) {
    console.error('[map] Failed to load geo data:', e);
  }
}

// ── Core Render Function ────────────────────────────────────────────────
window.onThreatUpdate = function(data) {
  try {
    const d = typeof data === 'string' ? JSON.parse(data) : data;
    if (!mapReady || !polygonsLoaded) {
      pendingUpdate = d;
      // Update status badge immediately even while loading
      if (mapReady) updateStatusBadge(d.status || 'GREEN');
      return;
    }
    renderFromThreatMap(d.threats || {}, d.status || 'GREEN', d.populationAtRisk || null);
  } catch (e) {
    console.error('onThreatUpdate failed:', e);
  }
};

function renderFromThreatMap(threats, status, popAtRisk) {
  const now = Date.now();
  const urgentFeatures = [];
  const cautionFeatures = [];
  const newClearingFeatures = [];
  const recentFeatures = [];
  const heatUrgentFeatures = [];
  const heatCautionFeatures = [];
  const heatUavFeatures = [];

  for (const [zoneName, entry] of Object.entries(threats)) {
    const state = entry.s;
    const name = entry.name || zoneName;
    const poly = polygonIndex.get(name) || polygonIndex.get(zoneName);
    if (!poly) continue;

    if (state === 'CLEARING') {
      const ct = entry.ct || 0;
      if (ct > 0 && (now - ct) < CLEAR_FADE_MS) {
        const fadeFactor = Math.max(0, 1 - (now - ct) / CLEAR_FADE_MS);
        const opacity = CLEAR_BASE_OPACITY * fadeFactor;
        const borderOpacity = CLEAR_BORDER_OPACITY * fadeFactor;
        newClearingFeatures.push({
          ...poly,
          properties: { ...poly.properties, state: 'CLEARING', ct, alertType: 'CLEARING', opacity, borderOpacity },
        });
      }
      continue;
    }

    const ctype = entry.ctype || '';
    const alertType = (state === 'URGENT') ? 'URGENT' : 'CAUTION';
    const colorKey = ALERT_COLORS[ctype] ? ctype : alertType;
    const colors = ALERT_COLORS[colorKey] || ALERT_COLORS[alertType];
    const baseOpacity = (alertType === 'URGENT') ? URGENT_BASE_OPACITY : CAUTION_BASE_OPACITY;
    const baseBorderOpacity = (alertType === 'URGENT') ? URGENT_BORDER_OPACITY : CAUTION_BORDER_OPACITY;
    const elapsed = now - (entry.t || now);
    const fadeFactor = Math.max(0, 1 - elapsed / CLEAR_FADE_MS);
    const feature = {
      ...poly,
      properties: {
        ...poly.properties,
        alertType: ctype || alertType,
        t: entry.t || 0,
        fillColor: colors.fill,
        borderColor: colors.border,
        opacity: baseOpacity * fadeFactor,
        borderOpacity: baseBorderOpacity * fadeFactor,
        baseOpacity,
        baseBorderOpacity,
      },
    };

    if (alertType === 'URGENT') {
      urgentFeatures.push(feature);
    } else {
      cautionFeatures.push(feature);
    }

    // Heatmap centroid
    const ring = poly.geometry.coordinates[0];
    if (!ring || ring.length === 0) continue;
    let cx = 0, cy = 0;
    for (const [x, y] of ring) { cx += x; cy += y; }
    cx /= ring.length; cy /= ring.length;
    const heatPoint = {
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [cx, cy] },
      properties: { weight: alertType === 'URGENT' ? 4 : 1 },
    };
    if (ctype === 'UAV') {
      heatUavFeatures.push(heatPoint);
    } else if (alertType === 'URGENT') {
      heatUrgentFeatures.push(heatPoint);
    } else {
      heatCautionFeatures.push(heatPoint);
    }

    if (entry.t && (now - entry.t) < RECENT_ZONE_MS) {
      recentFeatures.push(feature);
    }
  }

  alertUrgentFeatures = urgentFeatures;
  alertCautionFeatures = cautionFeatures;

  map.getSource('urgent-src').setData({ type: 'FeatureCollection', features: urgentFeatures });
  map.getSource('caution-src').setData({ type: 'FeatureCollection', features: cautionFeatures });
  map.getSource('recent-src').setData({ type: 'FeatureCollection', features: recentFeatures });

  if (recentExpireTimer) { clearTimeout(recentExpireTimer); recentExpireTimer = null; }
  if (recentFeatures.length > 0) {
    let latestT = 0;
    for (const f of recentFeatures) {
      if (f.properties.t > latestT) latestT = f.properties.t;
    }
    const delay = Math.max(0, RECENT_ZONE_MS - (now - latestT)) + 100;
    recentExpireTimer = setTimeout(() => {
      recentExpireTimer = null;
      const src = map.getSource('recent-src');
      if (src) src.setData({ type: 'FeatureCollection', features: [] });
    }, delay);
  }

  map.getSource('heat-urgent-src').setData({ type: 'FeatureCollection', features: heatUrgentFeatures });
  map.getSource('heat-caution-src').setData({ type: 'FeatureCollection', features: heatCautionFeatures });
  map.getSource('heat-uav-src').setData({ type: 'FeatureCollection', features: heatUavFeatures });

  const newKeys = new Set(newClearingFeatures.map(f => f.properties.zone || f.properties.name));
  const preserved = clearingFeatures.filter(f => {
    const key = f.properties.zone || f.properties.name;
    return !newKeys.has(key) && (Date.now() - f.properties.ct) < CLEAR_FADE_MS;
  });
  clearingFeatures = [...preserved, ...newClearingFeatures];
  map.getSource('clearing-src').setData({ type: 'FeatureCollection', features: clearingFeatures });

  updateStatusBadge(status);
  lastPopData = popAtRisk;
  updatePopulationChart(popAtRisk);
}

// ── Status Badge ────────────────────────────────────────────────────────
function updateStatusBadge(status) {
  switch (status) {
    case 'CRITICAL': case 'RED':
      dot.className = 'alert'; text.textContent = L().critical; break;
    case 'WARNING': case 'ORANGE':
      dot.className = 'pre-warning'; text.textContent = L().warning; break;
    case 'THREAT': case 'YELLOW':
      dot.className = 'threat'; text.textContent = L().threat; break;
    default:
      dot.className = ''; text.textContent = L().no_alerts;
  }
  // Also update the hero status dot if present
  const heroDot = document.getElementById('hero-status-dot');
  const heroText = document.getElementById('hero-status-text');
  if (heroDot && heroText) {
    heroDot.className = 'hero-dot ' + (dot.className || 'calm');
    heroText.textContent = text.textContent;
  }
}

// ── Clearing Fade Animation ─────────────────────────────────────────────
function tickClearingFade() {
  if (clearingFeatures.length > 0) {
    const now = Date.now();
    const updated = [];
    let changed = false;
    for (const f of clearingFeatures) {
      const elapsed = now - f.properties.ct;
      if (elapsed >= CLEAR_FADE_MS) { changed = true; continue; }
      const fadeFactor = Math.max(0, 1 - elapsed / CLEAR_FADE_MS);
      const newOpacity = CLEAR_BASE_OPACITY * fadeFactor;
      const newBorderOpacity = CLEAR_BORDER_OPACITY * fadeFactor;
      if (f.properties.opacity === undefined || Math.abs(f.properties.opacity - newOpacity) > 0.005) {
        f.properties.opacity = newOpacity;
        f.properties.borderOpacity = newBorderOpacity;
        changed = true;
      }
      updated.push(f);
    }
    if (changed) {
      clearingFeatures = updated;
      map.getSource('clearing-src').setData({ type: 'FeatureCollection', features: clearingFeatures });
    }
  }
  setTimeout(tickClearingFade, 1000);
}

// ── Alert Fade Animation ────────────────────────────────────────────────
function fadeFeatures(features) {
  const now = Date.now();
  let changed = false;
  for (const f of features) {
    const elapsed = now - (f.properties.t || now);
    const fadeFactor = Math.max(0, 1 - elapsed / CLEAR_FADE_MS);
    if (fadeFactor <= 0) continue;
    const newOp = f.properties.baseOpacity * fadeFactor;
    const newBorderOp = f.properties.baseBorderOpacity * fadeFactor;
    if (Math.abs((f.properties.opacity || 0) - newOp) > 0.005 ||
        Math.abs((f.properties.borderOpacity || 0) - newBorderOp) > 0.005) {
      f.properties.opacity = newOp;
      f.properties.borderOpacity = newBorderOp;
      changed = true;
    }
  }
  return changed;
}

function tickAlertFade() {
  if (fadeFeatures(alertUrgentFeatures)) {
    map.getSource('urgent-src').setData({ type: 'FeatureCollection', features: alertUrgentFeatures });
  }
  if (fadeFeatures(alertCautionFeatures)) {
    map.getSource('caution-src').setData({ type: 'FeatureCollection', features: alertCautionFeatures });
  }
  setTimeout(tickAlertFade, 1000);
}

// ── Recent Zone Pulse Animation ─────────────────────────────────────────
function tickRecentPulse(ts) {
  const t = ts || 0;
  const fillOpacity = 0.20 + 0.15 * Math.sin(t / 400);
  const borderOpacity = 0.50 + 0.30 * Math.sin(t / 400);
  try {
    map.setPaintProperty('recent-fill', 'fill-opacity', fillOpacity);
    map.setPaintProperty('recent-border', 'line-opacity', borderOpacity);
  } catch (e) {
    if (!tickRecentPulse._logged) { console.warn('[map] tickRecentPulse:', e.message); tickRecentPulse._logged = true; }
  }
  requestAnimationFrame(tickRecentPulse);
}

// Initial status
updateStatusBadge('GREEN');
