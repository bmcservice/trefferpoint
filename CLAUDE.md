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
- **Primärgerät:** Samsung Android-Tablet mit **TrefferPoint-APK** (Zero-Config Plug-and-Play)
- **Alternative:** Windows-Laptop mit USB-Kamera direkt an Chrome

## Feature-Trennung — Developer vs. Public

Das Build-System unterstützt zwei Profile. Per default ist DEBUG=true (Developer-Build);
für Public-Releases später per `BuildConfig.DEBUG=false` deaktivierbar (oder Feature-Flag).

### Public-Features (in jedem Release sichtbar)
- USB-C UVC-Kamera (per `com.herohan:UVCAndroid`)
- Eingebaute Tablet-Kamera (`getUserMedia`)
- WLAN/RTSP-Kamera (SGK GK720X-Familie, eigener MediaCodec-Pfad)
- MJPEG-URL-Eingabe als externer Stream
- Auto-Kalibrierung (PCA-Spiegel-Erkennung + elliptische Ringzählung)
- Manuelle Kalibrierung (2-Klick)
- Treffer-Detection (Frame-Differenz + 2-Frame-Bestätigung + Warmup)
- Sprachausgabe ("X auf Y Uhr")
- Bild-Rotation (0/90/180/270°)
- Disziplin-Auswahl (LG10, LP10, KK25, GK25, KK50)
- Snapshot-Speichern (`Downloads/TrefferPoint/`)
- Mail-Socket-Empfang (REC, Battery, SD-Status der SGK)
- Update-Check via GitHub Releases
- View-Toggle (Kamera | Anzeige | Treffer)

### Developer-Features (nur in DEBUG-Builds aktiv)
- **MJPEG-HTTP-Server auf Port 8090** (`DevHttpServer.kt`)
  - `GET /` → HTML-Embed der Live-Sicht
  - `GET /stream` → multipart/x-mixed-replace MJPEG
  - `GET /snapshot` → einzelnes JPEG
  - `GET /status` → JSON mit Pipeline-Status
  - **Nutzung vom PC:** `adb forward tcp:8090 tcp:8090` → `http://localhost:8090/` im Browser
- **Auto-Diagnose-Snapshot** bei Frame #5 (`diag-frame5_<timestamp>.jpg` in Downloads)
- **Verbose Frame-Logging** (Frame #1-5 + alle 30) im AppLog
- **Test-Pattern-Modus** im GL-Renderer (war v2.3.73 — entfernt nach Diagnose)

### Geplant für Developer (noch nicht gebaut)
- Eventlog-CSV bei Treffererkennung (`eventlog_YYYYMMDD.csv`)
- Debug-Snapshots bei Treffer (pre/post/diff JPEGs)
- Performance-Counter (Frame-Rate, JPEG-Encoding-Zeit, Decoder-Drops)
- Frame-Replay-Modus (statt Live-Stream eine MP4 abspielen — für reproduzierbare Tests)

## Aktueller Stand (v2.3.92 — MediaCodec direkt, kein Crash, kein Pulsieren)

**Aktiver Entwicklungsfokus: KK25 (.22) und GK25 (9mm) — 25 Meter**

### RTSP-Pipeline (Samsung Galaxy Tab S6 Lite, Snapdragon/Adreno) — finale Architektur:

```
SGK GK720X WiFi-Cam
  ├─ HTTP-Wakeup (Port 80)
  ├─ Mail-Socket (Port 6035) → Battery/REC/SD-Status → JS via tpOnSgkMail
  └─ RTSP (Port 554)
       ↓
    RtspSdpProxy (Localhost 15554) — patcht "a=recvonly", korrigiert FU-A SPS→IDR
       ↓
    RtspMediaCodecPipeline — eigener RTSP-Client (DESCRIBE/SETUP/PLAY auf Localhost:15554)
       ├─ RTP-Reader-Thread + FU-A-Reassembler
       ├─ MediaCodec.createByCodecName("c2.android.avc.decoder")
       │  └─ configure(SPS+PPS aus sprop-parameter-sets als CSD)
       ├─ setVideoSurface(imageReader.surface)
       └─ Output-Loop: dequeueOutputBuffer + releaseOutputBuffer(render=true)
       ↓
    ImageReader 960×540 YUV_420_888 (maxImages=6, USAGE_CPU_READ_OFTEN)
       ↓
    yuv420ToNv21 → YuvImage.compressToJpeg(quality=80)
       ↓
    onJpegFrame → MainActivity.pushFrameToWebView
       ↓
    Base64 → webView.evaluateJavascript("window.tpReceiveFrame(...)")
       ↓
    JS: atob → Blob → URL.createObjectURL → imgEl.src
       ↓
    loop() (akzeptiert inputMode='rtsp') → canvas.drawImage(imgEl)
```

### Schlüssel-Erkenntnisse aus v2.3.65–92 Versuchsreihe

1. **Adreno HW-Decoder verweigert CPU-lesbare Surfaces**: `OMX.qcom.video.decoder.avc` rendert ausschließlich in Hardware-Overlay. Bewiesen durch Test-Pattern in v2.3.73 (GL-Pipeline OK, OES-Texture leer) und ImageReader v2.3.74-76 (auch mit `USAGE_CPU_READ_OFTEN` nur 1 Frame).
2. **SW-Decoder + Synchronous-Adapter blockiert**: `IllegalStateException` bei `dequeueInputBuffer` bei jedem Init nach Frame 1. Lösung: `forceEnableMediaCodecAsynchronousQueueing()`.
3. **Audio-Track destabilisiert Video-Decoder**: g711-alaw Audio-Track muss explizit per `setRendererDisabled(C.TRACK_TYPE_AUDIO, true)` deaktiviert werden, sonst `CodecException 0x80000000` nach Frame 1. (In v2.3.88+ irrelevant — wir setupen nur Video-Track.)
4. **JS-Loop akzeptierte `inputMode='rtsp'` nicht**: Display-Bug v2.3.84 — Loop returned weil Bedingung nur `'stream'` zuließ. Fix: `inputMode === 'rtsp'` auch zulassen.
5. **ExoPlayer + IDR-Reset = Crash, MediaCodec direkt + IDR-Reset = OK**: `c2.android.avc.decoder` verträgt H.264-spec-konforme IDR-Resets, wenn er direkt mit kompletten NAL-Units gefüttert wird. Im ExoPlayer-Pfad crashte er alle ~11s. Im direkten Pfad kein Crash, kein flush() nötig.
6. **frame_num-Smoothing brach Reference-Picture-DPB**: v2.3.86 verhinderte den Crash, aber modifizierte IDRs verwirren das Reference-Picture-Management → grün-korrumpierte Bilder. Spec-Verstoß ist nicht der richtige Weg.

### Verworfene Pfade (nicht mehr nutzen)
- TextureView.getBitmap() — Samsung HW-Layer-Bug (schwarz)
- PixelCopy(SurfaceView) + HW-Decoder — HW-Overlay-Bypass (schwarz)
- PixelCopy(window, srcRect) + HW-Decoder — liest WebView-UI rekursiv (UI-Spiegelung)
- GLSurfaceView + SurfaceTexture + samplerExternalOES — Decoder schreibt nicht in OES-Texture (uniform grün)
- ImageReader + HW-Decoder + `USAGE_CPU_READ_OFTEN` — 1 Frame/Session (Hardware-Overlay-Bypass)
- ImageReader + SW-Decoder mit Audio-Track aktiv — 1 Frame/Session (Audio-Decoder destabilisiert)
- ImageReader + SW-Decoder mit Sync-Adapter — IllegalStateException bei dequeueInputBuffer
- ExoPlayer + SW-Decoder + ImageReader (v2.3.82-87) — Crash alle ~11s an IDR-Boundaries
- H264 frame_num-Smoothing im RtspSdpProxy — bricht Reference-Picture-Management
- MediaCodec direkt + Async-JPEG-Encoder-Thread — Decoder-Output-Race, dequeueOutputBuffer-Exceptions

### Aktive Files
- `RtspMediaCodecPipeline.kt` (ersetzt RtspPipeline.kt + RtspGlRenderer.kt + RtspImageReaderPipeline.kt)
- `RtspSdpProxy.kt` (Bug-Fixes für a=recvonly, FU-A SPS→IDR, SPS/PPS-Dedup, Seq/TS-Continuity)
- `MainActivity.kt`
- `activity_main.xml` — nur WebView, kein SurfaceView/GLSurfaceView mehr

### Offen
- Treffererkennung-Algorithmus: noch nicht aktiviert (RTSP-Pipeline jetzt stabil als Voraussetzung erfüllt)
- Bild-Rotation der Spektiv-Kamera (Hochformat) per "BILD DREHEN"-Button
- Frame-Rate-Optimierung (~15fps statt 30fps) — JPEG-Encoding-Bottleneck, für Trefferdetection ausreichend

**Architektur — Native APK (Android):**
- `android/app/src/main/java/de/bmcservice/trefferpoint/MainActivity.kt` — UVC-Kamera via `com.herohan:UVCAndroid`
- Frames: NV21 → JPEG → Base64 → `webView.evaluateJavascript("window.tpReceiveFrame(...)")`
- `AppLog.kt` Ring-Buffer für Diagnose-Logs ohne ADB
- `@JavascriptInterface TrefferPointBridge`: `getLog()`, `getStatus()`, `getVersion()`, `isAndroidApp()`, `getWifiGateway()`, `startRtsp()`, `stopRtsp()`, `speak()`
- Drei Kamera-Modi: USB-C UVC (UVCAndroid), Eingebaute Kamera (getUserMedia), RTSP/WiFi (ExoPlayer)
- WebView lädt `file:///android_asset/trefferpoint/index.html` — komplette TrefferPoint-Logik
- Build via GitHub Actions (`.github/workflows/build-android.yml`), fester Signing-Keystore committed
- Bei Git-Tag `vX.Y.Z` → automatisches GitHub Release mit APK

**Architektur — Web/PWA:**
- Selbe `index.html`, gehostet auf `https://bmcservice.github.io/trefferpoint`
- Kamera-Input via `getUserMedia` (USB/Webcam) oder externer MJPEG-Stream via URL
- Service Worker für Offline-Cache (nicht im APK — unter file:// nicht unterstützt)

## Legacy / historisch relevant (alle abgelöst)

Vor v2.1.0 waren wir bei einer HTML-Einzeldatei + Simple HTTP Server + USB Dual Cam (3 Apps) bzw. kurz bei einer APK mit internem NanoHTTPD (fragil wegen WebView↔HTTP-Race-Conditions). Die JS-Bridge in v2.1.0 hat das beides ersetzt.

Die App ist eine einzelne HTML-Datei (`trefferpoint_v2.0.6.html`) mit:
- Kamera-Input via getUserMedia (USB-C UVC) oder Stream-URL (MJPEG)
- **Auto-Kalibrierung** per PCA/Kovarianzmatrix → erkennt Spiegel, fittet Ellipse
- **Elliptische Ringzählung** — perspektivkorrigiert über Halbachsen a/b + Drehwinkel
- **Frame-Differenz-Erkennung** mit 2-Frame-Bestätigung (Anti-Flatter)
- **Warmup-Periode** (60 Frames) nach Referenzframe-Setzen — Kamera stabilisiert sich
- **Kamera-Rotation** ↻-Button: 0/90/180/270° Software-Rotation für falsch orientierte Kamerabilder
- **Synthetische Scheiben** (DSB-normiert) als Vorschau und Overlay
- **Akustische Trefferansage** per Web Speech API: "7 auf 3 Uhr" (An/Aus-Toggle)
- **Debug-Overlay**: zeigt erkannte Änderungsfläche mit Pixelzahl
- **"Neuer Spiegel"-Button** für schnellen Referenzwechsel während laufender Erkennung
- **PWA manifest** inline (Blob-URL) → installierbar auf Android
- SW-Zoom, Kamera-Controls, D-Pad für manuelle Kalibrierung
- Responsives Layout (Quer-/Hochformat)
- **View-Toggle** (📷 Kamera | 📊 Treffer): Kamera läuft im Hintergrund, Treffer-Vollansicht zeigt Gesamt/Anzahl/Durchschnitt/Letzter Treffer + scrollbare Trefferliste
- Touch-freundliche Buttons (min-height 44px)
- **Richtungspfeil** in Trefferliste: rotierter ↑-Pfeil zeigt Abweichungsrichtung (passend zur Sprachausgabe "X auf Y Uhr")
- **Letzter Treffer farblich hervorgehoben**: gold (#c8a84b, größer) auf Kamerabild; vorige Treffer rot

## Disziplinen (v2.0)

| ID | Name | Entfernung | Spiegel | Kaliber |
|---|---|---|---|---|
| lg10 | Luftgewehr 10m | 10m | ∅ 45.5mm (alle Ringe schwarz) | 4.5mm |
| lp10 | Luftpistole 10m | 10m | ∅ 59.5mm (Ringe 7–10 schwarz) | 4.5mm |
| kk25 | Sportpistole 25m (KK) | 25m | ∅ 200mm (Ringe 7–10 schwarz) | 5.6mm |
| gk25 | Pistole 25m (9mm GK) | 25m | ∅ 200mm (Ringe 7–10 schwarz) | 9.0mm |
| kk50 | KK-Gewehr 50m liegend | 50m | ∅ 50.4mm (Ringe 6–10 schwarz) | 5.6mm |

## DSB Scheiben-Normen — verifiziert gegen DSB-Sportordnung 01.01.2026

`outer_mm` = Spiegel-Außenradius (= was die Auto-Kalibrierung als dunkle Fläche erkennt)

### LG 10m (Krüger 1300, DSB-Signum) — Scheibe Nr. 1
Kaliber 4.5mm | outer_mm: 22.75 | Ring 10: ∅0.5mm | Schritt: 2.5mm/Ring | Ring 1: ∅45.5mm
`10: 0.25 | 9: 2.75 | 8: 5.25 | 7: 7.75 | 6: 10.25 | 5: 12.75 | 4: 15.25 | 3: 17.75 | 2: 20.25 | 1: 22.75`

### LP 10m (Krüger 3000, DSB-Signum) — Scheibe Nr. 9
Kaliber 4.5mm | outer_mm: 29.75 | Ring 10: ∅11.5mm | Schritt: 8mm/Ring | Ring 1: ∅155.5mm | Spiegel ∅59.5mm
`10: 5.75 | 9: 13.75 | 8: 21.75 | 7: 29.75 | 6: 37.75 | 5: 45.75 | 4: 53.75 | 3: 61.75 | 2: 69.75 | 1: 77.75`

### KK 25m Sportpistole (Krüger 3100S, DSB-Signum) — Scheibe Nr. 4
Kaliber 5.6mm | outer_mm: 100.0 | Ring 10: ∅50mm | Schritt: 25mm/Ring | Ring 1: ∅500mm | Spiegel ∅200mm
`10: 25.0 | 9: 50.0 | 8: 75.0 | 7: 100.0 | 6: 125.0 | 5: 150.0 | 4: 175.0 | 3: 200.0 | 2: 225.0 | 1: 250.0`

### KK-Gewehr 50m liegend (DSB-Signum) — Scheibe Nr. 3
Kaliber 5.6mm | outer_mm: 25.2 | Ring 10: ∅10.4mm | Schritt: 5mm/Ring | Ring 1: ∅100.4mm | Spiegel ∅50.4mm
`10: 5.2 | 9: 10.2 | 8: 15.2 | 7: 20.2 | 6: 25.2 | 5: 30.2 | 4: 35.2 | 3: 40.2 | 2: 45.2 | 1: 50.2`
⚠ Ringabstand 5mm/Ring = ISSF-Standard; vor v2.x-Release gegen DSB-Sportordnung prüfen

## Bekannte Probleme / offene Punkte

1. **Zweizonen-Erkennung fehlt noch** — schwarzer Spiegel (hell→dunkel) vs. weißer Bereich (dunkel→hell) unterschiedlich behandeln
2. **Service Worker fehlt** — für echtes Offline-Caching nötig; braucht separate `sw.js`-Datei (Einzeldatei-Constraint); derzeit nur manifest (installierbar, aber kein Offline-Cache)
3. **KK 50m Ringabstand unbestätigt** — 5mm/Ring nach ISSF-Standard; vor Praxiseinsatz verifizieren
4. **Kamera-Rotation** — "↻ Bild drehen"-Button vorhanden; muss beim Disziplinwechsel/Kalibrierung berücksichtigt werden (Kalibrierung wird beim Drehen auto-zurückgesetzt)

## Sprint 3 Aufgaben

### Priorität 1 — Genauigkeit
- [ ] Zweizonen-Erkennung: schwarzer Spiegel (Helligkeit steigt = Loch) + weißer Bereich (Helligkeit sinkt = Loch)
- [ ] Zehntelwertung (1 Dezimalstelle) aus elliptischem Abstand

### Priorität 2 — Seriemanagement
- [ ] Seriengröße wählbar (10, 30, 40, 60 Schuss)
- [ ] Probeschuss / Wertungsschuss trennen
- [ ] Export: CSV / Screenshot mit Trefferbild

### Priorität 3 — Service Worker
- [ ] `sw.js` erzeugen und einbinden (erfordert Umstieg auf zwei Dateien oder Build-Step)

## Technische Constraints

- **Eine HTML-Datei** — kein Build-System, kein npm, kein Framework
- **Komplett offline** — keine CDN-Links, alle Libraries gebundled (Fonts als woff2/Base64 eingebettet)
- **Kein Backend** — alles läuft im Browser
- **Versionierung** im Dateinamen: `trefferpoint_vX.Y.Z.html`
- **`index.html`** = immer Kopie der aktuellen Version → feste URL `http://localhost:8080/` auf dem Tablet. Bei neuem Release: `index.html` überschreiben. Versionierte Dateien bleiben als Backup.

## Nutzer-Kontext

- Bert, Sportschütze (DSB), Saarland
- Disziplin: 1.56 Unterhebelrepetierer GK 50m (kommt in v3.x)
- Primäres Testgerät: Android Tablet + USB-C Okularkamera am Spektiv
- Scheiben: Krüger DSB-Signum
