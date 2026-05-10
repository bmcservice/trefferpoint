# TrefferPoint — Session Handoff
_Stand: 2026-05-10, Vormittag — neue Cam Apexel ETF150 angeschlossen, Stream zeigt kein Bild_

---

## Aktueller Stand

**Repo: v2.3.149** (committed, getaggt, gepusht, GitHub-Release vorhanden)
**APK installiert:** versionName 2.3.149 / versionCode 153 (Tablet `ce031823ccfb0432027e`)
**Hardware-Wechsel heute:** Apexel ETF150 WiFi-Kamera ersetzt SGK GK720X / Viidure
**Status der ETF150-Pipeline: Stream zeigt kein Bild** ⚠️

Heute wurden 5 Bring-up-Releases (v2.3.145–149) für die ETF150 ausgeliefert. Snapshot
und Video-Aufnahme über plain HTTP funktionieren laut Commits. RTSP-Live-Stream sollte
nach v2.3.149 (SETUP-URL aus SDP, SPS/PPS lazy aus Stream) eigentlich laufen — tut er
aber nicht. **Dieser Bug ist der einzige aktive Blocker.** Die vorige Session ist bei der
Diagnose abgestürzt (Screenshot-Tool → API-400 "Could not process image").

---

## Apexel ETF150 — Hardware-Spezifikation

| Aspekt | Wert |
|---|---|
| WLAN-IP | `192.168.10.1` (eigenes WLAN, Tablet muss verbunden sein) |
| Auth | **Keine** — alle Endpoints offen |
| RTSP | `rtsp://192.168.10.1/live/?ctype=video` (resolved aus SDP `Content-Base` + `a=control`) |
| Snapshot | `POST /roc/photo/capture` → JSON mit `filelist[].origin = /roc/file/<datum>/IMGxxxxx.jpg`; File-Download via `GET /roc/file/<path>` |
| Video-REC | `POST /roc/record/start` und `POST /roc/record/stop` → `/roc/file/<datum>/VIDxxxx.mp4` auf SD-Karte |
| SDP-Quirk | **Kein** `sprop-parameter-sets` im SDP — SPS/PPS kommen inline im Stream als NAL 7/8 oder STAP-A |
| Auflösung | aus Stream-SPS (nicht SDP) — Fallback 960×540 |

---

## Was an der ETF150 funktioniert

### v2.3.143 — Erste Integration
Auto-Fill-Erkennung des ETF150-Netzes (`192.168.10.1`), Cam-Modus-Toggle.

### v2.3.144 — Snapshot mit SD-Karte
- `index.html` Foto-Button zeigt Dateiname + Größe nach Capture
- `training_data/server.py` `/cam/snap?type=etf150`: nutzt Capture-Response → File-Download via adb shell curl + adb pull (kein Gallery-Scan)

### v2.3.145 — Video-Aufnahme
- ETF150 REC-Buttons (⏺/⏹) plain-HTTP fetch direkt zur Cam
- Status zeigt "REC läuft" / "Aufnahme beendet"
- Damit Funktionsbreite gleich Viidure SGK: Live-Stream + Foto + Video, alles ohne Auth über plain HTTP

### v2.3.146 — Auto-REC + Hit-Timestamps (Trainings-Workflow)
- "🎬 Auto-REC bei Erkennung"-Toggle: Klick auf "Erkennung starten" → ETF150-Aufnahme startet
- Jeder detektierte Treffer → relativer `t_ms` zusätzlich in `recSession.hits`
- Stop → JSON mit Disziplin, Kalib-Snapshot, Hits (t_ms, x/y, Ring mit Zehntel, Distanz, Winkel) + Video-Pfad + `adb_pull_cmd`
- VID-Pfad wird per HEAD-Probing ermittelt (max VID-Nummer vor Start, dann nach Stop → Diff)
- Neu: `training_data/extract_session.py` — pullt Video, schneidet ±400ms-Fenster pro Hit mit ffmpeg, exportiert benannte Frames `hit_NN_t<ms>_R<ring>_FF.jpg`
- **Damit Pipeline für validierte Trainingsdaten geschlossen** (sobald Live-Stream geht)

### v2.3.147 — Drei kleine Bugfixes
1. **Komma-Sanitize**: Deutsche Locale konnte `192.168.10,1` in localStorage schreiben → "Unable to resolve host". Jetzt an drei Stellen (Auto-Fill, Start-Klick, Restore) Komma in Zifferngruppe → Punkt
2. **Auto-Fill-Override**: Wenn ETF-Netz erkannt, aber Input-URL passt nicht zu `192.168.10.x` → automatisch überschreiben + persistieren
3. **HD/SD-Toggle**: ETF-Netz erkannt → Toggle immer aus, unabhängig vom Input-Wert

### v2.3.148 — SPS/PPS aus Stream
ETF150 sendet keine `sprop-parameter-sets` im SDP. Pipeline jetzt zweistufig:
- SDP-CSD vorhanden (Viidure SGK) → ImageReader+Decoder direkt konfigurieren
- SDP-CSD fehlt (Apexel ETF150) → Lazy-Konfig nach Empfang von SPS+PPS im Stream
- Frames vor `decoder!=null` werden verworfen (typisch nur erste paar ms vor erstem I-Frame)
- Neu in `RtspMediaCodecPipeline.kt`:
  - `captureCsdNal()` sammelt SPS/PPS, triggert Lazy-Konfig
  - `parseStapAForCsd()` zerlegt STAP-A Aggregate
  - Auflösung aus Stream-SPS

### v2.3.149 — SETUP-URL aus SDP
Pipeline scheiterte mit "SETUP keine Session-ID" weil URL-Generierung SGK-spezifisch war (`$streamUrl/video/track0`). ETF150 nutzt im SDP:
```
Content-Base: rtsp://192.168.10.1/live/
m=video ...
a=control:?ctype=video
```
→ resolved SETUP-URL: `rtsp://192.168.10.1/live/?ctype=video`
- Neu: `resolveVideoControlUrl()` parst `Content-Base` + `a=control` aus DESCRIBE-Response, resolved via `java.net.URI.resolve()` RFC-3986
- Fallback `$streamUrl/video/track0` für SGK-Kompatibilität wenn SDP nichts liefert
- JS: striktes `^rtsp://192.168.10.1/live/?$` Pattern, `setRtspChannel()` ignoriert ETF-URLs, localStorage-Restore säubert ETF-URLs mit Channel-Suffix

---

## Aktiver Blocker

**ETF150-Stream zeigt im TrefferPoint kein Bild.** Trotz aller heute gefixten Bugs:
- SETUP läuft offenbar durch (sonst gäbe es laute Fehlermeldung — zu verifizieren)
- SPS/PPS-Lazy-Konfig sollte greifen
- ImageReader sollte Frames liefern → JPEG → `onJpegFrame` callback → `tpReceiveFrame` im WebView

Aber irgendwo bricht die Kette. **Diagnose ist offen.**

### Diagnose-Plan ohne Screenshots (nur ADB + Source-Lesen)

**Wichtige Regel: Keine Screenshot-Tools, keine Bildverarbeitung, kein Computer-Use** — vorige Session ist genau daran gecrasht. Nur Bash, Read, Edit, Grep, ADB-Textbefehle.

1. **Live-Logcat während Test laufen lassen** in einem Bash-Background-Job:
   ```bash
   adb logcat -c   # clear
   adb logcat -v time RtspMediaCodec:D RtspSdpProxy:D AppLog:D MainActivity:D *:E > /tmp/tp.log &
   ```
   User: ETF150-WLAN aktiv, App öffnen, "WLAN-CAM"-Modus, "Stream starten".

2. **Zeitstempel-getriebene Analyse:** Welche Stages erreicht?
   - `"Verbinde via Proxy"` ✓?
   - `"Stream verbunden"` (PLAY 200) ✓?
   - SPS+PPS empfangen (logs in `captureCsdNal`)?
   - `decoder!!.configure(format, imageReader!!.surface, null, 0)` durchgelaufen?
   - `onImageAvailable` in ImageReader feuert?
   - `onJpegFrame` Callback wird aufgerufen?
   - JS-Side: `window.tpReceiveFrame` empfängt Base64?

3. **Hypothesen-Liste (ranked)**:
   - **a)** RtspSdpProxy verbindet zur ETF150 noch nicht — Proxy ist auf SGK-Pfad ausgelegt; ETF150 könnte abweichende OPTIONS/DESCRIBE/SETUP-Reihenfolge erfordern
   - **b)** SDP-Parse für SETUP-URL liefert leeren String / falsche URL — `resolveVideoControlUrl()` schlägt still fehl
   - **c)** STAP-A-Parsing in `parseStapAForCsd()` extrahiert SPS/PPS nicht korrekt → Lazy-Konfig wird nie getriggert
   - **d)** Decoder konfiguriert, aber ImageReader bleibt leer (HW-Decoder-Adreno-Problem aus alten Lessons → wäre dann Regression)
   - **e)** Frames kommen, aber `webView.evaluateJavascript("window.tpReceiveFrame(...)")` läuft im falschen Thread oder vor WebView-Bereitschaft

4. **Erste Action:** Logcat-Dump aus laufendem Test (User triggert, ich analysiere); danach gezielt die schwächste Stage angehen. **Nicht raten und herumpatchen.**

---

## Konkreter nächster Schritt

User aktiviert ETF150-WLAN, öffnet TrefferPoint, **bevor** er auf "Stream starten" klickt:

```bash
adb logcat -c
adb logcat -v time | tee /tmp/tp.log
```

Dann "Stream starten", ~20 s laufen lassen, abbrechen. Inhalt von `/tmp/tp.log` an mich geben (oder ich ziehe selbst). Anhand der Logs identifiziere ich, an welcher Stage die Pipeline stehen bleibt.

---

## Was nicht (mehr) zu tun ist

- ~~Screenshots vom Tablet ziehen und mit Bild-API analysieren~~ → API-Fehler, vorige Session crashed
- ~~Per Computer-Use UI öffnen / klicken~~ → User-Regel: nur ADB-Text in dieser Session
- ~~Memory project_trefferpoint.md / MEMORY.md vertrauen~~ → 10 Tage alt, Stand v2.3.131 (= überholt; ETF150 dort nicht erwähnt)
- ~~Worktree `claude/nervous-tu-2286b6` nutzen~~ → sitzt auf v2.3.41-Basis mit verwaisten v2.3.131-Mods, irrelevant für main

---

## Tablet-Verbindung

```
ADB-Gerät: ce031823ccfb0432027e (Samsung Tab S6 Lite)
Modus: tier "click" — typing/right-click blockiert; für Text in App: WebView-eval via Bridge oder Auto-Fill nutzen
```

---

## Versionsdateien sync (immer 4 gleichzeitig)

- `index.html` → `APP_VERSION`
- `sw.js` → `CACHE_VER`
- `version.json`
- `android/app/build.gradle` → `versionName` + `versionCode`

Bei `index.html`-Änderung zusätzlich: `android/app/src/main/assets/trefferpoint/index.html` UND `G:\Meine Ablage\Claude\`.

Release: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z` → GitHub Actions baut APK.
APK ziehen: `gh release download vX.Y.Z --pattern "*.apk"` + `adb install -r`.
