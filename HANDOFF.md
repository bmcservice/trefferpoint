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

### 1. Cal-Mapping-Fix — ✓ ERLEDIGT (2026-06-01, v2.4.0/v2.4.1)
Wurzel: SurfaceView war match_parent (full-screen 2560px), streckte Stream 1920→2560,
Canvas-Overlay nur 1912px → Touch/Stream-Versatz Faktor 1.339 → calib 86mm daneben.
FIX: Bridge setSurfaceBounds() legt SurfaceView deckungsgleich auf die camera-wrap-
Region (= Canvas). JS syncSurfaceBounds() im rAF-Loop (1×/s) + Stream-Start + resize.
LIVE VERIFIZIERT (Tablet, v2.4.1): calib auf Stream-Pos des Lämpchens (449,575) →
Ring sitzt visuell exakt drauf. Auto-Sync setzt SurfaceView automatisch auf 1912×1076.
FINAL VERIFIZIERT (2026-06-01, echte KK25-Scheibe vor ETF150): App-Auto-Kalibrierung
trifft echten Spiegel mit nur 16px ≈ 4mm Versatz (vorher 127px/40mm). CV-Fit (orange)
und App-Cal (grün) liegen visuell deckungsgleich auf dem Spiegel-Rand. Beweis-Bild:
sessions/2026-05-31_v184_test/cal_fix_PROOF.jpg. CAL-MAPPING-FIX KOMPLETT ABGESCHLOSSEN.

Setup-Hinweis: USB-ADB nötig (PC im Heimnetz erreicht Tab im ETF-WLAN nicht per WLAN-ADB).
ETF150 geht bei Inaktivität in Standby → Tab wechselt dann selbst ins Heimnetz.

### 2. v2.4.0 in index.html — TEILWEISE ERLEDIGT (2026-06-01)
✓ TPDetect-Block additiv eingebettet (Commit main 8495c26), window.TPDetect,
  isolierter <script>-Block, keine Seiteneffekte. APP_VERSION unverändert.
✓ Verifiziert: extrahierter Block aus index.html = 90%/90% (test_embedded.mjs).
✓ Mirror nach android-assets + G-Drive. NICHT gepusht (toter Code, kein CI-Build).
OFFEN — die eigentliche Live-Verdrahtung (Zwei-Stufen-Trigger, INTEGRATION_PLAN_v240.md):
- billiger Grob-Trigger pro Frame (bestehende Diff-Flächen-Logik) ruft bei Schuss-Event
  window.TPDetect.streamDetect() (~370ms, ~1×/Schuss)
- refFrame inkrementell nach jedem Schuss aktualisieren
- additiv hinter Checkbox „v2.4-Präzisions-Detection", alter Pfad bleibt Default
- Spiegel-Geometrie aus calib (cx,cy → r aus a/b, pxPerMm) an TPDetect übergeben
- Browser-Harness (harness_v240.html) als Vor-Stand-Check

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
