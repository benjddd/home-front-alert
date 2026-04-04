# Release Notes — Tzeva Artzi 🚨

## v1.7.7 (2026-03-31) — High-Precision Alert Map & Clustering
- **Live Alert Map**: New MapLibre GL JS integration rendering a real-time dark-themed threat map directly in the app.
- **Architectural Split**: Map rendering offset to a new `homefront-map` Cloud Run microservice to protect the critical alert relay path from computation spikes.
- **Clustering Calibrated**: Utilizing March 2026 real-world data (DBSCAN 22km) to perfectly bucket multi-front salvos without overlap.
- **Dynamic Unification**: Intelligent concave hull and lightweight unions dependent on the number of zones in the salvo.
- **Attributions**: New "Thanks / Credits" page to explicitly credit `amitfin` for polygons and `dleshem` for data insights.
- **UI Architecture**: Shifted the main navigation to a ViewPager2 with tabs.
## v1.6.0 (2026-03-16) — FCM Fix + Infrastructure Consolidation

### Critical Fix
- **FCM delivery broken since initial setup** — the Android app was subscribed to Firebase project `home-front-alert` (old) while the backend was sending FCM to `home-front-alert-hfc` (new). Two different projects, messages never arrived. Fixed by registering the Android app in the correct Firebase project and updating `google-services.json`.

### New
- **Boot receiver** — `LocalPollingService` now restarts automatically after device reboot for Standard users and Pro users in Direct HFC mode
- **FCM token refresh** — `onNewToken` now re-subscribes to the `alerts` topic when Android rotates the FCM token (reinstall, token expiry), preventing silent delivery failures
- **`/test-fcm` endpoint** — API-key protected backend endpoint to manually trigger an FCM dispatch for end-to-end diagnostics

### Fixed
- Removed old `home-front-backend` Cloud Run service (Container type, scaled-to-zero, superseded by `homefront-backend`)
- Firebase Hosting rewrite corrected to `homefront-backend` (Cloud Build source, always-on with `min-instances=1`)
- Settings screen: removed duplicate mode toggle, unified diagnostics card, consistent FCM/Direct HFC mode switching
- `performInitialSetupIfNeeded` now correctly defaults Pro to FCM mode and Standard to Direct HFC

### Infrastructure
- Firebase project consolidated: everything now under `home-front-alert-hfc`
- `home-front-alert` Firebase project (old, orphaned) to be archived

---

## v1.3.3 (2026-03-13) — Internal Testing Launch
- Targets Android 15 (API 35) — Play Store compliant
- Firebase Cloud Messaging (FCM) integrated for push alerts
- Package renamed to `com.attius.homefrontalert`
- Status display: "NO ALERTS" (previously "SECURE")
- Terms of Service enforcement on first launch
- Hebrew & English language selection in Settings
- Help screen added to dashboard
- Advanced diagnostics section (collapsible)
- Baseline detection logic improvements
- GPS / manual location override in Settings

## v1.3.2 (2026-03-12) — Pre-release
- Package name: `com.attius.homefrontalert`
- Release keystore signed (Jerusalem, alias: homefront)
- Settings and Help screens localized

## v1.3.0 (earlier)
- Dashboard redesign with NO ALERTS / ALERT states
- Distance-based audio urgency system (pitch-mapped, 300–1500 Hz)
- PreferenceMigration for safe upgrades

## v1.7.6 (2026-03-26) — Closed Testing Promo
- **Closed Testing Transition**: Updated Play Store status from Internal to Closed Testing.
- **Join Instructions**: Added prominent instructions and links for Google Group and Play Store join flow.
- **Version alignment**: Unified versioning across documentation and build files.

---

## v1.7.5 (2026-03-20) — Play Store Automation & Resilience
- **Play Store Dashboard**: Automated tester recruitment and status dashboard.
- **Intelligent Connectivity**: Pro mode now automatically failsover to Direct HFC if FCM heartbeats are missed.
- **Android 14 Compliance**: Proper foreground service types and lifecycle labels.
- **UI Improvements**: Bilingual zone display and 10-minute alert summary.
- **Cleanup**: Housekeeping and stale branch pruning.
