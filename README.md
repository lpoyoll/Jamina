# Jamina — Android Wi-Fi Survey & Network Planner

A native Android app (Kotlin + Jetpack Compose) for surveying Wi-Fi, drawing simple building blueprints,
generating measured heatmaps and planning access-point placement. It works fully offline: no backend,
no accounts and no paid services.

**Install:** download [`release/Jamina-0.1.0.apk`](release/Jamina-0.1.0.apk) to your phone, open it and allow
"Install unknown apps" when Android asks. Requires Android 10 (API 29) or newer.

## What it does

| Section | Features |
|---|---|
| **Dashboard** | Connected SSID/BSSID, RSSI gauge + history, band/channel/width, link speed (TX/RX), Wi-Fi standard, IP/gateway, one-tap latency/jitter/loss test. Tap any metric to see what it means. **Quick Survey** wizard. |
| **Scanner** | Real `WifiManager` scans, grouped by SSID with every BSSID listed separately. Shows RSSI, channel, width, standard, security and vendor (OUI). Channel graphs for 2.4/5/6 GHz, per-BSSID RSSI history, and an interference overview (*inferred* from scans). Honours Android's 4-scans-per-2-minutes throttle and shows the age of each result. You can name APs (e.g. "Landing"). |
| **Survey** | Buildings → floors → **Blueprint / Survey / Heatmap** tabs. |
| **Planner** | Virtual APs (catalogue of common models + a generic profile), predicted heatmaps, best-server and co-channel views, multi-floor attenuation, scenarios, **Optimise coverage** and **Calibrate** against a real survey. |
| **Reports** | Per-survey summaries, evidence-based insights, survey A/B comparison (same locations only), PDF report, CSV export, JSON project backup/restore. |

### Blueprint editor
- **Import** a PNG/JPG/WEBP image or the first page of a PDF, then **Calibrate** by marking a known length.
- Or **draw** a blueprint: Wall, Room (rectangle → four walls + a named room), Door, Window, Stairs,
  Select (move walls/ends, set exact length, mark exterior), Erase and Undo.
- 25 cm snap grid, snapping to wall ends and right angles; two-finger pinch/pan.
- Wall materials with per-band attenuation (plasterboard, timber, brick, block, concrete, reinforced
  concrete, glass, Low-E glass, metal, foil board, doors). Values are editable in *Settings → Advanced mode*.
- Rooms can be flagged "No AP here" or "Needs strong coverage" (used by the optimiser).
- Multiple floors with level, ceiling height, slab material and X/Y alignment offsets; new floors can copy
  the exterior walls of the floor below.

### Surveying
- **Point** — stand still, tap your position; every visible BSSID is recorded at that spot (optionally with
  gateway latency/loss and a short throughput test).
- **Walking** — lay out checkpoints, walk the route and tap *Reached* at each one. Scan positions are
  *estimated* by time between confirmed checkpoints and stored with an accuracy figure (hollow dots).
- **Continuous** — scans are recorded continuously and stored at the position you last pinned.
- Raw scans are stored separately from heatmaps, so maps can be regenerated with different settings.

### Heatmaps
Signal strength, best AP, channel overlap, latency, packet loss, throughput, roaming/connected AP and
coverage confidence. Filter by SSID, BSSID and band; combine several surveys. Inverse-distance weighting
with optional wall-aware weighting. Areas too far from any measurement are left blank (or shown faded if
you enable the estimate). Measured maps are tagged **MEASURED**, simulations **PREDICTED** — on screen,
in PNG exports and in the PDF.

### Diagnostics
Gateway vs internet latency, jitter, packet loss (ICMP where Android allows, otherwise TCP-connect timing,
always labelled), uncached DNS timing, HTTP response time, a "full check" that separates Wi-Fi/LAN problems
from internet problems, internet speed test (configurable endpoints), LAN speed test against a URL on
your network, a built-in LAN test server (`curl http://PHONE_IP:8099/download` from a wired PC), a
recording diagnostic session that highlights degradations, and a roaming monitor with a timeline of
BSSID changes and observed connectivity gaps.

## Honest limitations
Ordinary Android phones **can't** measure airtime utilisation, non-Wi-Fi interference, raw 802.11 frames or
the exact reassociation time, and indoor position isn't automatic. Jamina never fabricates readings: cached
scans, estimated positions and predictions are always flagged. RSSI differs between phones, so compare
surveys taken with the same device. For denser walking surveys, turn off **Developer options → Wi-Fi scan
throttling**.

Location permission is needed because Android only shows nearby APs and the connected BSSID to apps with
it. Jamina doesn't read or store your GPS position.

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest      # 34 unit tests (interpolation, propagation, optimiser, parsing…)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed with keystore.properties if present, else the debug key
```

To sign releases with your own key, create `keystore.properties` in the project root (it is git-ignored):

```
storeFile=C:/path/to/jamina-release.jks
storePassword=...
keyAlias=jamina
keyPassword=...
```

GitHub Actions (`.github/workflows/android.yml`) runs the tests and attaches a debug APK to every push.

## Architecture

Single `app` module, MVVM with `StateFlow`, Room, DataStore, coroutines and a small manual DI container
(`AppContainer`). Packages:

```
core/        db (Room entities, DAO), model, settings (DataStore), Repository (import, backup, geometry)
wifi/        WifiScanner (throttle-aware), ConnectionMonitor (roam detection), ChannelAnalysis, WifiMath
floorplan/   Geometry (segment intersection, wall/opening path loss)
heatmap/     Interpolation (IDW, confidence, stats), Heatmaps (measures & builders), renderers
planner/     Propagation (log-distance + walls + floors), Optimizer, Calibration, ApCatalog
diagnostics/ NetProbe (ping/TCP/DNS/HTTP), SpeedTest, LanTestServer
reports/     Insights, Comparison, PDF & CSV export
ui/          Compose screens, PlanCanvas (zoom/pan/tool gestures), theme
```

Predicted RSSI = Tx power + antenna gain − FSPL(1 m, f) − 10·n·log10(d) − Σ wall losses(f) − Σ floor losses(f) + offset.
