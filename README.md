# Tzeva Artzi 🚨

[![Release](https://img.shields.io/github/v/release/Attius-Digital-Art/home-front-alert?label=version)](https://github.com/Attius-Digital-Art/home-front-alert/releases)
[![Closed Testing](https://img.shields.io/badge/Play%20Store-Closed%20Testing-blue)](https://play.google.com/store/apps/details?id=com.attius.homefrontalert)

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **Home Front Command alerts for Android — pitch and pattern tell you everything, eyes-free.**  
> קצב הצליל ותדרו אומרים לך מה קורה — ללא צורך להסתכל על הטלפון.

---

## What Makes This Different

Most alert apps play the same loud beep for every event, everywhere. Tzeva Artzi tells you *what* and *how close* through audio alone:

- 🔊 **Pitch = Distance** — Alert tone pitch is calculated from your GPS position to the alerted zones. Low pitch means it's near you; high pitch means it's far away. Instantly intuitive while driving or in a noisy environment.
- 🎵 **Pattern = Threat Type** — Three distinct audio patterns: **URGENT** (rockets / UAVs / infiltrators), **CAUTION** (early warning / approximate), **CALM** (all-clear). You know what's happening without looking.
- 🚗 **Designed for Eyes-Free Use** — Ideal while driving, in a meeting, or with the phone in your pocket. The sound conveys urgency and distance context in under a second.
- 📍 **GPS-Aware from First Second** — Fused Location Provider with a saved fallback zone. Even without live GPS, you always hear a contextually-relevant tone.
- 🔕 **Silent Delivery, Loud Alert** — Uses Android's `STREAM_ALARM` channel directly. No notification badges, no shade clutter. Just sound.
- 🌍 **Hebrew & English** — Full bilingual support in UI and zone names.
- ⚡ **Two Delivery Modes** — Standard (direct HFC polling) and Pro (Intelligent failover: uses instant FCM by default, but auto-switches to Direct HFC if it detects an outage).

---

## Alert Audio Logic

| Alert Type | Meaning | Pitch | Pattern |
|---|---|---|---|
| `URGENT` | Rocket / UAV / Infiltrator | Distance-mapped (300–1500 Hz) | Rapid pulsing tones per zone |
| `CAUTION` | Early warning / approximate | Fixed mid-range | Slower advisory pattern |
| `CALM` | All-clear / incident over | Fixed low | Single resolving tone |

Frequencies: 300 Hz = 0 km away, 1500 Hz = 500 km away. Linear interpolation per zone.

---

## Two Delivery Modes

### Standard — Direct HFC
- Polls Israel Home Front Command API directly from the device, every 2 seconds
- Runs as a persistent Android foreground service (survives screen-off, doze)
- Restarts automatically after device reboot
- No backend or internet infrastructure dependency beyond the HFC API
- Default for Standard flavor

### Pro — Instant FCM / Intelligent (Recommended)
- **Primary Delivery**: Backend (Cloud Run) polls HFC and dispatches Firebase push the moment an alert is detected. Zero battery drain on device while idle.
- **Failover Resilience**: Uses a 10-minute "Heartbeat" (KEEPALIVE) to monitor connectivity.
- **Automatic Shield**: If the heartbeat is missed for 20+ minutes, the app automatically activates **Direct HFC Shield** (polling) to ensure safety until the connection recovers.
- **Auto-Recovery**: If a valid FCM message is received while in failover, the app automatically switches back to cloud mode to save battery.
---

## Join the Testing Program (Closed Beta) 🚀

Help us test the app before its public release! Follow these steps to join:

1.  **Join the Google Group**: [https://groups.google.com/g/tzeva-artzi](https://groups.google.com/g/tzeva-artzi)
    *   *Alternatively, send an empty email to:* `tzeva-artzi+subscribe@googlegroups.com`
2.  **Download on Android**: [Play Store Link](https://play.google.com/store/apps/details?id=com.attius.homefrontalert)
    *   *Note: The Store link will only work AFTER you have joined the Google Group.*

---
- Manual override available in Settings → Connectivity Mode.

---

## Architecture

```
android-app/                        # Android Kotlin app (com.attius.homefrontalert)
├── MainActivity.kt                 # Dashboard & alert status display
├── LocalPollingService.kt          # Direct HFC API polling (Standard mode)
├── MyFirebaseMessagingService.kt   # FCM push handler (Pro mode)
├── BootReceiver.kt                 # Restarts LocalPollingService after device reboot
├── DynamicToneGenerator.kt         # Synthesizes distance-mapped audio tones (44.1kHz)
├── ZoneDistanceCalculator.kt       # GPS → Haversine distance to alerted zones
├── AlertStyleRegistry.kt           # Maps HFC category strings → URGENT/CAUTION/CALM
├── StatusManager.kt                # Stateful threat map (30-min zone persistence)
├── SettingsActivity.kt             # GPS, volume, mode selection, diagnostics
├── AppLocationManager.kt           # Fused Location Provider + saved fallback zone
└── TOSActivity.kt                  # Terms of Service (first launch)

backend/                            # Node.js — Google Cloud Run (Pro tier)
└── index.js                        # HFC relay: polls API, dispatches FCM to 'alerts' topic

proxy-microsite/                    # Firebase Hosting config
└── firebase.json                   # Routes *.web.app → Cloud Run backend
```

**Infrastructure (GCP project: `home-front-alert-hfc`):**
- Cloud Run: `homefront-backend`, region `me-west1`, `min-instances=1`, no CPU throttling
- Firebase Hosting: `home-front-alert-hfc.web.app` → `homefront-backend`
- Firebase Cloud Messaging: topic `alerts`, project `home-front-alert-hfc`

---

## Build & Run

### Prerequisites
- Android Studio Meerkat or newer
- JDK 17
- `google-services.json` in `android-app/app/` — download from [Firebase Console → home-front-alert-hfc](https://console.firebase.google.com/project/home-front-alert-hfc/settings/general/)

### Debug Builds
```bash
cd android-app
./gradlew assembleStandardDebug   # Standard flavor (Direct HFC)
./gradlew assembleProDebug        # Pro flavor (FCM)
```

### Release Builds
```bash
cd android-app
./gradlew assembleStandardRelease
./gradlew assembleProRelease
```
> Requires `release.jks` in `android-app/` — **never committed to git**.

### Backend Deploy
```bash
cd backend
gcloud run deploy homefront-backend \
  --source . \
  --region=me-west1 \
  --project=home-front-alert-hfc \
  --allow-unauthenticated \
  --min-instances=1 \
  --no-cpu-throttling
```

### Firebase Hosting Deploy
```bash
cd proxy-microsite
firebase deploy --only hosting --project=home-front-alert-hfc
```
> Requires Firebase CLI login as `homefrontcommand@attius.com`.

---

## Branch Strategy

| Branch | Purpose |
|---|---|
| `main` | Stable production code. Protected. PRs only. |
| `develop` | Integration branch for features |
| `feature/*` | Individual feature work |
| `hotfix/*` | Urgent production fixes |
| `release/x.y.z` | Release candidate — freeze, test, tag |

---

## Security Notes

- ❌ `release.jks` is **never** committed. Store in 1Password + GitHub Secret.
- ✅ `google-services.json` contains no private secrets (public Firebase config only).
- ✅ Backend API key required for `/test-fcm` endpoint.
- ✅ ProGuard/R8 minification active on release builds.
- ✅ Cloud Run IAM: unauthenticated read only; admin endpoints require `X-API-Key` header.

---

## Privacy Policy

[View Privacy Policy](https://home-front-alert-hfc.web.app/privacy.html)

---

## License

MIT License — see [LICENSE](LICENSE).

---

## Disclaimer

This app is an **independent, unofficial** companion to the Israel Home Front Command's alert system.  
It is **not affiliated** with the IDF, Pikud HaOref, or any government body.  
**Always follow official instructions during an emergency.**
