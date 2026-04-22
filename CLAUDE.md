# TrefferPoint — Digitale Echtzeit-Schussauswertung

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

## Aktueller Stand (v2.1.0)

**Architektur — Native APK (Android):**
- `android/app/src/main/java/de/bmcservice/trefferpoint/MainActivity.kt` — UVC-Kamera via `com.herohan:UVCAndroid`
- Frames: NV21 → JPEG → Base64 → `webView.evaluateJavascript("window.tpReceiveFrame(...)")`
- `AppLog.kt` Ring-Buffer für Diagnose-Logs ohne ADB
- `@JavascriptInterface TrefferPointBridge`: `getLog()`, `getStatus()`, `getVersion()`, `isAndroidApp()`
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
