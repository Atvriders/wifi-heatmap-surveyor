# WiFi Heatmap Surveyor — Android APK (new project)

## Context

New standalone project: a professional walk-around WiFi survey app for Android (Ekahau/NetSpot-style, phone-only). The user walks an area, the app collects signal samples tied to positions, and a heatmap fills in **live** on screen. SSID-selectable visualization. Deliverable: installable APK.

**User-locked decisions:** both positioning modes (indoor floor-plan tap-to-walk + outdoor GPS); both signal modes with auto-detect (connected-SSID fast polling + scan-all with throttle detection/coaching); floor plan = imported image or blank grid, two-point scale calibration; new public GitHub repo under **Atvriders**, APK built by GitHub Actions.

**Environment constraint:** this sandbox has **no JDK / Android SDK** (verified). CI-is-verify, exactly like the proven `Atvriders/ham-radio-wspr-txrx` pattern: correctness comes from pure-JVM unit tests + CI `assembleRelease`; UI verified by sideloading the CI-built APK. This forces per-phase pushes to master so CI can verify each phase (deviation from the usual single-commit-at-end preference — there is no other way to run the build).

## Repo & identity

- Repo: `Atvriders/wifi-heatmap-surveyor` (public), branch `master`, local dir `~/wifi-heatmap-surveyor`
- Package/appId: `com.atvriders.wifiheatmap`; app name "WiFi Heatmap Surveyor"
- Auth: GitHub token from `~/.config/gh/hosts.yml` (the PAT at `~/.github_token` is expired). Create repo via GitHub REST API (no gh CLI per user preference), push via git.

## Build stack (copied from ~/ham-radio-wspr-txrx — proven)

Single `:app` module. AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.10.01, Gradle wrapper 8.9 (sha-pinned, copy jar+scripts), JDK 17, compileSdk 35 / minSdk 26 / targetSdk 35. Compose M3 + icons-extended, Navigation Compose 2.8.4, Lifecycle 2.8.7, coroutines 1.8.1, kotlinx-serialization 1.7.3, Room 2.6.1 (KSP, exportSchema→`app/schemas`, commit the JSON), DataStore 1.1.1. **Drop from WSPR catalog:** OkHttp, MapLibre, adaptive/window-size libs. Release: minify+shrink; debug appId suffix `.debug`.

CI `.github/workflows/build.yml`: copy WSPR structure — fork-safe PR `test` job (testReleaseUnitTest + assembleRelease), `publish` job (tests → assembleRelease → artifact upload every push; GitHub Release with APK on `v*` tags; `KEYSTORE_BASE64` secret optional → **falls back to debug signing so every green build is installable**; VERSION_CODE=`github.run_number`), `workflow_dispatch` enabled. Changes vs WSPR: branch `master` (same), artifact names `wifi-heatmap-surveyor-*`, **drop all AAB steps**.

## Architecture rule

Everything under `core/` is **pure Kotlin/JVM** — no `android.*` imports, no Bitmap/Context; pixels are `IntArray` ARGB, time is injected `() -> Long`. Android layer (`data/`, `sources/`, `export/`, `ui/`) is a thin shell. ~90% of logic lives in `core/` under JUnit4 tests since that's the only runnable verification in-sandbox.

```
com.atvriders.wifiheatmap
├── WifiHeatmapApp.kt / MainActivity.kt      Application + manual AppContainer DI; single activity
├── core/model      PositionedSample, WifiReading, SignalSnapshot, PositionFix, HeatFilter, Band, enums
├── core/geo        Vec2, GeoProjection (equirect local-meters), ScaleCalibration (2-pt px→m), PlanTransform (view↔plan pan/zoom math)
├── core/assemble   TapSampleAssembler (segment lerp), GpsSampleAssembler (nearest-fix + accuracy gate)
├── core/heatmap    IncrementalIdwGrid, ColorScale, GridSpec, LegendModel, CoverageStats
├── core/wifi       BandClassifier (MHz→2.4/5/6), ScanThrottleDetector, ScanScheduler, RssiSampler (freshness dedup), SignalStats, dBm clamps
├── core/onboarding PermissionsGate(apiLevel, grants, locationOn, wifiOn) → step  [pure state machine]
├── core/engine     SurveyEngine, HeatmapController; PositionSource/SignalSource/SampleSink interfaces
├── core/export     CsvBuilder (RFC-4180)
├── data/           Room (AppDatabase, entities, DAOs), SurveyRepository : SampleSink (write-behind batching), SettingsStore, FloorPlanImageStore
├── sources/        Android impls: ConnectedRssiPoller, ScanAllSignalSource, GpsPositionSource, PermissionsProbe
├── export/         ExportComposer (PNG render), SAF glue
└── ui/             theme, nav (type-safe routes), home, wizard, live, analysis, settings, common
```

## Data model (Room, 4 entities)

- `surveys`: id, name, createdAtMs, positioningMode(TAP|GPS), scanMode(CONNECTED|SCAN_ALL), floorPlanId?, status(ACTIVE|COMPLETE)
- `floor_plans`: imagePath? (null=blank grid), widthPx/heightPx, metersPerPixel?, raw 2-point calibration inputs (re-editable post-survey)
- `samples` (idx surveyId, FK cascade): surveyId, timestampMs, x/y (TAP: plan px; GPS: local meters), lat/lon?, accuracyM?
- `readings` (idx sampleId, (surveyId,ssid), (surveyId,bssid)): sampleId, **denormalized surveyId + precomputed band** (hot filters need no joins), bssid, ssid, rssiDbm, frequencyMhz, isConnected

Write volume ~50 rows/s worst case → batched `@Transaction` inserts (per finalized tap segment; every 10 samples/5 s in GPS mode). **Live heatmap does NOT observe Room** — engine keeps samples in memory, Room is write-behind (continuous autosave → "resume interrupted survey" on relaunch). Room Flows serve home list / SSID filter sheet / analysis. Key queries: SSID summaries (GROUP BY with counts), `heatPoints(surveyId, ssid, band?, bssid?)` = best RSSI per sample via `MAX() GROUP BY s.id`, flat export rows.

## Survey engine (core, all JVM-tested with fakes + virtual time)

Pipeline: `SignalSource.snapshots` → assembler → positioned samples → (a) `StateFlow` to UI, (b) batched to `SampleSink`(Room).

- **TapSampleAssembler**: buffers snapshots since last tap; on next tap, lerps each by `(t-t0)/(t1-t0)` along the segment. Pre-first-tap discard, t1==t0 guard, undo returns segment for deletion + restores anchor, pause/resume.
- **GpsSampleAssembler**: pair snapshot with latest fix if <5 s old and accuracy ≤ gate (default 15 m); else drop + "waiting for GPS" flag. `GeoProjection` anchored at first accepted fix.
- **SurveyEngine** API: `start/stop/onTap(planPoint)/undoLastSegment/pause/resume`; exposes `samples`, `liveStats` (current dBm + freshness, counts, elapsed), `ssidsSeen`, `throttle` StateFlows.
- **HeatmapController**: dirty-flag + 500 ms conflated ticker on Dispatchers.Default; incremental adds; filter change → full re-add. Filter semantics = max RSSI among matching readings per sample (matches the DAO query — tested for agreement).

## Platform layer (the risky bits — researched)

- **Connected polling (hero mode, unthrottled):** 1 Hz `wifiManager.connectionInfo` (`@Suppress("DEPRECATION")`, works through API 36) for RSSI on all levels; on 31+ also register `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` for authoritative SSID/BSSID + roam/disconnect events (direct `getNetworkCapabilities().transportInfo` is redacted even with fine location!). Framework refreshes RSSI only every ~3 s (6 s stationary on 14+) → `RssiSampler` tags fresh/stale, UI pulses on fresh. Strip quotes from `WifiInfo.getSSID()`. Clamp dBm to [-100,-10], drop -127/invalid. Tag samples with BSSID; surface roams.
- **Scan-all:** `startScan()` (deprecated-but-functional) + context-registered receiver (`RECEIVER_NOT_EXPORTED` on 34+) for `SCAN_RESULTS_AVAILABLE_ACTION`; keep receiver registered all session to **passively harvest other apps'/system scans** (free, no budget). `ScanThrottleDetector` (pure state machine): startScan()==false, `EXTRA_RESULTS_UPDATED`==false, and `ScanResult.timestamp` (µs since boot) older than request time (OEM cache, e.g. Samsung) → UNKNOWN/UNTHROTTLED/THROTTLED/OEM_CACHED. `ScanScheduler` token bucket: 4/120 s window; pace 3–6 s unthrottled, 30 s throttled. Coaching sheet → `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` (try/catch; fallback = illustrated "enable dev options" instructions). `Settings.Global "wifi_scan_throttle_enabled"` read is a hint only (stale on 11+) — empirical detection is truth.
- **Permissions:** manifest = `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`. **No `NEARBY_WIFI_DEVICES`** (it gates P2P/Aware/RTT, not scan APIs; we need fine location anyway since results are location-correlated). Request FINE+COARSE together; detect "Approximate-only" grant on 31+ → dedicated fix-it screen. Gate survey start on grant + `LocationManagerCompat.isLocationEnabled` (off → scans silently return empty / `<unknown ssid>`) + WiFi on (`Settings.Panel.ACTION_WIFI`). All decisions in pure `PermissionsGate`, exhaustively tested for API 26–36.
- **GPS (no Play Services):** `LocationManagerCompat.requestLocationUpdates`, `FUSED_PROVIDER` on 31+ only after `hasProvider()`, else `GPS_PROVIDER`; 1 s interval; show TTFF "waiting for fix" state + accuracy circle; reject fixes >15 m for placement, block >30 m. Blank north-up local-meters canvas with scale bar — **no map tiles in v1** (lat/lon stored per sample so a basemap can come later without migration).
- **Lifecycle:** `FLAG_KEEP_SCREEN_ON` during capture (user is actively tapping — screen must be on anyway; RSSI polling stops with screen off, so a foreground service wouldn't help). ON_STOP → auto-pause + flush. **No FGS in v1.**
- **Floor plan import:** `OpenDocument("image/*")` (not photo-picker — plans come from Files/Drive), **copy** into `filesDir/floorplans/` (persisted URI grants die), downsample to ≤4096 px, EXIF rotation (ImageDecoder on 28+, BitmapFactory+exifinterface on 26–27), re-encode normalized.

## Heatmap

IDW power-2 with hard radius cutoff (default 8 m indoor / 20 m outdoor, setting) — additive, so **incremental**: per new sample touch only cells in the cutoff disc of `sumW`/`sumWV` FloatArray accumulators (incremental == batch is a unit test). Grid: longest dimension capped at 200 cells. **Grey/transparent beyond the cutoff — never paint fantasy coverage.** Colorize in core to `IntArray`; UI does the only untestable step (`Bitmap.setPixels` into 2 ping-pong bitmaps → `ImageBitmap` state, ≤5–10 Hz). Draw via `drawImage(dstSize=…, FilterQuality.Low)` for free bilinear upscale + path polyline + tap markers from immutable snapshots (no `mutableStateListOf` — recomposition storm).

**Fixed industry color scale (never auto-rescaled):** ≥-50 excellent / -60 / **-67 (voice-grade floor — default threshold)** / -75 / -85 / below = grey. Threshold **pass/fail mode**: binary green/red/grey with presets. Coverage stats: % of **surveyed** area ≥ threshold (surveyed = within cutoff of a sample), min/**median**/max, m² (hidden if uncalibrated), sample count.

## Screens (M3, phone-first)

1. **Home** — survey cards (name, mode, date, counts), tap→Analysis, Resume for ACTIVE, delete w/ confirm; FAB→wizard; ⋮→Settings.
2. **Wizard** (<60 s to walking): Basics (name, Indoor/Outdoor) → Floor plan (import / blank grid w×h meters; outdoor skips) → Calibration (pinch-zoom, tap 2 points, enter distance; Skip allowed w/ consequences dialog → "Uncalibrated" watermark, px-based radius, no m² stats; re-editable later) → Capture mode (Connected recommended-chip w/ live connected SSID, or Scan-all w/ inline throttle check result) → Start + one-time coach overlay ("tap where you stand → walk straight → tap at turns").
3. **Live survey** — canvas (plan/grid + heatmap underlay + numbered tap dots + path + pulsing anchor; GPS: position dot + accuracy circle). Two-finger pan/pinch-zoom; **single tap = position** (TAP mode). Status strip: big live dBm colored by scale + fresh-pulse, band/channel, sample counter, mode chip, amber Throttled chip (tap→coaching). Bottom bar: Pause/Resume (canvas dims), Undo last tap (snackbar w/ count), Finish (confirm + quick stats). Stale-tap nudge after 45 s without a tap. Keep-screen-on.
4. **Analysis** — same canvas read-only. SSID bottom sheet (sorted by samples: band chips, BSSID + sample counts, "(hidden)" rows); band chip filter (All/2.4/5/6); per-BSSID drill-down (AP cell view); threshold pass/fail toggle + presets; stats card; opacity slider + dots/path toggles; Export PNG/CSV.
5. **Settings** — poll interval (0.5/1/2 s), dBm/% (dBm default), **meters/feet** (locale default), default threshold (-67), IDW radius, keep-screen-on, theme (system/light/dark + dynamic color).

**Error states (all v1):** permission denied (rationale screen → app settings); Approximate-only grant fix-it; location services off (blocking banner → `ACTION_LOCATION_SOURCE_SETTINGS` — empty scans otherwise); WiFi off (→ WiFi panel); throttled chip + data-age indicator ("12 s ago" — never present stale as fresh); disconnect mid-survey in connected mode (auto-pause, red strip, auto-resume, no phantom fill); zero-sample finish prompt; corrupt/oversized image inline error; process death → autosave + "Resume interrupted survey".

## Exports

- **PNG report** (the deliverable): ≥2048 px composite — plan + current heatmap view + legend + metadata footer (survey name, date, SSID/band/BSSID filter, threshold + % pass, sample count, scale bar, app version). SAF `CreateDocument` + share sheet.
- **CSV** (pure `CsvBuilder`, RFC-4180 incl. comma/emoji SSIDs): `timestamp_iso, pos_source, segment_index, x_m, y_m, latitude, longitude, gps_accuracy_m, ssid, bssid, band, frequency_mhz, rssi_dbm, connected`.

## Out of scope for v1 (cut list)

MapLibre/map tiles, foreground service, Wi-Fi RTT, spectrum/SNR, AP auto-trilateration (manual AP markers = v1-nice), PDF reports, cloud/accounts, multi-floor UI (schema-ready), sensor-fusion positioning, throughput tests, channel-overlap graphs, tablet layouts, Hilt (manual DI), AAB, instrumented tests.

## Implementation phases (each ends CI-green: testReleaseUnitTest + assembleRelease; push per phase — CI is the only build)

- **P0 Scaffold**: copy WSPR mechanical files (wrapper, catalog minus dropped deps, gradle files, adapted build.yml, manifest, theme, empty MainActivity, smoke test); create GitHub repo via API; push; **verify CI green + APK artifact installs before anything else** (minify config proven early).
- **P1 Core math**: core/geo, core/model, core/heatmap, core/wifi, core/onboarding — highest test density.
- **P2 Engine**: core/assemble + core/engine with fake sources, `runTest` virtual time.
- **P3 Data**: Room entities/DAOs (compile-time SQL validation via CI; commit `app/schemas/1.json`), SurveyRepository, SettingsStore, FloorPlanImageStore; mapper + CSV tests.
- **P4 UI shell**: theme/nav/Home/Settings/wizard (incl. calibration canvas — math already tested), PermissionsGate UI; survey creation persists.
- **P5 Live survey**: `sources/` implementations, live screen, canvas, tap/undo/pause, throttle banner, SSID picker. → **first on-device walk test (user sideloads CI APK)**.
- **P6 Analysis**: filter sheet, threshold mode, stats, rehydration of completed surveys.
- **P7 Export**: CSV glue + PNG ExportComposer + share.
- **P8 Polish + release**: error-state sweep, resume-interrupted flow, icon, README, tag `v1.0.0` → GitHub Release APK.

## Test inventory (JUnit4, `app/src/test/...`)

GeoProjection (known-distance ±0.1%, roundtrip) · ScaleCalibration (degenerate reject) · PlanTransform (view↔plan under pan/zoom) · BandClassifier (2412/5180/5955/edges) · ScanThrottleDetector (4-in-2min ok, 5th throttled, stale-cache signal, recovery) · ScanScheduler (token bucket) · RssiSampler (freshness dedup, clamps) · PermissionsGate (API 26/29/31/33/35 matrix) · TapSampleAssembler (lerp fractions, pre-first-tap, t1==t0, undo, pause) · GpsSampleAssembler (staleness, accuracy gate) · IncrementalIdwGrid (peak/decay/symmetry, cutoff-NaN, **incremental==batch**, reset) · ColorScale (breakpoints, clamp, NaN→transparent) · GridSpec · LegendModel · CoverageStats (surveyed-area-only) · SurveyEngine end-to-end w/ fakes · HeatmapController (conflation, filter=max-per-sample agrees with DAO semantics) · CsvBuilder (RFC-4180 quoting) · entity↔domain mappers.

## Verification

1. Every phase: CI green on push (unit tests + release assemble + wrapper validation).
2. P0 and P5 gates: user sideloads the CI artifact APK — P0 proves install/minify; P5 is the real walk test (tap-to-walk fills heatmap live, connected-mode dBm updates, throttle chip behaves on scan-all).
3. Final: on-device checklist across the error-state matrix (deny permission, location off, WiFi off, throttling on/off, disconnect mid-survey, process-kill resume), PNG/CSV export opens correctly, `v1.0.0` release APK attached.
