# TrefferPoint — Session Handoff
_Stand: 2026-04-28, Ende der Nachmittagssession_

---

## Aktueller Stand

**Letzte Version im Repo: v2.3.117** (committed + gepusht + Tag gesetzt)  
**APK-Build: NOCH NICHT INSTALLIERT**

GitHub Actions hing >20 min in der Queue (wahrscheinlich Runner-Stau).
Builds wurden gecancelt. APK muss beim nächsten Start gebaut + installiert werden.

---

## Was heute gemacht wurde

### v2.3.116
- Per-disc `minPtsFactor`: kk25=0.75 (für kleine .22-Cluster), gk25=1.0 (Standard)
- `buildCamControls()` auch beim STREAM/WLAN-Modus aufgerufen (shutterPresets erschienen vorher nicht)

### v2.3.117 (fertig, APK fehlt noch)
- **⚙ Kamera Quick-Panel** direkt auf dem Kamerabild — oben rechts immer erreichbar
- Floating Panel: SW-Zoom, HW-Regler (Helligkeit/Kontrast/EV/Zoom/Schärfe/Farbtemp/Fokus), Auto-Optimieren, Android-Shutter-Presets (10/20/33ms + Auto), Gain-Regler
- Bidirektionale Sync Side-Panel ↔ Quick-Panel via `syncCamVal()` + `applySwZoom()`
- `buildCamControls()` rendert gleichzeitig in `#camControls` UND `#camQuickControls`
- `initShutterPresets()` initialisiert auch Quick-Panel-Buttons (`.qshutter-btn`)

### 9mm Gegenprobe (heute durchgeführt — Ergebnis: OFFEN)
Live-Test war nicht auswertbar wegen zwei überlagerten Problemen:
1. **Scheibe pendelte** (Zentrum 170px versetzt, Drift-Maske 88%) → Re-Align abgelehnt (>30px)
2. **MIN_PTS zu niedrig:** pxPerMm=1.41 live → MIN_PTS=14; offline war pxPerMm=1.84 → MIN_PTS=24
   Ergebnis: 103 Treffer bei th=28, Plateau bei ~60 auch bei hohem Threshold → unbrauchbar

---

## Offene Aufgaben

### 1. v2.3.117 APK installieren (beim nächsten Start als erstes)

**Option A: GitHub Actions neu triggern**
```bash
cd "C:/Users/bertm/OneDrive/Dokumente/Claude/trefferpoint"
gh run list --limit 3   # prüfen ob schon ein Run läuft
# falls nicht: Tag neu pushen
git push origin :v2.3.117 && git push origin v2.3.117
# sobald fertig:
gh release download v2.3.117 --pattern "*.apk" --dir /tmp
adb install -r /tmp/*.apk
```

**Option B: Lokal mit Docker** (Docker Desktop starten → dann bauen)
```bash
# User muss Docker Desktop starten, dann:
cd "C:/Users/bertm/OneDrive/Dokumente/Claude/trefferpoint/android"
# → ich baue im Container
```

**ADB-Gerät:** `ce031823ccfb0432027e` (Samsung Tab, verbunden)

---

### 2. 9mm-Test wiederholen (v2.3.118 mit minPtsAbsolute)

**Root Cause:** MIN_PTS ist pxPerMm-abhängig. Bei weniger Zoom → weniger Pixel pro Loch → niedrigere MIN_PTS → Rauschen überlebt.

**Fix für v2.3.118:**
```javascript
// In DISCS-Konfiguration (gk25):
minPtsAbsolute: 20   // Zoom-unabhängige Untergrenze

// In compareNow(), MIN_PTS-Berechnung:
const calcPts = Math.max(5, Math.round(
    Math.PI * Math.pow(disc.kaliber * pxPerMm / 2, 2) / (STEP*STEP) * minPtsFactor
));
const minPts = typeof disc.minPtsAbsolute === 'number'
    ? Math.max(disc.minPtsAbsolute, calcPts)
    : calcPts;
```

**Voraussetzungen für Test:**
- Scheibe fest einspannen (kein Pendeln → Drift-Maske <5%)
- Zoom so einstellen, dass pxPerMm ≥ 1.7 (r ≥ 170px bei 100mm Spiegel)
- Drift vor VERGLEICHE JETZT überprüfen (Anzeige im compareStatus)

---

### 3. .22 Live-Test v2.3.116 (noch ausständig)
- LEERE SCHEIBE → 14 .22-Treffer aufhängen → VERGLEICHE JETZT
- Erwartung: 14 Treffer (offline Sweep: kk25/mpf=0.75 → 14-16 Treffer)

---

## Technische Referenz

### pxPerMm — Soll-Werte
| Situation | pxPerMm | r bei 100mm Spiegel | MIN_PTS (9mm) |
|---|---|---|---|
| Offline-Trainingsdaten | 1.84 | 184 px | 24 |
| Live aktuell (zu wenig Zoom) | 1.41 | 141 px | 14 ← Problem |
| Ziel für 9mm-Test | ≥ 1.7 | ≥ 170 px | ≥ 21 |

### APK lokaler Build — Was fehlt
- Kein JDK, kein Android SDK auf dem Rechner
- Docker Desktop ist installiert → nach Start sofort nutzbar
- Alternativ: JDK 17 portable (~180MB) + Android cmdline-tools (~150MB)

### WLAN-CAM URL
`rtsp://192.168.0.1/live/tcp/ch1` (SGK GK720X)

### Kamera-Modi
| Modus | Funktion |
|---|---|
| KAMERA | getUserMedia (USB/Webcam im Browser) |
| STREAM-URL | Android APK → Bridge → UVC nativ (UVCAndroid) |
| WLAN-CAM | RTSP via tpBridge.startRtsp() → ExoPlayer |
