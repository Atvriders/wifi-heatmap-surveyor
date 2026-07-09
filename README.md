# WiFi Heatmap Surveyor

Professional walk-around WiFi survey tool for Android. Walk an area, watch the signal
heatmap fill in live, and export a report — Ekahau-style floor-plan surveys, phone-only.

- **Indoor**: import a floor plan (or use a blank grid), calibrate its scale with two
  taps, then tap your position as you walk; samples are interpolated along each segment.
- **Outdoor**: GPS positioning on a north-up local-meters canvas.
- **Two capture modes**: fast RSSI polling of the connected network (never throttled),
  or scan-everything mode that records all SSIDs/BSSIDs and coaches you through
  Android's scan-throttling Developer Options toggle.
- **Analysis**: filter by SSID / band / individual BSSID, fixed industry color scale
  (-67 dBm voice-grade threshold), pass/fail coverage view, coverage statistics.
- **Exports**: PNG report (plan + heatmap + legend + metadata) and raw CSV.
- **Private by design**: the app has no Internet permission. Nothing ever leaves the device.

## Building

CI (GitHub Actions) builds a release APK on every push to `master`; download it from the
workflow run's artifacts. Tagged `v*` releases attach the APK to a GitHub Release.
Without signing secrets the APK is debug-signed — still installable for sideload.

Local build: `./gradlew assembleRelease` (needs JDK 17 + Android SDK 35).

## Status

Under active development — scaffold phase.
