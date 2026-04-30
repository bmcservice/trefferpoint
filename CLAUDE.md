# TrefferPoint — Digitale Echtzeit-Schussauswertung

## ⚡ Kritische Regeln (immer beachten)

1. **Version synchron halten** — 4 Dateien bei jeder Änderung: `index.html` (APP_VERSION), `sw.js` (CACHE_VER), `version.json`, `android/app/build.gradle` (versionName + versionCode)
2. **`index.html` = Single Source of Truth** — bei Änderung: Kopie in `android/app/src/main/assets/trefferpoint/` UND `G:\Meine Ablage\Claude\`
3. **Eskalation:** Mehr als 3 Commits ohne messbaren Fortschritt → `/codex:rescue` aufrufen, nicht weiterwursteln
4. **Release:** `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z` → GitHub Actions baut APK automatisch
5. **Komplett offline** — keine CDN-Links, kein npm, kein Build-System; alles gebundled

## 🤝 Session-Handoff

Beim Session-Start: Wenn `~/.claude/.handoff-pending` existiert → `HANDOFF.md` im Projekt-Root lesen und als Working-Context nutzen, dann Flag löschen: `rm ~/.claude/.handoff-pending`

Zum Session-Abschluss: `/handoff` ausführen → schreibt aktuellen Problemstand in `HANDOFF.md`

## Projektübersicht

TrefferPoint ist eine Echtzeit-Treffererkennung und Ringzählung für Sportschützen (DSB/ISSF). Es gibt zwei Varianten:
- **Native Android-APK** (empfohlen für Tablet mit USB-C Okularkamera, ab v2.1.0)
- **Web/PWA** (für PC/Laptop mit USB-Webcam in Chrome, oder externe Stream-Quellen)

Gemeinsame TrefferPoint-Logik (Erkennung, Kalibrierung, Trefferauswertung) in `index.html` — im APK als Asset eingebettet.

## Hardware-Setup

- **Kamera:** USB-C Okularkamera (Mustcam 20x Digital Telescope, UVC, 1920×1080)
- **Optik:** Spektiv mit Okularkamera-Aufsatz, auf Schießscheibe ausgerichtet
- **Primärgerät:** Samsung Galaxy Tab S6 Lite mit **TrefferPoint-APK**
- **Alternative Kamera:** SGK GK720X WiFi-Kamera (RTSP) oder eingebaute Tablet-Kamera

## Aktueller Stand (v2.3.131, 2026-04-30)

**Aktiver Entwicklungsfokus: Erste valide Trefferauswertung — KK25 (.22) und GK25 (9mm) @ 25 Meter**

### Was stabil ist
- **USB-C UVC-Kamera** (Mustcam via UVCAndroid) — Frames kommen zuverlässig
- **RTSP-Pipeline** (SGK GK720X via RtspMediaCodecPipeline + RtspSdpProxy) — SW-Decoder + ImageReader, kein Crash
- **Kalibrierung:**
  - Auto: RANSAC-Kreisfit + Ellipsen-Fit + Outward-Gradient-Filter (nur äußerer Spiegel-Rand)
  - Manuell 3-Klick: Mitte → X-Achse (3 Uhr) → Y-Achse (6 Uhr) → echte Ellipse (a, b, angle)
  - Zoom-unabhängig: Koordinaten in Quell-Pixel-Space, `_calibFromSrc`/`_calibToSrc`
- **Ring-Overlay:** `strokeRing()` mit korrekter Ellipsen-Formel — `ra = dist * pxPerMm * (a/√(a·b))`
- **setRef()** funktioniert für alle Modi (USB, Stream, RTSP)
- **UI:** Panel-Collapse, Header-Minimize, Pinch-to-Zoom, D-Pad verschiebbar, Touch-Guard auf ℹ-Button

### Offener Haupt-Blocker: pxPerMm zu niedrig
- Aktuell bei 25m: **~0.93 px/mm** (Spiegel nimmt ~19% der Bildbreite ein)
- Benötigt: **≥ 1.4 px/mm** für KK25 (5.6mm) / **≥ 1.7 px/mm** für GK25 (9mm)
- Fix: Optischen Zoom am Spektiv erhöhen (~1.9×) → Hardware-Action

## Roadmap: Erste valide Trefferauswertung

### Schritt 1 — Kalibrierung verifizieren (sofort, leerer Spiegel)
- [ ] Auto-Kalibrierung starten → Ringe-Overlay sitzt auf Spiegel-Rand?
- [ ] pxPerMm ablesen (Ziel: ≥ 1.4)
- [ ] Falls pxPerMm < 1.4: Spektiv-Zoom erhöhen, neu kalibrieren

### Schritt 2 — Zoom-Hardware anpassen
- [ ] Optischen Zoom erhöhen bis pxPerMm ≥ 1.4 (KK25) oder ≥ 1.7 (GK25)
- [ ] Kalibrierung danach nochmal prüfen (Ringe auf Scheibe?)

### Schritt 3 — Erste Erkennung
- [ ] Referenzframe setzen (leere Scheibe, nach Warmup)
- [ ] Schuss abgeben → Trefferauswertung läuft durch?
- [ ] Ring und Uhrzeit prüfen gegen tatsächlichen Einschlag

### Schritt 4 — Korrektheit verbessern (nach erstem erfolgreichen Treffer)
- [ ] Zweizonen-Erkennung: schwarzer Spiegel (Helligkeit steigt = Loch) vs. weißer Bereich
- [ ] Zehntelwertung aus elliptischem Abstand (1 Dezimalstelle)
- [ ] Seriemanagement: 10/30/40/60 Schuss, Probe vs. Wertung, CSV-Export

## Architektur — Native APK

- `android/app/src/main/java/de/bmcservice/trefferpoint/`
  - `MainActivity.kt` — USB-C UVC via UVCAndroid, WebView, JS-Bridge
  - `RtspMediaCodecPipeline.kt` — eigener RTSP-Client + MediaCodec direkt (SW-Decoder)
  - `RtspSdpProxy.kt` — korrigiert FU-A SPS→IDR, Seq/TS-Continuity, a=recvonly
  - `AppLog.kt` — Ring-Buffer für Diagnose-Logs ohne ADB
- Frame-Pfad: Kamera → NV21/YUV → JPEG → Base64 → `webView.evaluateJavascript("window.tpReceiveFrame(b64)")`
- `@JavascriptInterface TrefferPointBridge`: `getLog()`, `getStatus()`, `getVersion()`, `isAndroidApp()`, `getWifiGateway()`, `startRtsp()`, `stopRtsp()`, `speak()`
- WebView lädt `file:///android_asset/trefferpoint/index.html`
- Build via GitHub Actions, fester Keystore committed → APK-Updates ohne Deinstall

### RTSP (SGK GK720X / Viidure)
- SW-Decoder `c2.android.avc.decoder` + ImageReader YUV_420_888 (960×540, maxImages=6)
- HW-Decoder (`OMX.qcom.video.decoder.avc`) **nicht nutzbar** für CPU-Zugriff auf Adreno — rendert ausschließlich in HW-Overlay
- SdpProxy patcht a=recvonly, korrigiert FU-A(SPS)→IDR, hält Seq/TS-Continuity
- SGK Gateway: `192.168.0.1`, RTSP: `rtsp://192.168.0.1/live/tcp/ch1`, Mail-Socket Port 6035

## Architektur — Web/PWA

- Selbe `index.html`, gehostet auf `https://bmcservice.github.io/trefferpoint`
- Kamera-Input via `getUserMedia` (USB/Webcam) oder externer MJPEG-Stream via URL
- Service Worker (`sw.js`) für Offline-Cache

## Disziplinen

| ID | Name | Entfernung | Spiegel-Ø | Kaliber | outer_mm |
|---|---|---|---|---|---|
| lg10 | Luftgewehr 10m | 10m | 45.5mm | 4.5mm | 22.75 |
| lp10 | Luftpistole 10m | 10m | 59.5mm | 4.5mm | 29.75 |
| kk25 | Sportpistole 25m (KK) | 25m | 200mm | 5.6mm | 100.0 |
| gk25 | Pistole 25m (9mm GK) | 25m | 200mm | 9.0mm | 100.0 |
| kk50 | KK-Gewehr 50m liegend | 50m | 50.4mm | 5.6mm | 25.2 |

## DSB Scheiben-Normen (verifiziert gegen DSB-Sportordnung 01.01.2026)

`outer_mm` = Spiegel-Außenradius (= dunkle Fläche, die Auto-Kalibrierung erkennt)

### LG 10m: Schritte 2.5mm/Ring | `10: 0.25 | 9: 2.75 | 8: 5.25 | 7: 7.75 | 6: 10.25 | 5: 12.75 | 4: 15.25 | 3: 17.75 | 2: 20.25 | 1: 22.75`
### LP 10m: Schritte 8mm/Ring | `10: 5.75 | 9: 13.75 | 8: 21.75 | 7: 29.75 | 6: 37.75 | 5: 45.75 | 4: 53.75 | 3: 61.75 | 2: 69.75 | 1: 77.75`
### KK 25m: Schritte 25mm/Ring | `10: 25.0 | 9: 50.0 | 8: 75.0 | 7: 100.0 | 6: 125.0 | 5: 150.0 | 4: 175.0 | 3: 200.0 | 2: 225.0 | 1: 250.0`
### KK 50m: Schritte 5mm/Ring | `10: 5.2 | 9: 10.2 | 8: 15.2 | 7: 20.2 | 6: 25.2 | 5: 30.2 | 4: 35.2 | 3: 40.2 | 2: 45.2 | 1: 50.2`

## Technische Constraints

- **`index.html` = Single Source of Truth** — kein Build-System, kein npm, kein Framework
- **Komplett offline** — keine CDN-Links, alle Libraries gebundled (Fonts als woff2/Base64)
- **Kein Backend** — alles läuft im Browser / WebView
- **Kein gradlew.bat im Repo** → APK nur via GitHub Actions

## Nutzer-Kontext

- Bert, Sportschütze (DSB), Saarland
- Disziplin: 1.56 Unterhebelrepetierer GK 50m (kommt in v3.x)
- Primäres Testgerät: Samsung Tab S6 Lite + USB-C Okularkamera am Spektiv
- Scheiben: Krüger DSB-Signum
