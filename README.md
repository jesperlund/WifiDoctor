# WifiDoctor

An Android app that diagnoses home Wi-Fi problems. It checks the connection one layer at a time (Wi-Fi link → router → internet → DNS → speed → channel congestion), works out where the first problem is, and explains it in plain language with fixes to try.

- **Dashboard:** live link stats (RSSI gauge, band, channel, width, link speed, standard, BSSID), a big *Run diagnosis* button, a per-layer progress stepper, and a result card with a verdict and numbered fixes.
- **Rooms:** a walk test. Name a room and the app measures for 10 seconds (signal plus router latency and loss). Rooms are listed best to worst with signal bars, and dead zones are flagged.
- **History:** Canvas-drawn line charts of latency, packet loss and RSSI. Tap a point (or a row in the list) to see that run's diagnosis. Intermittent drops are marked with ▲ triangles.
- **Settings:** scheduled background checks (WorkManager, interval choice, notify only on problems), speed-test URL, probe targets, ping fallback, metered-network behaviour, CSV export and clear history.

Stack: Kotlin, Jetpack Compose (Material 3, dynamic colour, light/dark), a single activity, MVVM with `StateFlow`, Hilt, Room, WorkManager, DataStore and Coroutines/Flow. minSdk 26, targetSdk/compileSdk 35, package `com.example.wifidoctor`.

## Architecture

```
:core  (pure Kotlin/JVM library: no Android dependencies, so the engine and rules are fully unit-testable)
  com.example.wifidoctor.core
    model/       Measurements, LinkInfo, ProbeStats, DnsResult, ScanSnapshot, Diagnosis, Severity, Layer,
                 EnvironmentStatus (Wi-Fi off / not connected / location off / metered / cellular)
    wifi/        WifiMath (band, channel, channel overlap incl. bonded 5/6 GHz blocks, congestion,
                 expected link speed per band/standard/width), SignalQuality (label + bars)
    probe/       ProbeMath (avg/min/max/jitter/loss, Mbps), TcpConnectProber, PingProber + output parser,
                 FallbackProber, DnsTimer (system resolver vs raw UDP query), HttpThroughputTester,
                 NetworkBinding (lets the platform pin traffic to the Wi-Fi network)
    diagnosis/   Diagnoser + DiagnoserConfig (thresholds) + Rules (one object per rule)
    engine/      DiagnosticEngine (ordered layers → Flow<EngineEvent>), WalkTester (10 s room test)
    history/     RunRecord (flattened run), DropDetector, RoomRanking, CsvExporter

:app   (Android application)
  com.example.wifidoctor
    platform/    WifiNetworkTracker (NetworkCallback), AndroidLinkSource (WifiInfo/LinkProperties),
                 AndroidScanSource (startScan + throttling fallback), WifiNetworkBinding, EnvironmentMonitor,
                 PermissionChecker, WifiMappers (raw Android values → domain), DocumentWriter (SAF)
    data/        Room (RunEntity, RunDao, WifiDoctorDatabase), RunRepository, DataStore SettingsRepository,
                 SettingsValidation
    work/        DiagnosticWorker (HiltWorker), WorkManagerCheckScheduler, ProblemNotifier
    di/          Hilt modules, EngineFactory (builds an engine for the current settings)
    ui/          theme/, components/ (RSSI gauge, signal bars, layer stepper, diagnosis card),
                 dashboard/, rooms/, history/ (Canvas LineChart), settings/, permission/, navigation/
```

**Data flow.** A screen calls its `@HiltViewModel`. The ViewModel asks `EngineFactory` for a `DiagnosticEngine` and collects its `Flow<EngineEvent>` (`StepStarted`, `StepFinished`, `Throughput` progress, `Finished`). The UI state is a `StateFlow` that Compose collects with `collectAsStateWithLifecycle`. Every finished run is saved as a `RunRecord` in Room, tagged with the room name for walk tests. History and Rooms observe Room through `Flow`.

**Diagnostic engine.** The checks run in order:

| # | Layer | How |
|---|-------|-----|
| 1 | Link | RSSI, link/tx/rx speed, frequency → band + channel, channel width (from our own BSSID's scan entry), Wi-Fi standard, BSSID, SSID |
| 2 | Gateway | 10 TCP-connect probes to the router IP from `LinkProperties` (DHCP fallback): avg/max latency, jitter, loss % |
| 3 | Internet | The same probes to each target (default `1.1.1.1`, `8.8.8.8`) |
| 4 | DNS | 5 lookups via the network's DNS server (raw UDP query, uncached; system resolver if unknown) vs. `1.1.1.1` |
| 5 | Throughput | Download from the configurable URL (default Cloudflare's speed endpoint) for ≤10 s / ≤50 MB, with progress and cancellation |
| 6 | Congestion + channel plan | Wi-Fi scan; counts networks on the same / overlapping channels, then surveys **2.4 GHz and 5 GHz (and 6 GHz where permitted) regardless of which band you are connected to**, and suggests the best channel each band may legally use in your country |

If the phone is not connected, layers 2–6 are skipped. Every probe runs on `Dispatchers.IO` with connect/read timeouts and checks for cancellation between samples, and the whole run can be cancelled from the UI.

**Rules engine.** `Diagnoser.diagnose(Measurements): Diagnosis` runs every rule. The primary finding is the most severe one; ties go to the earliest layer, which is the first failing layer. Fixes from all findings are merged in priority order with duplicates removed. The rules:

| Rule | Condition | Severity / layer |
|------|-----------|------------------|
| Not connected | no link | FAIL / link |
| Weak signal | RSSI < −70 dBm (FAIL < −80) → distance or obstruction | WARN/FAIL / link |
| Low link speed | link speed < 25 % of the typical 2×2 max for band + standard + width | WARN / link |
| Band steering | on 2.4 GHz while the same SSID is visible on 5/6 GHz at ≥ −67 dBm | WARN / link |
| Router not responding | no probe replies from gateway *and* internet | FAIL / gateway |
| Interference / congestion | RSSI ≥ −60 dBm but gateway latency > 30 ms, jitter > 20 ms or loss ≥ 5 % → change channel / use 5 GHz | WARN (FAIL ≥ 10 % loss) / gateway |
| Unstable router link | moderate signal and a bad gateway | WARN/FAIL / gateway |
| ISP / uplink | gateway fine but internet loss ≥ 5 %, latency > 150 ms or jitter > 50 ms | WARN (FAIL ≥ 20 % loss) / internet |
| No internet | gateway unknown and no internet target answers | FAIL / internet |
| Slow / failing DNS | pings fine, but system DNS > 2× reference and ≥ 50 ms slower, or failing → switch DNS | WARN/FAIL / DNS |
| Crowded channel | ≥ 4 networks on the same channel, or ≥ 6 same + overlapping | WARN / congestion |
| Channel plan | your router's channel on any band is not permitted in the detected country, a permitted channel is clearly quieter (score ≥ 1.0 lower and ≥ 1.5× better), or 2.4 GHz uses 40 MHz among ≥ 3 neighbours | WARN / congestion |
| Slow download | < 5 Mbps | WARN / throughput |

All thresholds live in `DiagnoserConfig`.

**Channel suggestions (all bands, region-aware).** `core/wifi/Regulatory.kt` holds simplified indoor channel tables per regulatory domain:

| Domain | Countries | 2.4 GHz | 5 GHz | 6 GHz |
|--------|-----------|---------|-------|-------|
| FCC | US (+ territories), MX, BR | 1–11 | 36–64, 100–144, 149–165 | 1–233 |
| ISED | CA | 1–11 | as FCC minus 120–128 | 1–233 |
| ETSI | EU/EEA, CH, TR, Balkans | 1–13 | 36–64, 100–140 | 1–93 |
| Ofcom | GB | 1–13 | 36–64, 100–140, 149–165 | 1–93 |
| MIC | JP | 1–13 | 36–64, 100–144 | 1–93 |
| MIIT | CN | 1–13 | 36–64, 149–165 | – |
| ACMA | AU, NZ | 1–13 | 36–64, 100–116, 132–144, 149–165 | 1–93 |
| WPC | IN | 1–13 | 36–64, 149–165 | – |
| MSIT | KR | 1–13 | 36–64, 100–144, 149–165 | 1–233 |
| Unknown | anything else | 1–11 | 36–48 | – |

The country comes from, in order: the **Settings override**, the 802.11d **country element broadcast by nearby access points** (majority vote; Android 11+), the **mobile network** country, the **SIM**, then the **device locale**. The source is shown next to the advice. A channel is only called "not permitted" when the country is known.

`ChannelPlanner` scores every permitted channel per band: each overlapping neighbour adds a weight based on its signal strength (−70 dBm = 1.0, −40 dBm = 2.0). On 2.4 GHz a partially overlapping neighbour counts 1.5× (adjacent-channel interference is worse than sharing a channel). Your own router's BSSIDs (same SSID) are excluded. 2.4 GHz is planned at 20 MHz, preferring 1/6/11 on ties and penalising 12/13 (US-market devices can't use them). 5/6 GHz use the widest permitted 80/40/20 MHz block, with a small penalty for DFS (radar) channels. 6 GHz uses preferred scanning channels. A change is only advised when it clearly helps, since switching channel briefly disconnects everyone. The Dashboard shows a per-band card with an occupancy bar chart (★ suggested, • yours).

## Permissions

| Permission | Why |
|------------|-----|
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | Android treats SSID, BSSID and scan results as location data. Android 12+ requires asking for both. GPS is never used: the country for channel rules comes from access points and the mobile network. |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Wi-Fi scans. Not flagged `neverForLocation`, because that would let Android strip the BSSIDs that band-steering detection needs. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Read Wi-Fi info and call `startScan()`. |
| `ACCESS_NETWORK_STATE`, `INTERNET` | Network callbacks, probes and the speed test. |
| `POST_NOTIFICATIONS` (Android 13+) | Asked when scheduled checks are turned on; used only for problem alerts. |

On first launch a rationale screen explains each permission before the system dialog appears. If the user denies, the app keeps working in **limited mode**: latency, DNS and speed still work, while the SSID/BSSID and congestion check are unavailable. After a permanent denial ("don't ask again") the screen offers *Open app settings*, and the Dashboard shows a notice with an *Allow* button.

The Dashboard also handles **Wi-Fi off** (blocks the run, with a button for the Wi-Fi panel), **not connected**, **location services off** (button to turn them on), **metered Wi-Fi** (the speed test is skipped unless allowed in Settings), and **Wi-Fi without internet while Android uses mobile data**. In that last case all probe sockets, DNS queries and the download are bound to the Wi-Fi `Network`, so the app never measures mobile data by mistake.

Privacy: no analytics, no ads, no crash reporting, no accounts. The only traffic is to the router, the probe targets, the two DNS servers being compared, and the speed-test URL. Results stay on the device unless exported.

## Known Android limitations

- **No raw ICMP.** Apps cannot open raw sockets without root. The main probe times TCP handshakes to ports 53/443/80 (whichever answers first): both SYN-ACK and RST ("connection refused") are full round trips. If every port is silently dropped, the app falls back to `/system/bin/ping` through `ProcessBuilder`, which exists on most but not all devices. Ping cannot be bound to the Wi-Fi network.
- **Scan throttling.** Android 9+ allows 4 scans per 2 minutes in the foreground and 1 per 30 minutes in the background. When `startScan()` is refused, the app uses cached results and labels the congestion step "(cached scan)".
- **Channel width** is not in `WifiInfo`, so it comes from the scan entry of the connected BSSID and may be missing until a scan has run.
- **Regulatory data is simplified:** indoor rules only, no power limits, and no outdoor or very recent rule changes. Access points' country elements need Android 11+. Your router may also offer fewer channels than the law allows. Use the Settings override if the detected country is wrong.
- **Wi-Fi standard** (`getWifiStandard`) needs Android 11. On older versions, link-speed expectations fall back to per-band defaults.
- **Background checks** cannot read the SSID/BSSID without `ACCESS_BACKGROUND_LOCATION`, which the app deliberately does not request. WorkManager may also defer runs (Doze, battery saver), and the minimum interval is 15 minutes.
- **DNS caching.** The system resolver caches results, so the app sends uncached raw UDP queries to the network's DNS server for a fair comparison. With Private DNS (DoT) on, apps actually use the DoT server and the comparison is approximate.
- **TCP-connect latency** includes a little kernel and scheduling overhead, so it reads about 1 ms higher than ICMP on a LAN.
- `WifiManager.startScan()`, `connectionInfo`, `dhcpInfo` and `ScanResult.SSID` are deprecated, but they are still the only (or most compatible) APIs for this data. They are used behind suppressions with modern fallbacks where available.

## Build and install

Requirements: JDK 17+ and the Android SDK (platform 35) with `ANDROID_HOME` set or `sdk.dir` in `local.properties`.

```bash
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # app unit tests (ViewModels, mappers, validation, chart math)
./gradlew :core:test             # engine, rules and probe tests (pure JVM)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml`) runs the unit tests and `assembleDebug` on every push and uploads the debug APK and test reports as artifacts.

## Decisions made

- **Two modules.** `:core` is a plain Kotlin/JVM module, so the Gradle module boundary itself guarantees the rules have no Android dependencies.
- **First failing layer** means the most severe finding, ties broken by layer order. A FAIL further up the stack outranks a WARN at the link, because it explains more of what the user is experiencing.
- A router that ignores probes while the internet works is **not** a problem (many routers drop SYNs to closed ports).
- **"Low link speed"** is judged against 25 % of a typical two-stream maximum for the band, standard and width, because most phones are 2×2.
- **The DNS "system resolver"** is the network's DHCP-provided DNS server, queried directly over UDP. Without one, the app uses `InetAddress`.
- **The speed test** defaults to `https://speed.cloudflare.com/__down?bytes=25000000`, is capped at 10 s / 50 MB, times only the response body (not DNS or TLS setup), and is skipped on metered Wi-Fi and in background checks unless enabled.
- **Walk test:** 10 samples, one per second, of RSSI and link speed, plus one gateway probe per sample. Rooms are ranked by RSSI, minus 0.5 dB per % loss and a latency penalty above 20 ms. A room is a dead zone below −80 dBm or at 30 % or more loss. Only the latest walk per room (case-insensitive) is ranked.
- **Intermittent drops** are runs with ≥ 10 % loss, latency more than 3× the median (and at least 50 ms above it), or RSSI 15 dB or more below the median.
- **History charts** show the latest 200 runs, spaced evenly by run rather than by time, so bursts of runs stay readable.
- **CSV export** uses the Storage Access Framework (no storage permission) and neutralises spreadsheet formula injection from attacker-controlled SSIDs.
- **Accessibility:** every status has an icon and a word as well as a colour. Charts have text summaries, and every chart point is also reachable through a list. Touch targets are at least 48 dp, with content descriptions on gauges, bars and progress indicators.
- **Version pins** (AGP 8.10.1, Kotlin 2.1.21, KSP 2.1.21-2.0.2, Hilt 2.56.2, Compose BOM 2025.05.00, Room 2.7.1) are stable releases with known-good compatibility, which matters more here than being the newest.
- **Build environment:** the cloud sandbox this was written in could not reach Google's Maven repository (`dl.google.com` / `maven.google.com`). `:core` was built and tested locally against Maven Central; the full Android build and app tests were verified on GitHub Actions.

## Next steps

- Show a live mini-chart of signal strength during a walk test, and let the user draw a floor plan to turn walk tests into a heat map.
- Add an upload test, and latency under load (bufferbloat) by probing during the download.
- Add IPv6 probes and checks for a captive portal or a double NAT.
- Compare against the router's band/BSSID history to detect roaming problems in mesh networks.
- Add a Glance home-screen widget with the last verdict and a one-tap check.
- Add Compose UI tests (Robolectric or instrumented), screenshot tests, and Room DAO tests.
- Localise strings into resources and translate them.
- Add a per-network history filter (by SSID/BSSID) and a weekly summary notification.
