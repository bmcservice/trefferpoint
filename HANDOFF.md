# HANDOFF — Stand 2026-06-01

## Großer Durchbruch heute: Detection-Algorithmus war die Hürde, NICHT die Auflösung

Nach 4 Wochen Kamera-/Auflösungs-Fokus empirisch widerlegt. 1080p reicht für 90% Recall.
Das geplante 4K-Hardware-Upgrade ist unnötig.

### Was offline fertig + dreifach validiert ist (v2.4.0)

Pipeline: **Spiegel → Image-Registration → Frame-Diff → Center-Surround → NMS → SNR-Verify**

| Variante | Recall | Precision | Δ | Status |
|---|---|---|---|---|
| Python-Referenz (`detection_v240.py`) | 90% | 90% | 2.7mm | ✓ |
| Node-JS (`js_test/detection_v240.js`) | 90% | 90% | 2.0mm | ✓ |
| Browser-Pfad (`imageDataToGray`) | 90% | 90% | 2.0mm | ✓ |

Foto-Modus (12MP Standbild, Endauswertung): 72% / 98% / 0.9mm.
App-Algorithmus heute zum Vergleich: 20% / 12% / 10.2mm.

Die zwei fehlenden Bausteine der alten App:
1. **Image-Registration** vor Frame-Diff (Scheibe driftet 2-4px → sonst Ring-Geisterbilder → Phantome)
2. **Korrektes NMS** gegen Loch-Form (alte App markierte Ring-Ziffern als Treffer, 20 FP auf LEER-Scheibe)

### Wo alles liegt (sessions/training_data/)

- `detection_v240.py` / `validate_v240.py` — Python-Referenz + Validierungs-Harness
- `js_test/detection_v240.js` — JS-Modul (Browser+Node), `test_v240.mjs`, `test_browser_path.mjs`
- `js_test/harness_v240.html` — visueller Browser-Test (über HTTP-Server öffnen)
- `gt_multi.html` + `ground_truth_multi.json` — 13 Fotos, 130 Labels, GT-Tool
- `ERGEBNIS_v240.md`, `INTEGRATION_PLAN_v240.md`, `BEFUND_AUFLOESUNG.md` — Doku
- Rohdaten (HEIC-Fotos, Detframes, PNG) via .gitignore lokal, nicht committed

Branch: `claude/nervous-tu-2286b6` (worktree). 5 Commits heute (fb01f81..4b4e59c).
**Muss noch nach main übertragen werden** (Worktree-Regel).

## NÄCHSTE SCHRITTE — brauchen Tablet/Stream (Reihenfolge!)

### 1. Cal-Mapping-Fix (VORAUSSETZUNG für alles weitere)
Befund 2026-05-31: App-Cal sitzt 120px/86mm neben echtem Spiegel im Stream, obwohl
auf dem Display visuell korrekt. Ursache: Canvas-CSS (850×478) ≠ SurfaceView (2560×1438),
Touch→Stream-Mapping verzerrt. `_meta.json`-Diagnose-Stempel (v2.3.184) liegt vor.
→ getCanvasPointer() Touch→Stream-Transform reparieren. Braucht Tablet zum Verifizieren.

### 2. v2.4.0 additiv in index.html einbauen (offline machbar)
Zwei-Stufen-Trigger (siehe INTEGRATION_PLAN_v240.md):
- billiger Grob-Trigger pro Frame (bestehende Diff-Flächen-Logik)
- teure v2.4.0-Präzision (~370ms) nur bei Schuss-Event, ~1×/Schuss
- additiv hinter Checkbox „v2.4-Präzisions-Detection", alter Pfad bleibt Default
- Browser-Harness gegen PNG-Frames testen vor Stand

### 3. Stream-Test am Tablet
- Einzelframe-Rauschen (offline nutzte Median über 15 Frames → live ggf. 3-5 mitteln)
- Live-Performance auf Adreno-CPU (370ms im Node ≠ Tablet)
- Grob-Trigger-Timing

### 4. Stand-Test mit echten Schüssen, A/B alt vs v2.4.0

## Methoden-Regel (ab jetzt Pflicht)
Jede Test-Session braucht unabhängige Ground-Truth = **Scheiben-FOTO** + Screenshot +
Detframes + Session-JSON + GT-Tool. 4 Wochen lang nur Pipeline-Output gesammelt →
Symptom-Patching statt Quote-Messung. Das war der eigentliche Fehler.

## Tablet/Hardware
- Tablet ce031823ccfb0432027e (Samsung Tab S6 Lite), ADB-Tier "click"
- ETF150 (RTSP 1080p, 192.168.10.1) = Echtzeit-Track
- App-Stand: v2.3.184 (versionCode 188) installiert
