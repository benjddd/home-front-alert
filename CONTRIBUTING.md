# Contributing to Home Front Alerts

## Project Overview

Home Front Alerts is a real-time Israeli Home Front Command (HFC) alert system. It consists of:

| Component | Stack | Location |
|---|---|---|
| Android app | Kotlin, FCM | `android-app/` |
| Alert relay + FCM dispatcher | Node.js, Express, Firebase Admin | `backend/index.js` |
| Map visualization service | Node.js, Express, MapLibre | `backend/map-server.js` |
| Firebase Hosting proxy | Firebase | `proxy-microsite/` |

The backend runs on **Google Cloud Run**. The Android app receives push notifications via **Firebase Cloud Messaging (FCM)**. Alert data is polled from the official HFC API.

---

## Branch Strategy

| Branch | Purpose |
|---|---|
| `main` | Stable production code — PRs only, no direct pushes |
| `develop` | Integration branch for features |
| `feature/*` | Individual feature work, branched from `develop` |
| `hotfix/*` | Urgent production fixes, branched from `main` |
| `release/x.y.z` | Release candidate, branched from `develop` |

---

## Local Development Setup

### Android App

1. Open `android-app/` in Android Studio (Hedgehog or later, JDK 17)
2. Ensure `android-app/local.properties` has your SDK path (auto-created by Android Studio, not committed)
3. The app requires `google-services.json` in `android-app/app/` — obtain it from the Firebase project (not committed)
4. Build: `./gradlew assembleDebug` from `android-app/`

### Backend

```bash
cd backend
npm install
cp .env.example .env        # fill in real values — see .env.example
node index.js               # HFC relay on :8080
node map-server.js          # Map service on :8080 (run separately)
```

> The backend requires a Firebase service account JSON. Either set `FIREBASE_SERVICE_ACCOUNT` as a JSON string in `.env`, or place the file as `backend/serviceAccountKey.json` (already gitignored).

### Firebase Hosting (proxy-microsite)

```bash
cd proxy-microsite
npm install -g firebase-tools
firebase login
firebase deploy --only hosting --project=home-front-alert-hfc
```

---

## Secrets and Environment Variables

**Never hardcode secrets.** All sensitive values must be:
- Stored in `.env` locally (gitignored)
- Stored in **GitHub Secrets** for CI/CD
- Stored in **Cloud Run environment variables** for production

See `backend/.env.example` for the full list of required variables.

---

## CI/CD

GitHub Actions workflow at `.github/workflows/release.yml` builds and publishes a signed Android release bundle to the Play Store on version tags (`v*.*.*`). Required GitHub Secrets:

- `KEYSTORE_BASE64` — base64-encoded release keystore
- `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — signing credentials
- `PLAY_SERVICE_ACCOUNT_JSON` — Google Play service account

---

## Pull Request Guidelines

- Branch from `develop` (or `main` for hotfixes)
- Keep PRs focused — one logical change per PR
- Ensure the Android app builds (`./gradlew assembleDebug`) before opening a PR
- Write a clear PR description explaining *why*, not just *what*
- PRs to `main` require review

---

## Code Style

- **Kotlin**: follow Android Kotlin conventions; no unused imports
- **Node.js**: `'use strict'`; async/await over callbacks; descriptive variable names
- **No hardcoded secrets, IPs, or credentials anywhere in source**
