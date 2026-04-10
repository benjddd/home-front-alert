'use strict';

/**
 * alert-client.js — Polls /api/alerts/current and feeds data to the map.
 *
 * The backend's armedZones map provides zone → { type, since }.
 * We transform this into the shape onThreatUpdate() expects:
 *   { threats: { zone: { s, t, name, ctype } }, status }
 */

const POLL_INTERVAL_MS = 3000;
const MAX_BACKOFF_MS = 30000;
let pollTimer = null;
let consecutiveErrors = 0;

async function pollAlerts() {
  try {
    const res = await fetch('/api/alerts/current');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    consecutiveErrors = 0;

    // Transform backend shape → map onThreatUpdate shape
    const threats = {};
    for (const [zone, info] of Object.entries(data.threats || {})) {
      const type = info.type || 'OTHER';
      const isUrgent = ['ROCKET', 'UAV', 'INFILTRATION'].includes(type);
      threats[zone] = {
        s: isUrgent ? 'URGENT' : 'CAUTION',
        t: info.since || Date.now(),
        name: zone,
        ctype: type,
      };
    }

    const update = {
      threats,
      status: data.status || 'GREEN',
    };

    if (typeof window.onThreatUpdate === 'function') {
      window.onThreatUpdate(update);
    }

    hideConnectionError();
  } catch (e) {
    consecutiveErrors++;
    console.warn('[alert-client] Poll failed:', e.message);
    if (consecutiveErrors >= 3) {
      showConnectionError();
    }
  }

  // Schedule next poll with exponential backoff on errors
  const delay = consecutiveErrors > 0
    ? Math.min(POLL_INTERVAL_MS * Math.pow(2, consecutiveErrors - 1), MAX_BACKOFF_MS)
    : POLL_INTERVAL_MS;
  pollTimer = setTimeout(pollAlerts, delay);
}

function showConnectionError() {
  let el = document.getElementById('connection-error');
  if (!el) {
    el = document.createElement('div');
    el.id = 'connection-error';
    el.style.cssText = 'position:fixed;top:60px;left:50%;transform:translateX(-50%);background:rgba(120,30,30,0.9);color:#faa;padding:8px 18px;border-radius:8px;font:13px sans-serif;z-index:100;backdrop-filter:blur(6px);';
    el.textContent = 'Connection lost \u2014 retrying...';
    document.body.appendChild(el);
  }
}

function hideConnectionError() {
  const el = document.getElementById('connection-error');
  if (el) el.remove();
}

// Start polling when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', pollAlerts);
} else {
  pollAlerts();
}
