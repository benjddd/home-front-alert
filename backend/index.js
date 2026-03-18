const admin = require('firebase-admin');
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');

const app = express();
app.set('trust proxy', 1); // Trust Cloud Run Load Balancer

// Security Hardening
app.use(helmet()); // Sets various secure HTTP headers
app.use(express.json());

// Strict Rate Limiting: 100 requests per 15 minutes
const limiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    message: "Too many requests from this IP, please try again later."
});
app.use(limiter);

// Strict Access Control: Use a private API Key
app.use((req, res, next) => {
    const apiKey = req.headers['x-api-key'];
    const validKey = process.env.API_KEY || "DEVELOPMENT_MODE_UNSET"; 
    
    // Health and Alerts check
    const isSensitive = req.path === '/alerts';
    
    // Health and Privacy check is public
    if (req.path === '/health' || req.path === '/privacy') return next();

    // Block sensitive endpoints if key is missing or wrong
    if (isSensitive && apiKey !== validKey) {
        return res.status(403).json({ error: "Forbidden: Valid API Key required" });
    }
    next();
});

app.use(cors({
    origin: '*', // We now rely on User-Agent and Rate Limiting for security
    methods: ['GET'],
    allowedHeaders: ['Content-Type', 'Authorization', 'User-Agent', 'X-API-Key']
}));

// Track the last alert globally for persistent history reporting
let lastDetectedAlert = null;

// Expose a public /health endpoint for the Android App to check status
app.get('/health', (req, res) => {
    res.status(200).json({
        status: 'ok',
        connectedToHomeFront: isConnected,
        source: activeSource,
        lastReportedAlert: lastDetectedAlert,
        timestamp: new Date().toISOString()
    });
});

// Privacy Policy Route
app.get('/privacy', (req, res) => {
    res.sendFile(__dirname + '/public/privacy.html');
});

// Expose the latest active alert for the Android App's Hybrid Polling mode
let currentAlert = null;
app.get('/alerts', (req, res) => {
    res.json({
        active: currentAlert || {},
        system: {
            connected: isConnected,
            source: activeSource,
            last_sync: lastSuccessfulPoll
        }
    });
});

// History endpoint for proof of delivery
app.get('/alerts/history', (req, res) => {
    res.json(lastDetectedAlert ? [lastDetectedAlert] : []);
});

// Test FCM endpoint — fires a synthetic alert to confirm end-to-end FCM delivery.
// API-key protected. Use: curl -X POST -H 'X-API-Key: <key>' https://.../test-fcm
app.post('/test-fcm', (req, res) => {
    const apiKey = process.env.API_KEY || 'DEVELOPMENT_MODE_UNSET';
    if (req.headers['x-api-key'] !== apiKey) {
        return res.status(403).json({ error: 'Forbidden' });
    }
    const testAlert = {
        id: 'TEST_' + Date.now(),
        type: 'Test Alert / בדיקה',
        cities: ['בדיקה — FCM Test']
    };
    console.log('🧪 Manual /test-fcm triggered — sending FCM now');
    sendFCMAlert(testAlert);
    res.json({ ok: true, alertId: testAlert.id, time: new Date().toISOString() });
});



const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
    console.log(`Backend Server listening on port ${PORT}`);
});

// Initialize Firebase Admin (Application Default Credentials on Cloud Run)
try {
    if (process.env.FIREBASE_SERVICE_ACCOUNT) {
        const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
        admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
        console.log("🔥 Firebase: Initialized via Environment Variable.");
    } else {
        // This will work automatically on Cloud Run using the Service Account
        admin.initializeApp({ credential: admin.credential.applicationDefault() });
        console.log("🔥 Firebase: Initialized via Application Default Credentials.");
    }
} catch (e) {
    console.warn("⚠️ Firebase: Falling back to local file check...");
    try {
        admin.initializeApp({ credential: admin.credential.cert(require('./serviceAccountKey.json')) });
        console.log("🔥 Firebase: Initialized via local serviceAccountKey.json.");
    } catch (e2) {
        console.error("❌ Firebase: Initialization failed.", e2.message);
    }
}

// Polling configuration
const interval = 1000; // Increased frequency for sub-second trailing
const HFC_API_URL = 'https://www.oref.org.il/WarningMessages/alert/Alerts.json';
const HFC_HEADERS = {
    'User-Agent': 'PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)',
    'Referer': 'https://www.oref.org.il/',
    'X-Requested-With': 'XMLHttpRequest',
    'Accept': 'application/json, text/javascript, */*; q=0.01',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache'
};

let isConnected = false;
let activeSource = "None";
let lastErrors = [];
let lastSuccessfulPoll = null;

console.log("Starting Home Front Command Native Poller (Axios)...");

const poll = async function () {
    try {
        let data = null;
        let source = "None";
        let errors = [];

        // 1. Try Official API (Native Axios call)
        try {
            const hfcRes = await axios.get(HFC_API_URL, {
                headers: HFC_HEADERS,
                timeout: 3000,
                validateStatus: s => s === 200 || s === 204
            });
            
            // HFC returns 204 No Content when there are no alerts
            if (hfcRes.status === 204 || !hfcRes.data) {
                data = []; // No active alerts
            } else {
                data = hfcRes.data;
            }
            source = "Official API";
        } catch (e) {
            errors.push(`Official: ${e.message}`);
        }

        // 2. Fallback to Mirror A (Pakar API)
        if (data === null) {
            try {
                const res = await axios.get('https://api.pakar.ivr2.tel/alerts', {
                    timeout: 5000,
                    headers: HFC_HEADERS,
                    validateStatus: s => s === 200
                });
                if (res.data && typeof res.data === 'object') {
                    data = res.data;
                    source = "Mirror A (Pakar)";
                } else {
                    errors.push(`Mirror A: Invalid format`);
                }
            } catch (e) {
                errors.push(`Mirror A: ${e.message}`);
            }
        }

        // 3. Fallback to Mirror B (Tzeva Adom Hist)
        if (data === null) {
            try {
                const res = await axios.get('https://www.tzevaadom.co.il/static/historical/all.json', {
                    timeout: 5000,
                    headers: HFC_HEADERS,
                    validateStatus: s => s === 200
                });
                // Take the most recent alert if it's very recent (less than 30s old)
                if (Array.isArray(res.data) && res.data.length > 0) {
                    const latest = res.data[0];
                    const alertTime = new Date(latest.alertDate).getTime();
                    const now = Date.now();
                    // Show as active if within last 60 seconds
                    if (now - alertTime < 60000) {
                        data = latest;
                        source = "Mirror B (History Feed)";
                    } else {
                        // Return empty array to signify "no current alerts" but connected
                        data = [];
                        source = "Mirror B (Connected)";
                    }
                } else {
                    errors.push(`Mirror B: Empty or malformed`);
                }
            } catch (e) {
                errors.push(`Mirror B: ${e.message}`);
            }
        }


        // Process results
        if (data !== null) {
            activeSource = source;
            lastSuccessfulPoll = new Date().toISOString();
            isConnected = true; // Mark as connected since we got a valid response (even if data is empty)
            handleSuccessfulConnection(data, source);
        } else {
            if (isConnected) {
                console.warn("⚠️ DISCONNECTED from all sources. Errors:", errors.join(" | "));
            }
            isConnected = false;
            activeSource = "None";
        }
        lastErrors = errors;

    } catch (e) {
        console.error("CRITICAL Poller Error:", e.message);
        isConnected = false;
        activeSource = "None"; // No active source if critical error
    } finally {
        // Tight loop: 1s pause between requests
        setTimeout(poll, interval);
    }
};

let alertExpirationTimer = null;

function handleSuccessfulConnection(data, source) {
    if (!isConnected) {
        console.log(`✅ Connection stable via ${source}.`);
    }
    isConnected = true;

    // Check for empty data (no active alerts)
    const isEmpty = !data ||
        (Array.isArray(data) && data.length === 0) ||
        (typeof data === 'object' && Object.keys(data).length === 0);

    if (isEmpty) {
        if (currentAlert) {
            console.log("Alert state cleared: API is now empty.");
            currentAlert = null;
        }
        if (Math.random() < 0.02) { // Periodic heartbeat log (2% of polls)
            console.log(`[${new Date().toLocaleTimeString()}] Heartbeat: Watching via ${source}`);
        }
        return;
    }

    // If we reach here, we have a REAL alert payload
    const payloadStr = JSON.stringify(data);
    
    // Canonicalize
    const normalizedAlert = {
        id: data.id || Date.now().toString(),
        type: data.title || data.desc || data.type || "Rocket Alert",
        cities: Array.isArray(data.cities) ? data.cities : (Array.isArray(data.data) ? data.data : []),
        raw: data
    };

    if (normalizedAlert.cities.length > 0) {
        // Backend Deduplication: Only dispatch if City List or ID changed
        const currentCitiesKey = normalizedAlert.cities.sort().join('|');
        const lastCitiesKey = lastDetectedAlert ? lastDetectedAlert.cities.sort().join('|') : "";
        
        if (normalizedAlert.id !== (lastDetectedAlert ? lastDetectedAlert.id : "") || currentCitiesKey !== lastCitiesKey) {
            console.log(`🚨 DISPATCHING [${source}]: ${normalizedAlert.cities.length} cities`);
            handleAlertDispatch(normalizedAlert);
        } else {
            // Heartbeat for existing alert
            if (Math.random() < 0.05) console.log(`... Alert ${normalizedAlert.id} still active (suppressing redundant FCM)`);
        }

        // State maintenance for /alerts endpoint (Hybrid Polling)
        currentAlert = normalizedAlert;
        if (alertExpirationTimer) clearTimeout(alertExpirationTimer);
        alertExpirationTimer = setTimeout(() => {
            console.log("Current alert state cleared (90s).");
            currentAlert = null;
        }, 90000);
    }
}

function handleAlertDispatch(alert) {
    currentAlert = alert;
    lastDetectedAlert = {
        ...alert,
        serverTime: new Date().toISOString()
    };
    console.log('Pushing Notification for:', alert.cities.join(', '));
    sendFCMAlert(alert);
}

function sendFCMAlert(alertData) {
    const CHUNK_SIZE = 100; // Safe threshold for 4KB limit
    const citiesList = alertData.cities || [];
    
    if (citiesList.length === 0) return;

    // Send the array in chunks to avoid "FCM Broadcast Error: Android message is too big"
    for (let i = 0; i < citiesList.length; i += CHUNK_SIZE) {
        const chunk = citiesList.slice(i, i + CHUNK_SIZE);
        const chunkIndex = Math.floor(i / CHUNK_SIZE) + 1;
        const totalChunks = Math.ceil(citiesList.length / CHUNK_SIZE);

        const message = {
            data: {
                alertId: String(alertData.id),
                type: String(alertData.type),
                cities: JSON.stringify(chunk),
                chunkInfo: String(`${chunkIndex}/${totalChunks}`)
            },
            android: {
                priority: 'high',
                ttl: 60 * 1000 // 60 seconds
            },
            topic: 'alerts'
        };

        admin.messaging().send(message)
            .then(res => console.log(`🚀 FCM Broadcast Success (Chunk ${chunkIndex}/${totalChunks}):`, res))
            .catch(err => console.error(`❌ FCM Broadcast Error (Chunk ${chunkIndex}/${totalChunks}):`, err.message));
    }
}

// Global crash handlers
process.on('unhandledRejection', (reason, promise) => {
    console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

// FCM Keepalive Heartbeat for Auto-Failover
// Emits a silent KEEPALIVE message to the 'alerts' topic every 10 minutes
setInterval(() => {
    if (isConnected) {
        console.log(`💓 Emitting KEEPALIVE heartbeat to 'alerts' topic...`);
        const keepaliveMessage = {
            data: {
                type: 'KEEPALIVE',
                time: new Date().toISOString()
            },
            android: {
                priority: 'normal',
                ttl: 60 * 1000 // 60 seconds
            },
            topic: 'alerts'
        };

        admin.messaging().send(keepaliveMessage)
            .then(res => console.log('💓 KEEPALIVE Success:', res))
            .catch(err => console.error('❌ KEEPALIVE Error:', err.message));
    } else {
        console.log(`💔 Skipping KEEPALIVE (Backend disconnected from HFC)`);
    }
}, 10 * 60 * 1000); // 10 minutes

// Start polling
poll();
