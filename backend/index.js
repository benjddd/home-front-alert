'use strict';

/**
 * index.js — Minimal HFC Alert Relay
 *
 * Exactly 3 responsibilities:
 *   1. Poll HFC oref.org.il every 1 second
 *   2. Normalize alert types (Hebrew → canonical)
 *   3. Dispatch FCM messages to Android devices
 *
 * No geometry computation. No map state. No polygon cache.
 * The Android app is the SSOT for all rendering (dashboard, map, sounds).
 */

const admin = require('firebase-admin');
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const path = require('path');
const config = require('./config');
const dedup = require('./dedup');

// ── Express Setup ───────────────────────────────────────────────────────
const app = express();
app.set('trust proxy', 1);

app.use(helmet({
    contentSecurityPolicy: {
        directives: {
            defaultSrc: ["'self'"],
            scriptSrc: ["'self'", "'unsafe-inline'"],
            styleSrc: ["'self'", "'unsafe-inline'"],
        },
    },
}));

app.use(cors({ origin: '*', methods: ['GET', 'POST'] }));
app.use(express.static(path.join(__dirname, 'public')));
app.get('/favicon.ico', (_req, res) => res.status(204).end());

const limiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 200,
    standardHeaders: true,
    legacyHeaders: false,
});
app.use(limiter);

// ── Firebase Init ───────────────────────────────────────────────────────
try {
    if (!admin.apps.length) {
        if (process.env.FIREBASE_SERVICE_ACCOUNT) {
            const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
            admin.initializeApp({ credential: admin.credential.cert(sa) });
            console.log('🔥 Firebase: Initialized via env var.');
        } else {
            admin.initializeApp({ credential: admin.credential.applicationDefault() });
            console.log('🔥 Firebase: Initialized via ADC.');
        }
    }
} catch (e) {
    console.error('❌ Firebase init failed:', e.message);
    process.exit(1);
}

// ── Alert Type Normalization ────────────────────────────────────────────
function normalizeAlertType(rawType) {
    if (!rawType) return 'OTHER';
    const trimmed = rawType.trim();
    const map = config.ALERT_TYPE_MAP;
    if (map[trimmed]) return map[trimmed];
    const lower = trimmed.toLowerCase();
    if (map[lower]) return map[lower];
    for (const [pattern, canonical] of Object.entries(map)) {
        if (trimmed.includes(pattern)) return canonical;
    }
    console.warn(`[normalizeAlertType] Unknown type "${rawType}" → OTHER`);
    return 'OTHER';
}

// ── Connection State ────────────────────────────────────────────────────
let isConnected = false;
let activeSource = 'None';
let lastSuccessfulPoll = null;

// ── API Endpoints ───────────────────────────────────────────────────────
app.get('/health', (_req, res) => {
    res.json({
        status: 'ok',
        connectedToHomeFront: isConnected,
        source: activeSource,
        timestamp: new Date().toISOString(),
        lastSync: lastSuccessfulPoll,
    });
});

// Manual test FCM (requires API key)
app.use(express.json());

app.post('/test-fcm', (req, res) => {
    const apiKey = req.headers['x-api-key'];
    const acceptedKeys = (process.env.API_KEYS || process.env.API_KEY || '')
        .split(/[\s,]+/).map(s => s.trim()).filter(Boolean);
    if (acceptedKeys.length === 0 || !acceptedKeys.includes(apiKey)) {
        return res.status(403).json({ error: 'Forbidden' });
    }

    const testAlert = {
        id: 'TEST_' + Date.now(),
        type: req.body.type || 'Test Alert / בדיקה',
        cities: req.body.cities || ['בדיקה — FCM Test'],
    };

    if (req.body.dryRun === true) {
        console.log('🛡️ DRY-RUN /test-fcm');
        return res.json({ ok: true, dryRun: true, alert: testAlert });
    }

    console.log('🧪 Manual /test-fcm triggered');
    sendFCMAlert(testAlert);
    res.json({ ok: true, dryRun: false, alert: testAlert });
});

// ── HFC Poller ──────────────────────────────────────────────────────────
const HFC_API_URL = 'https://www.oref.org.il/WarningMessages/alert/Alerts.json';
const HFC_HEADERS = {
    'User-Agent': 'PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)',
    'Referer': 'https://www.oref.org.il/',
    'X-Requested-With': 'XMLHttpRequest',
    'Accept': 'application/json, text/javascript, */*; q=0.01',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache',
};

async function poll() {
    try {
        let data = null;

        try {
            const res = await axios.get(HFC_API_URL, {
                headers: HFC_HEADERS,
                timeout: config.HFC_REQUEST_TIMEOUT_MS,
                validateStatus: s => s === 200 || s === 204,
            });
            data = (res.status === 204 || !res.data) ? [] : res.data;
            activeSource = 'Official API';
        } catch (e) {
            console.warn('HFC poll failed:', e.message);
        }

        if (data !== null) {
            lastSuccessfulPoll = new Date().toISOString();
            isConnected = true;
            processAlerts(data);
        } else {
            isConnected = false;
            activeSource = 'None';
        }
    } catch (e) {
        isConnected = false;
        activeSource = 'None';
    } finally {
        setTimeout(poll, config.POLL_INTERVAL_MS);
    }
}

// ── Alert Processing ────────────────────────────────────────────────────
function processAlerts(data) {
    const rawAlerts = Array.isArray(data)
        ? data
        : (data && typeof data === 'object' && Object.keys(data).length > 0 ? [data] : []);

    if (rawAlerts.length === 0) return;

    // Group by canonical alert type
    const batchedByType = new Map();

    for (const payload of rawAlerts) {
        const rawType = payload.title || payload.desc || payload.type || 'Rocket Alert';
        const type = normalizeAlertType(rawType);
        const legacyTitle = String(rawType || '').trim();
        const legacyCat = String(payload.cat || '').trim();
        const id = payload.id || Date.now().toString();
        const cities = Array.isArray(payload.cities)
            ? payload.cities
            : (Array.isArray(payload.data) ? payload.data : []);

        if (cities.length === 0) continue;

        if (!batchedByType.has(type)) {
            batchedByType.set(type, { id, cities: new Set(cities), legacyTitle, legacyCat });
        } else {
            const entry = batchedByType.get(type);
            cities.forEach(c => entry.cities.add(c));
            if (!entry.legacyTitle && legacyTitle) entry.legacyTitle = legacyTitle;
            if (!entry.legacyCat && legacyCat) entry.legacyCat = legacyCat;
        }
    }

    for (const [type, entry] of batchedByType) {
        const allCities = [...entry.cities];

        // Handle explicit clears — only dispatch for zones that are still armed
        if (type.toUpperCase().includes('CLEAR') || type.toUpperCase().includes('CALM')) {
            const newClears = dedup.filterNewClears(allCities);
            if (newClears.length > 0) {
                console.log(`🟢 CLEAR: ${newClears.length}/${allCities.length} armed zones for ${type}.`);
                sendFCMClear({
                    id: entry.id,
                    type,
                    cities: newClears,
                    legacyTitle: entry.legacyTitle || '',
                    legacyCat: entry.legacyCat || '',
                });
            }
            continue;
        }

        // Dedup: only dispatch zones not recently sent
        const newCities = dedup.filterNew(allCities, type);

        if (newCities.length > 0) {
            console.log(`🚨 DISPATCH: ${newCities.length} new zones for ${type} (${allCities.length} total).`);
            sendFCMAlert({
                id: entry.id,
                type,
                cities: newCities,
                legacyTitle: entry.legacyTitle || '',
                legacyCat: entry.legacyCat || '',
            });
        }
    }
}

// ── FCM Dispatch ────────────────────────────────────────────────────────
const FCM_CHUNK_SIZE = 100;

function sendFCMAlert(alertData) {
    const citiesList = alertData.cities || [];
    if (citiesList.length === 0) return;

    for (let i = 0; i < citiesList.length; i += FCM_CHUNK_SIZE) {
        const chunk = citiesList.slice(i, i + FCM_CHUNK_SIZE);
        const chunkIndex = Math.floor(i / FCM_CHUNK_SIZE) + 1;
        const totalChunks = Math.ceil(citiesList.length / FCM_CHUNK_SIZE);

        const message = {
            data: {
                alertId: String(alertData.id),
                type: String(alertData.legacyTitle || alertData.type),
                canonicalType: String(alertData.type || ''),
                legacyTitle: String(alertData.legacyTitle || ''),
                legacyCat: String(alertData.legacyCat || ''),
                schemaVersion: '2',
                classificationPath: 'backend_canonical',
                cities: JSON.stringify(chunk),
                chunkInfo: `${chunkIndex}/${totalChunks}`,
                is_dedup: 'true',
            },
            android: { priority: 'high', ttl: 60 * 1000 },
            topic: 'alerts',
        };

        admin.messaging().send(message)
            .then(res => console.log(`🚀 FCM OK (${chunkIndex}/${totalChunks}):`, res))
            .catch(err => console.error('❌ FCM Error:', err.message));
    }
}

function sendFCMClear(alertData = {}) {
    const citiesList = Array.isArray(alertData.cities) ? alertData.cities : [];
    const chunks = citiesList.length > 0
        ? Array.from(
            { length: Math.ceil(citiesList.length / FCM_CHUNK_SIZE) },
            (_, i) => citiesList.slice(i * FCM_CHUNK_SIZE, (i + 1) * FCM_CHUNK_SIZE)
          )
        : [[]];

    chunks.forEach((chunk, index) => {
        const hasZones = chunk.length > 0;
        const message = {
            data: {
                alertId: String(alertData.id || ''),
                type: 'CLEAR',
                canonicalType: 'CALM',
                legacyTitle: String(alertData.legacyTitle || ''),
                legacyCat: String(alertData.legacyCat || ''),
                schemaVersion: '2',
                classificationPath: 'backend_canonical',
                clearScope: hasZones ? 'zones' : 'global',
                time: new Date().toISOString(),
                is_dedup: 'true',
            },
            android: { priority: 'high', ttl: 60 * 1000 },
            topic: 'alerts',
        };

        if (hasZones) {
            message.data.cities = JSON.stringify(chunk);
            message.data.chunkInfo = `${index + 1}/${chunks.length}`;
        }

        admin.messaging().send(message)
            .then(() => console.log(`🚀 FCM Clear (${index + 1}/${chunks.length}).`))
            .catch(err => console.error('❌ FCM Clear error:', err.message));
    });
}

// ── Start ───────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
    console.log(`[relay] Listening on :${PORT}`);
    poll();
});
