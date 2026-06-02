# Befund USB-Cam-Diagnosetest 2026-06-02 (Trockenübung zuhause, 0 Schüsse)

## Kontext
USB-C-Okularkamera (Mustcam 20x) statt ETF150. Trockenübung zuhause, **NULL echte
Schüsse** — Scheibe vor der Cam, keine Veränderung. Damit effektiv ein
**Negativ-Kontroll-Test** (Ground Truth = 0 Treffer).

## Zwei Bugs aufgedeckt + gefixt
1. **v2.4.3** — `captureCurrentFrame()` lieferte im USB-Modus "" (rief nur
   captureRtspSurfaceJpeg). Fix: Fallback auf lastUvcFrameJpeg → Stand-Snapshots
   funktionieren auch mit USB-Cam.
2. **v2.4.4** — `sessionRecMaybeStart()` returnte hart bei gwIp != 192.168.10.1
   (ETF150-only) → recSession blieb null → kein Session-JSON trotz vollständigem
   Wizard. Fix: cam-agnostische recSession (Hit-Logging + JSON, ohne ETF-Video).

## Befunde (verifiziert)
- USB-Cam liefert **Full HD 1920×1080** (Detframes); die App-Live-Detection läuft
  aber auf 1280×720 (Display-Server skaliert) — Auflösungsverlust, v2.4.0 löst es
  (arbeitet auf 1920-Detframes).
- **Cal-Mapping im USB-Modus nativ korrekt** (Bild direkt im Canvas, kein
  SurfaceView-Versatz). Snapshot: calib-Ellipse perfekt auf Spiegel.
- Bild anfangs pink (AE/WB-Pendeln), nach ~2s normal (freezeCameraAutoModes).

## NEGATIV-KONTROLLE: v2.4.0 vs alte App (statische Szene, GT=0)
| Detektor | Phantome |
|---|---|
| Alte App-Detection | **4** |
| v2.4.0-Pipeline | **0-1** (1 nur mit Start-Frame als ref = AE-Übergang; sonst 0) |

Bestätigt LEER-Scheiben-Befund (App 20 FP, v2.4.0 0 FP). SNR-Verify + Registration
filtern Beleuchtungs-Artefakte. App-Phantome kamen über 22s verteilt = AE/WB-Drift
+ Referenzframe-Instabilität.

## Lehre für Tests
Nach Referenzframe **2-3s warten** (AE/WB einpendeln), bevor scharfgeschaltet wird —
sonst erzeugt der Belichtungs-Übergang Phantome (gilt für alte App; v2.4.0 fängt es ab).

## Dateien
- `phantom_test.py` — v2.4.0-Negativ-Kontrolle
- `run2/snap1-4.jpg` — die 4 Phantom-Snapshots (App-Sicht)
- Detframes (191×1920) via .gitignore lokal
