# WiFi Heatmap Surveyor

Professional walk-around WiFi survey tool for Android — Ekahau-style floor-plan surveys,
phone-only. Walk an area, watch the signal heatmap fill in **live**, filter by SSID / band /
access point, and export a report.

**Private by design: the app has no Internet permission.** Nothing you capture can leave
the device.

## Download

Grab the APK from the [latest Release](../../releases/latest), or from any green
[Actions run](../../actions)'s `wifi-heatmap-surveyor-apk` artifact, and sideload it
(enable "install unknown apps" for your file manager). Android 8.0+ (API 26).

## How a survey works

1. **New survey** → name it, pick **Indoor** (floor-plan tap-to-walk) or **Outdoor** (GPS).
2. **Indoor**: import a floor-plan image (photo, screenshot of a PDF, anything) or use a
   blank grid of known dimensions. **Calibrate** the plan by tapping both ends of a known
   distance (a wall, a hallway) and entering its real length — this makes coverage
   statistics and the interpolation radius physically meaningful.
3. Pick a **capture mode**:
   - **Connected network** (recommended): samples the WiFi you're on about once a second.
     Never throttled by Android — the smoothest live heatmap.
   - **Scan everything**: records every visible AP on 2.4/5/6 GHz. Android throttles
     foreground scans to 4 per 2 minutes; the app detects this and walks you through
     disabling **Developer Options → Wi-Fi scan throttling** for full-speed surveys.
4. **Walk**: tap where you stand, walk in a straight line, tap at every turn and stop.
   Samples collected between taps are placed by time interpolation along each segment —
   the heatmap paints as each segment completes. Undo removes the last tap and its samples.
5. **Finish** → the analysis screen: pick the SSID to visualize (everything was recorded
   in scan-all mode), filter by band or drill into a single BSSID to see one AP's true
   cell, flip to **pass/fail mode** against a threshold (default **-67 dBm**, the
   voice-grade floor), and read coverage statistics computed over the *surveyed* area only
   — this tool never paints coverage where it has no data.
6. **Export** a PNG report (plan + heatmap + legend + metadata) or the raw CSV
   (one row per sample × BSSID reading, meters-calibrated) via the share sheet.

## Feature notes for professionals

- Fixed industry color scale (−50 / −60 / **−67** / −75 / −85 dBm breakpoints), never
  auto-rescaled, so two surveys are always comparable. A colorblind-safe perceptual ramp
  (viridis-style, monotonic lightness) is available in Settings.
- Cells beyond the interpolation radius render as no-data — sparse sampling is shown
  honestly instead of painting fantasy coverage.
- Signal readings are deduplicated against Android's ~3 s RSSI refresh; the live readout
  pulses when a genuinely fresh value arrives, so you can pace your walk.
- Surveys autosave continuously; an interrupted survey resumes from the Home screen
  (GPS surveys re-anchor to the original coordinate frame, tap surveys continue their
  segment numbering).
- dBm or %, meters or feet, sampling rate, thresholds and IDW radius are configurable.

## Permissions

Android gates WiFi scan results (and the connected SSID) behind **precise location**,
because AP lists reveal location — the app therefore needs location permission and
location services ON to read anything. It uses them on-device only; there is no network
permission to send anything anywhere.

## Building

`./gradlew assembleRelease` with JDK 17 + Android SDK 35. CI builds a (debug-signed unless
keystore secrets are configured) release APK on every push to `master` and attaches it to
a GitHub Release on `v*` tags. 169 JVM unit tests cover the survey engine, interpolation
grid, throttle detection, assemblers, projections and exports: `./gradlew testReleaseUnitTest`.

## Architecture (for contributors)

Everything under `core/` is pure JVM — no Android imports — and fully unit-tested:
geometry/projection, the incremental radius-cutoff IDW grid (with exact remove-point undo),
scan-throttle detection state machine, tap-segment and GPS sample assemblers, and the
survey engine. The Android layer (`sources/`, `data/`, `ui/`, `export/`) is a thin shell:
`sources/` is the only code that touches `WifiManager`/`LocationManager`. See
[docs/DESIGN.md](docs/DESIGN.md) for the full design.
