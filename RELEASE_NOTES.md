# Release Notes — Tzeva Artzi 🚨

## v2.2.1 (2026-04-06) — Population Tooltip Fix

### Bug Fixes
- **Population data aggregation**: Fixed corrupted tooltip when zones had mismatched state/ctype (e.g., URGENT state with PRE_WARNING ctype from non-escalation overwrite). Unknown ctype values under URGENT are now normalized to known threat types.
- **Tooltip layout**: Replaced single pipe-separated line with structured per-line layout — each threat type on its own row with a colored bullet matching the donut chart arc, sorted by population. Eliminates arbitrary mid-label line breaks.
- **Safety-net labels**: Added fallback entries in population label dictionary for PRE_WARNING and URGENT keys to prevent raw internal strings from leaking to the UI.

### CI
- **R8 fix**: Root-caused `cleanDesktopInit` task's `projectDir.walkBottomUp()` causing I/O contention with R8 on CI runners. Task now skips on CI (where Google Drive `desktop.ini` files don't exist). Also consolidated duplicate packaging blocks and added META-INF resource excludes.

---

## v2.2.0 (2026-04-06) — Population-at-Risk Indicator & CI Pipeline

### Features
- **Population donut chart**: SVG donut on the alert map shows population under active alert/pre-warning, color-coded by threat type (rocket/UAV/infiltration/caution) with tap-to-tooltip.
- **CBS population data**: Enriched cities.json with Israel CBS population data (~10.18M total, scaled), via new `enrich_population.js` build script.
- **Clearing border fade**: Zone borders now fade alongside fill opacity during all-clear.

### CI/CD Pipeline (new)
- **Automated Play Store deploy**: Push a tag or trigger manually to build, sign, and upload AAB to internal testing — fully automated via GitHub Actions.
- **Workload Identity Federation**: Keyless GCP auth (no service account JSON keys), using `google-github-actions/auth` + `r0adkll/upload-google-play`.
- **R8 minification on CI**: Enabled for smaller APKs; disabled locally to avoid Google Drive path issues.
- **SHA-pinned actions**: All third-party GitHub Actions pinned to commit SHAs for supply-chain security.
- **Auto-sync main → develop**: New workflow merges main back to develop after each release.

### Housekeeping
- Retired local `serviceAccountKey.json` fallback; backend uses ADC everywhere.
- Fixed signing config env var shadowing that broke CI builds.
- Added `google-services.json` decode step (was missing, broke all prior CI runs).

---

## v2.1.0 (2026-04-05) — Stability, Security & CI Hardening

### Map Improvements
- **Pulsing stabilization**: Fixed erratic map pulse animations for active alert zones.
- **Visual fadeoff**: Alert zones now fade out gracefully when clearing, improving map readability during barrage wind-down.

### Security & Code Quality
- **v2.0.0 code review fixes**: Addressed all findings from the v2.0.0 security and code review (PR #20).
- **WebView hardening**: Tightened JS bridge, threat timeout, and map cleanup logic.
- **SwitchCompat fix**: Corrected missed SwitchCompat on `switchShowAdvanced`.
- **Error handling**: Widened `onThreatUpdate` try-catch for resilience.

### Infrastructure
- **CI: Pro flavor build**: Release workflow now builds `bundleProRelease` instead of the non-existent `bundleFreeRelease`.
- **gradlew permissions**: Restored executable permission on `gradlew` for CI runners.
- **Submodule cleanup**: Removed stale `Xiaomi-cloud-tokens-extractor` submodule tracking.

---


## v2.0.0 (2026-04-05) — Client-Driven Map, UI Overhaul & Alert System v3

### Client-Driven Real-Time Map
- **On-device threat map**: MapLibre GL JS renders a real-time dark-themed map entirely in-app via WebView — no server-side rendering required.
- **Custom basemap**: Replaced ESRI tile service with a custom dark canvas basemap using bundled GeoJSON country outline.
- **Heatmap + zone polygons**: Threat zones shown as filled polygons with a heatmap glow layer underneath; 4-state status badge (NO ALERTS / REMOTE THREAT / WARNING / CRITICAL).
- **Rich map data**: City dots, water bodies, and neighbor countries rendered from a single bundled FeatureCollection.
- **Visual polish**: UAV distinct color in modern threat palette, clearing fade animation, recent-zone highlight, app logo watermark.
- **Polygon cleanup**: Removed synthetic polygons (Dead Sea, Gulf of Eilat, Haifa Bay); reverted to clean Natural Earth outline.

### UI Architecture Overhaul
- **Tab navigation**: Main screen refactored from monolithic activity to ViewPager2 with Dashboard + Map tabs.
- **DashboardFragment**: Live alert status widget with elapsed countdown (native Chronometer), intelligent alert zone grouping (Top 10 Cities + Nearest Zones), capped at 5 items during barrages.
- **MapFragment**: Secure WebView bridge for app-to-map communication with bundled assets (HTML, CSS, JS, GeoJSON, polygons.zip).
- **Thanks/Credits page**: New activity crediting `amitfin` (polygons) and `dleshem` (data insights).

### Alert System v3
- **Data model spec v3**: Severity gate, CLEARING lifecycle, zone-scoped all-clear, and map refresh parity.
- **FCM CLEAR signal**: Immediate dashboard reset when all-clear is received via FCM.
- **AlertType.SILENT**: Silent alert support with proper clearAll() and XSS-safe JS injection.
- **Canonical + legacy compatibility**: Both canonical and legacy HFC payload formats supported.
- **Missing phrases**: Added all missing HFC pre-warning phrases to ALERT_TYPE_MAP.
- **Zone state alignment**: FCM and direct-HFC zone state behavior aligned.
- **Smart deduplication**: Configurable area-based TTL deduplication to suppress duplicate alerts.

### Backend Minimization
- **Minimal relay architecture**: Backend stripped to lightweight alert relay — map-server, threatManager, polygonCache, mapState all removed.
- **Client-driven rendering**: All map rendering moved to the Android client via JS bridge with bundled assets.
- **Geometry stripping**: Backend strips geometry from payloads to reduce transfer size.
- **Map payload caching**: Optimized geometry operations for large multi-zone alerts.
- **Deduplication module**: Extracted configurable dedup logic to standalone `dedup.js`.

### Audio Engine
- **Sample-exact synthesis**: Dynamic duration capping (250ms/zone), Whisper/Wobble/Two-note resolve decisions.
- **Sound test**: New Sound Test section in Settings diagnostics (100/600 zone scenarios).
- **Silent URGENT fix**: Restored silent URGENT alerts by waiting for playback completion.
- **Single zone pip**: Shortened to 125ms for snappier feedback.

### Smart Home Integration
- **Mi smart bulb / Yeelight**: Experimental integration for visual alert feedback via smart bulbs.

### Security
- **WebView bridge hardening**: Threat timeout, chunk timer guards, and map cleanup on navigation.
- **Rotation-safe auth**: Backend auth secret support survives key rotation.
- **Public repo hardening**: Removed emails, added CONTRIBUTING.md and LICENSE.
- **Code review fixes**: Addressed all findings from PR #18 security review.

### Infrastructure & Housekeeping
- **Deploy memory**: Bumped to 512Mi for no-cpu-throttling requirement.
- **Cloud Run guard**: Backend string extraction guarded on Cloud Run environment.
- **Notification channels**: Split into separate channels with responsive scalable widget strategy.
- **Build cleanup**: Removed compiled APK from repo, hardened .gitignore, ignored local utility scripts.
- **Centralized config**: StatusManager SSOT helper, AlertColors single source of truth across all surfaces.

---

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
