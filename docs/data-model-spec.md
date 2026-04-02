# Data Model & Business Logic Specification (v3)

## Verified Against v1.7.6 Release Code

All v1.7.6 behaviors are preserved. This spec extends, never drops.

---

## The Model: Zone-Centric Map

```json
// SharedPreferences: "active_threat_map"
// Key: normalizeCity(zoneName)
{
  "תלאביבמזרח": {
    "t": 1712100000000,
    "s": "URGENT",
    "c": 45,
    "name": "תל אביב - מזרח",
    "ct": null
  }
}
```

| Field | Type | Purpose |
|-------|------|---------|
| `t` | Long | Last alert timestamp. Refreshed on every duplicate. |
| `s` | String | `CAUTION`, `URGENT`, or `CLEARING` |
| `c` | Int | Countdown seconds to shelter |
| `name` | String | Raw HFC display name |
| `ct` | Long? | Cleared-at timestamp. Null while active. Set on explicit CALM. |

---

## Per-Zone State Machine

```
(not in map)
    │
    ├── CAUTION alert ──→ CAUTION
    │                       ├── same/lower type → refresh t only (no sound, no status change)
    │                       ├── URGENT alert    → escalate s to URGENT (sound YES)
    │                       ├── explicit CALM   → s=CLEARING, ct=now
    │                       └── 30-min timeout  → removed silently (no sound)
    │
    └── URGENT alert ───→ URGENT
                            ├── any type ≤ URGENT → refresh t only (no sound, no status change)
                            ├── explicit CALM     → s=CLEARING, ct=now
                            └── 30-min timeout    → removed silently (no sound)

CLEARING
    ├── fade TTL (5 min from ct) → removed
    └── new CAUTION/URGENT alert → re-activate (s=new type, ct=null)
```

**Key rules:**
- Severity only goes up: CAUTION → URGENT. Never down.
- Duplicate = same zone already in map at same or higher severity → refresh `t`, nothing else.
- Escalation = same zone, higher severity → update `s` to URGENT, treated as "new" for audio.
- Explicit CALM → transition to CLEARING (not immediate removal — **new vs v1.7.6**).
- 30-min timeout → remove silently, **no sound** (this is a failover, not an all-clear).
- 30-min timeout → **does** trigger status recalculation → if no more active zones, status → GREEN → **bulb turns off**.

---

## A. Deduplication

### v1.7.6 behavior (PRESERVED)

1. **Per-alert-ID tracking** (`signaledCitiesPerAlert`):
   - In-memory `Map<alertId, Set<normalizedZone>>`
   - On `processAlert()`: `newCitiesForAudio = cities - signaledSet[id]`
   - Only new cities produce sound
   - Survives chunked delivery (same alertId across chunks)

2. **Threat map upsert** (always happens regardless of audio):
   - Every city in the incoming alert gets its `t` refreshed in the map
   - This keeps the zone alive (prevents premature timeout)
   - No sound, no status change for zone already at same/higher severity

### New addition: one-way severity gate

v1.7.6 does `obj.put("s", type.name)` unconditionally — a CAUTION alert after an URGENT overwrites the zone's severity. Fix:

```kotlin
val existing = threats.optJSONObject(normZone)
val incomingSeverity = type.ordinal  // CAUTION=0, URGENT=1
val existingSeverity = if (existing != null) AlertType.valueOf(existing.optString("s", "CAUTION")).ordinal else -1

if (incomingSeverity > existingSeverity) {
    // Escalation: update severity, treat as "new" for audio
    obj.put("s", type.name)
} else if (existing != null) {
    // Same or lower severity: keep existing, just refresh timestamp
    obj.put("s", existing.optString("s", type.name))
}
```

### What constitutes a duplicate (summary)

| Zone in map? | Incoming severity vs existing | Action |
|---|---|---|
| No | N/A (new zone) | Add to map, **sound YES** |
| Yes at CAUTION | CAUTION (same) | Refresh `t`, **sound NO** |
| Yes at CAUTION | URGENT (higher) | Escalate `s`, **sound YES** |
| Yes at URGENT | CAUTION (lower) | Refresh `t`, keep URGENT, **sound NO** |
| Yes at URGENT | URGENT (same) | Refresh `t`, **sound NO** |
| Yes at CLEARING | Any threat | Re-activate (ct=null, set `s`), **sound YES** |

---

## B. Sound Triggers (v1.7.6 behavior PRESERVED, annotated)

### Decision tree in processAlert()

```
1. type == CALM?
   → Check if home zone is in the cleared zones (using full list, not delta)
   → YES: play resolve tone (330→523 Hz)
   → NO: no sound

2. type == CAUTION or URGENT, with delta?
   → Compute newCitiesForAudio (cities not yet signaled for this alertId)

   2a. Home zone already signaled for this alertId?
       → YES: suppress audio (local siren already played for this alert)

   2b. Home zone is in the delta (newCitiesForAudio)?
       → YES: escalate to LOCAL siren (full playback with isLocal=true)

   2c. Neither 2a nor 2b?
       → Play REMOTE audio based on distances to new cities
       → Special case: CAUTION with empty delta still plays (using full distances)

3. No delta AND type != CAUTION?
   → No sound (purely redundant broadcast)
```

### Sound profiles (unchanged)

| Type + Local | Sound |
|---|---|
| URGENT remote | Whisper: triangle wave, 420-780 Hz, ~125ms/zone, far→near |
| URGENT local | Same whisper + local emphasis |
| CAUTION remote | 3-beat wobble: 392/466 Hz, 6Hz LFO |
| CAUTION local | 5-beat wobble: 340/440 Hz, 9Hz LFO |
| CALM (home zone active) | Two-note resolve: 330Hz→523Hz |
| 30-min timeout | **No sound** |

### Volume scaling (unchanged)
- Quadratic curve: `volume²`
- Zone count scaling: `min(1.0, 5 / sqrt(max(5, zoneCount)))` — handles 600+ zones gracefully (600 zones ≈ 20% volume)
- CALM gets 2x boost (min 50%)

---

## C. Map Strategy: Drop-and-Redraw

### Why drop-and-redraw (not incremental update)

Even with 600+ zones, generating GeoJSON from lat/lng coordinates is sub-millisecond. MapLibre's `setData()` on a GeoJSON source is designed for full replacement. Incremental updates require tracking "what changed since last push" — a source of stale-state bugs that adds complexity for negligible performance gain.

### Three layers

| Layer | Source | Color | Opacity |
|---|---|---|---|
| URGENT zones | `s == "URGENT"` | Red | 1.0 |
| CAUTION zones | `s == "CAUTION"` | Amber | 1.0 |
| CLEARING zones | `s == "CLEARING" && now - ct < fadeTTL` | Green | `1.0 - (now - ct) / fadeTTL` |

### When to redraw

| Trigger | Why |
|---|---|
| After every `processAlert()` → `recalculateStatus()` | New data |
| Periodic timer (every 30s) | Advance CLEARING fades, expire old zones |
| On app resume | Catch up after background |

### Data flow

```
recalculateStatus() — prunes expired zones, persists cleaned map
       ↓
  Build 3 GeoJSON FeatureCollections from zone map
       ↓
  Push to MapFragment via JS bridge:
    setAlertData(urgentJSON, cautionJSON, clearingJSON)
       ↓
  MapLibre replaces source data, re-renders
```

---

## D. Recent 10-Minute Count and Distance

### Computation (on every dashboard refresh, not cached)

```kotlin
fun getActiveThreatsSnapshot(context: Context): ActiveThreatsSnapshot {
    for each zone in active_threat_map:
        if s == "CLEARING" → skip
        if (now - t) > 10 minutes → skip
        count++
        calculate distance → track minimum
        if zone == homeZone → compute localRemaining (c - elapsed)

    return ActiveThreatsSnapshot(count, closestDist, localRemaining, ...)
}
```

### CLEARING zones excluded from 10-min count
A zone that received explicit CALM is no longer an "active threat" — it shouldn't inflate the count or affect closest distance. The map shows it as "released" but the dashboard metrics ignore it.

### The 10-min / 30-min gap (by design)
- 10 min = "recent activity" (informational, dashboard card)
- 30 min = "safety window" (status color, zone stays in map)
- A zone at 25 minutes: contributes YELLOW status, but NOT to "X alerts active"
- This is correct and intentional — GREEN requires all threats expired OR cleared

---

## E. Overall Status Derivation (v1.7.6 PRESERVED)

```
Scan zone map, EXCLUDE entries where s == "CLEARING":

  Empty → GREEN
  Non-empty → YELLOW (baseline)

  For each zone matching homeZone:
    s == "URGENT" → RED (break)
    s == "CAUTION" → ORANGE (continue, may find RED)

  Return highest
```

No change from v1.7.6 except CLEARING entries are skipped (they didn't exist before).

---

## F. Smart Bulb (Reflects Overall Status)

| Status change | Bulb action |
|---|---|
| → GREEN (from any) | Relaxation color flow → auto-off after 10s |
| → YELLOW | Amber steady |
| → ORANGE | Orange pulse |
| → RED | Red rapid pulse (30 repeats) |
| 30-min timeout → GREEN | **Bulb turns off** (via status → GREEN → bulb off path) |

Bulb is triggered by `recalculateStatus()` detecting a status transition. No direct bulb call from timeout logic — the existing status derivation handles it: expired zones are pruned → no zones left → GREEN → bulb off.

---

## G. Both Delivery Modes Covered

### FCM path (PRO)
```
FCM → onMessageReceived()
  → KEEPALIVE: record heartbeat, return
  → CLEAR: processAlert(CALM)
  → Chunked: buffer, reassemble (2s timeout), then processAlert()
  → Single: processAlert() directly
```

### Direct HFC path (Shield / free)
```
LocalPollingService → runPollCycle() every 2s
  → HTTP GET oref.org.il
  → handlePollResult()
  → Extract: cat, title, cities/data
  → AlertStyleRegistry.getStyle(cat, title) → AlertType
  → processAlert()
```

Both paths converge on the same `processAlert()`. All logic described above applies identically regardless of delivery path.

### FCM-specific: chunk reassembly
- Cities split into 100-city chunks on backend
- Each chunk: same alertId, chunkInfo "N/M"
- Android buffers chunks, processes when all arrive OR after 2s timeout
- With 600+ zones: ~6 chunks, all processed together or in 2s window

### Direct HFC-specific: single payload
- No chunking needed (direct HTTP response)
- Alert arrives as one object with all cities
- Parsed and passed to `processAlert()` in one call

---

## Summary: What's New vs v1.7.6

| # | Change | Why |
|---|---|---|
| 1 | **One-way severity escalation** | v1.7.6 overwrites `s` unconditionally. Fix: never downgrade URGENT→CAUTION from duplicate alerts with overlapping zones |
| 2 | **CLEARING state** | v1.7.6 removes zones on CALM. New: transition to CLEARING with `ct` so the map can show "released" areas. Status/audio/count ignore CLEARING zones. |
| 3 | **30-min timeout shuts off bulb** | Already works via: timeout prunes zones → recalculateStatus() → GREEN → bulb off. Just confirming: no sound on timeout, yes bulb off. |

Everything else — audio decision tree, dedup strategy, status derivation, notification tiers, dashboard metrics, FCM/direct poll convergence — is v1.7.6 behavior preserved exactly.
