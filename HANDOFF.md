# TrefferPoint — Session Handoff
_Stand: 2026-05-13 abends — v2.3.170, bereit für 2. Praxistest am Stand (morgen 2026-05-14)_

---

## Aktueller Stand

**Repo: v2.3.170** (committed, getaggt, gepusht, GitHub Release vorhanden, APK auf Tablet installiert)
**Tablet:** versionName 2.3.170 / versionCode 174 (`ce031823ccfb0432027e`)
**Hardware:** Apexel ETF150 WiFi-Cam am Spektiv

---

## Was zwischen v2.3.167 (1. Stand-Test) und v2.3.170 passiert ist

### 1. Stand-Test (2026-05-12) — Befunde (siehe `sessions/2026-05-12_nachbesprechung/README.md`)
- **KK25:** 37 Hits in 36 min · Ø 9.05 · Streuung 140×151 mm (zu groß) — User: "deutlich mehr Schüsse signalisiert als abgegeben"
- **GK25:** 21 Hits in 5:46 · Ø 6.33 · 1.8er-Ringe verdächtig
- **Auto-Kalib lag immer "3-4 Ringe zu weit links"**
- **App war zwischenzeitlich eingefroren** (Neustart nötig)
- Erste Hits schon bei t=0.42 s nach "Erkennung starten" → Warmup effektiv kaputt

### v2.3.168 — Detection-Härtung
- Auto-Kalib: **Center-of-Mass-Anchor** (statt fixer Bildmitte) + Quadranten-Symmetrie-Check
- Warmup: **zeitbasiert 6 s + Median-Ref-Frame** statt 60 rAF-Frames (≈1 s)
- 3-Frame-Bestätigung (statt 2-Frame), Disziplin-spezifischer minRing-Filter (Sportpistole=3)
- Hit-Liste FIFO 60, Snapshot-In-Flight-Guard
- **Bestätigungs-Modus** (Checkbox): Tap-to-Confirm Banner mit 5 s Auto-Discard
- ETF150 REC: alle Auto-Segment-Clips als `vidPaths`-Array (vorher nur erster 40s-Clip)

### v2.3.169 — Code-Review-Followup (P1 #1+#3)
- `scripts/check_versions.sh` Pre-Release-Drift-Check (6 Stellen)
- `stopAllActiveStreams()` cleanupt jetzt auch `rtspSnapshotInterval`, `rtspKeepAliveInterval`, Confirm-Banner, refWarmup-Akku
- sw.js-Asset-Drift (140 → 168 → 170) gefixt

### v2.3.170 — Praxis-Ready für morgen
1. **3-Frame-Confirm jetzt echt:** vorher zählte rAF (60 Hz) denselben Snapshot 15× pro Sekunde → 3 Confirms trivial in 5 ms abgeschlossen. Jetzt `snapshotGen`-Guard: 3 Confirms = 3 verschiedene Source-Frames (~750 ms SurfaceView-Mode).
2. **PixelCopy native-async** (Code-Review P1 #4): JS-Call kehrt in <1 ms zurück statt bis 1500 ms zu blockieren. Bitmap-Reuse statt 9k×8 MB-Alloc-Churn → vermutlich die "App-Freeze"-Quelle aus dem 1. Stand-Test.
3. **Diagnostik:** Jede Filter-Rejection (`pts_out_of_range`, `not_circular`, `spatial_jitter`, `below_min_ring`, `spatial_exclusion`, `user_discarded`) wird in `recSession.rejected_candidates` gespeichert + `rejected_summary` aggregiert.
4. **Live-Counter "1/3 → 2/3 → 3/3"** über pending-Cluster (orange/gelb/grün) für visuelles Feedback.

---

## Praxis-Workflow am Stand (morgen 2026-05-14)

### Setup
1. ETF150-Hotspot anschalten, Tablet **explizit ins ETF150-WLAN** wählen (Gateway `192.168.10.1`)
2. TrefferPoint öffnen → "📡 WLAN-Cam" → "▶ RTSP-Stream starten"
3. **Auto-Kalibrierung 1× testen** — sollte jetzt korrekt sitzen (Center-of-Mass-Anchor). Banner "asymmetrischer Fit (Quadranten …)" → manuell mit 3-Klick weitermachen
4. Disziplin wählen (KK25 oder GK25), Auto-REC aktivieren

### Erste Serie (empfohlen): Bestätigungs-Modus AN
- "✋ Bestätigung erforderlich" (Checkbox im Detection-Panel) anhaken
- "Erkennung starten" → 6 s Warmup-Banner ("stabilisiere · Ns · N Samples")
- Bei jedem Treffer-Kandidaten: Banner oben mit Ring + Distanz, "✓ Behalten" oder "✗ Verwerfen" oder Tap aufs Canvas (= bestätigen)
- 5 s Timeout → auto-verworfen
- **Vorteil:** jeder "Verwerfen"-Klick produziert einen `user_discarded`-Eintrag im JSON → Gold für FP-Analyse

### Zweite Serie: Bestätigungs-Modus AUS
- Auto-Detection läuft volltransparent durch
- Auf "1/3 → 2/3 → 3/3"-Counter achten:
  - Counter geht 1 → 3 schnell durch = echter Treffer wird zuverlässig erkannt
  - Counter springt 1 → 2 → verschwindet = Spatial-Jitter (FP knapp verworfen, gut)
  - Counter bleibt 1 → 0 = pts_out_of_range / not_circular (FP weit verworfen, sehr gut)
- Treffer-Ansage akustisch wie gehabt

### Speichern
- "■ Erkennung pausieren" → REC stoppt, vidPaths-Array gefüllt
- "💾 Session-Log speichern" → JSON nativ in `/sdcard/Download/tp_session_<ts>_<disz>.json`
- JSON enthält jetzt:
  - `hits[]` (committed)
  - `rejected_candidates[]` mit Reason je Eintrag
  - `rejected_summary` aggregiert pro Reason
  - `rec.video_paths[]` ALLE Auto-Segment-Clips (nicht nur ersten)

---

## Architektur (Stand v2.3.170)

```
Apexel ETF150 (1920×1080 H.264 RTSP)
            ↓ TCP, kein Proxy
RtspMediaCodecPipeline
  ├─ HW-Decoder OMX.qcom.video.decoder.avc
  ├─ KEY_LOW_LATENCY=1, OPERATING_RATE=60, PRIORITY=0
  ├─ Watchdog gegen Decoder-Stall (v2.3.156, 4 s Stall → Auto-Restart)
  └─ releaseOutputBuffer(idx, true)
            ↓ direkt
SurfaceView (Android Hardware-Overlay)           ← Display, <500 ms Lag
  (body.rtsp-live transparent → durch WebView sichtbar)

            ↑ Async-PixelCopy (v2.3.170)
captureRtspSurfaceJpeg() — Async-Producer
  ├─ Persistenter HandlerThread
  ├─ Reusable Bitmap (1× alloc statt 4 Hz × 8 MB Allocs)
  ├─ AtomicReference<latestJpeg>
  └─ JS-Call kehrt sofort zurück mit zuletzt-fertigem JPEG
            ↑ alle 250 ms abgerufen (4 Hz)
WebView (transparent)
  ├─ UI/Overlays (Ringe, Treffer-Marker, Status, 1/3-Counter)
  ├─ Snapshot-Loop: imgEl ← captureCurrentFrame() → 4 Hz
  ├─ Off-Screen-Canvas detCtx mit imgEl befüllt
  ├─ runDetection() im rAF-Loop liest aus detCtx → Hit-Detection
  │   ├─ pts_out_of_range filter
  │   ├─ not_circular filter (b/a < 0.25)
  │   ├─ 3-Frame-Bestätigung mit snapshotGen-Guard (v2.3.170)
  │   ├─ below_min_ring (Sportpistole=3) filter
  │   └─ spatial_exclusion gegen vorige Hits
  └─ tryCommitHit() → commitHit() oder showConfirmBanner() (Bestätigungs-Modus)

            ↑ Side-Channels
  • KeepAlive-Pings 30 s → ETF150 schläft nicht ein
  • tpBridge.saveJsonToDownloads → /sdcard/Download/
  • Auto-REC: alle Auto-Segment-Clips in vidPaths-Array
```

---

## Pre-Release-Check (NEU v2.3.169)

Vor jedem Tag:
```bash
bash scripts/check_versions.sh
```
Prüft alle 6 Stellen synchron + Hash-Gleichheit Root vs Asset-Kopien. Exit 1 bei Drift.

---

## Hardware-Checkliste für den Stand

- [ ] **Tablet voll geladen** (Display + WLAN + Cam-Pipeline ziehen 2-3 h Akku)
- [ ] **SD-Karte in der ETF150** (mind. 16 GB, FAT32/ExFAT) — Auto-REC braucht sie zwingend
- [ ] **Spektiv-Stativ stabil** (Mikrowackler erzeugen False-Positives)
- [ ] **USB-C-Kabel** falls ADB-Diagnose vor Ort gewünscht
- [ ] **Cam Power-Cycle** vor Setup (clean state)

---

## Was morgen am Stand erstmals zu verifizieren

1. **3-Frame-Confirm-Fix wirkt:** FP-Rate gegenüber 2026-05-12 deutlich runter
2. **Async-PixelCopy:** kein App-Freeze mehr in langen Sessions
3. **Center-of-Mass-Auto-Kalib:** Mitte sitzt
4. **Live-Counter sinnvoll als Feedback** (Tap-Bestätigung darüber im Bestätigungs-Modus)
5. **Mehrere Schüsse in Folge** (Sportpistole-Präzision 5 Schuss / 5 min — entspannt; Schnellfeuer 5 / 4-8 s — Cooldown-Test)
6. **Disziplin-Wechsel mitten in der Session** (KK25 → GK25 oder umgekehrt)
7. **Trainings-Frame-Extraktion** `extract_session.py` mit den vidPaths-Array endlich vollständig (vorher nur 40 s)

---

## Bekannte offene Punkte (kein Showstopper für morgen)

1. **WebView/Bridge sehr offen** (Code-Review P1 #2) — `allowUniversalAccessFromFileURLs`, `setWebContentsDebuggingEnabled(true)`, Dev-HTTP-Server :8090. Funktional kein Problem, Security-Schuld.
2. **Tote Pfade** (Code-Review P2) — `pushFrameToWebView`, `setupImageReader`, ungenutzte ExoPlayer-Deps in `build.gradle`. Cleanup-Sprint nach KK25/GK25-Stabilisierung.
3. **Trefferlogik UI-frei machen** (Code-Review P2) — `TP.detect.findHits({refImage, curImage, calib, disc, settings})` als reine Funktion ohne DOM-Seiteneffekte. Nötig für reproduzierbare Offline-Tests gegen Ground Truth.
4. **Auto-Kalibrierung bei mehreren Scheiben** unzuverlässig — Workaround: am Stand hängt nur eine Scheibe.
5. **Detection-Multi-Trigger 1.5×** aus Laser-Test (2026-05-11) — sollte mit echtem 3-Frame-Fix v2.3.170 weg sein. Bei dann noch FP: Cooldown 300 ms → 1500 ms erwägen.
6. **`setupImageReader()` in `RtspMediaCodecPipeline.kt`** Dead Code seit v2.3.159 — Cleanup ausstehend.

---

## Versionsdateien sync (immer 6 gleichzeitig — Check via scripts/check_versions.sh)

- `index.html` → `APP_VERSION`
- `sw.js` → `CACHE_VER` (`tp-X.Y.Z`)
- `version.json` → `{"version":"X.Y.Z"}`
- `android/app/build.gradle` → `versionName` + `versionCode`
- Asset-Kopien (1:1):
  - `android/app/src/main/assets/trefferpoint/index.html`
  - `android/app/src/main/assets/trefferpoint/sw.js`
  - `android/app/src/main/assets/trefferpoint/version.json`

Release: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z` → GitHub Actions baut APK.
APK ziehen: `gh release download vX.Y.Z --pattern "*.apk" --dir /c/Users/bertm/AppData/Local/Temp/ --clobber` + `adb install -r /c/Users/bertm/AppData/Local/Temp/app-debug.apk`

---

## Tablet-Verbindung & Diagnose

```
ADB-Gerät: ce031823ccfb0432027e (Samsung Tab S6 Lite)
ADB-Tier: "click" — typing in App via Auto-Fill / WebView-DevTools-Bridge-eval
WLAN: ETF150-Hotspot, Gateway 192.168.10.1
ETF150-Status: Snapshot+REC+OSD via /roc/*-API (kein Auth)
Apexel-App: WebSocket-Protokoll auf Port 80, Subprotocol "rocapi", Auth admin:12345678
```

**Diagnose-Toolkit:**
- `adb logcat -v time RtspMediaCodec:V RtspSdpProxy:V TrefferPoint:V *:E` — Pipeline-Stages
- WebView-DevTools-Protocol via `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` → Python `websocket-client` (mit `suppress_origin=True`) → JS-Eval
- `adb shell ls /sdcard/Download/tp_session*.json` — JSON-Save-Verifikation

---

## Sessions in Zahlen

| Zeitabschnitt | Phasen | Versionen | Erkenntnis |
|---|---|---|---|
| 2026-05-10/11 (Lag-Marathon) | Lag-Diagnose + ETF150 Bringup | v2.3.149→167 | CPU-lesbare-Pipeline strukturell zu langsam → SurfaceView |
| 2026-05-12 (1. Stand-Test) | Echte KK25/GK25-Schüsse | v2.3.167 | "Deutlich mehr Hits als Schüsse", App eingefroren, Auto-Kalib zu weit links |
| 2026-05-13 (Code-Review + Fix-Iteration) | Detection-Härtung | v2.3.168→170 | 3-Frame-Confirm-Bug fixed, Async-PixelCopy, Diagnostik |
| 2026-05-14 (2. Stand-Test) | KK25/GK25 mit v2.3.170 | — | Erwartung: drastisch weniger FPs, keine Freezes, korrekte Auto-Kalib |
