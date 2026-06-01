# Befund: Auflösung vs Algorithmus — die eigentliche Hürde

**Datum:** 2026-06-01
**Frage:** Ist 1080p-Stream-Auflösung der limitierende Faktor, oder der Algorithmus?

## Methodik

Template-Matching-Pipeline empirisch getestet auf:
1. **12 MP Fotos** (13 Scheiben, 130 GT-Treffer) → klassische CV-Obergrenze
2. **1080p Stream-Standbild** (2026-05-31 Session, Scheibe 1a+1b, 10 GT) → C1
3. **1080p Frame-Diff** (ref vs post) → C2
4. **1080p Frame-Diff + Image-Registration** → C2b

## Ergebnisse

### Algorithmen-Benchmark auf 12 MP Fotos (Obergrenze klassische CV)

| Algorithmus | Recall | Precision | Med-Δ | FP/Bild | LEER-FP |
|---|---|---|---|---|---|
| app_replica (App heute) | 20% | 12% | 10.2mm | 15 | 20 |
| hough | 35% | 8% | 6.8mm | 44 | 50 |
| dog_blob | 34% | 13% | 3.2mm | 25 | 30 |
| **template (NMS gefixt)** | **72%** | **86%** | **0.9mm** | **1.2** | **0** |

→ App-Algorithmus hat NMS-Bug: markiert jede Ring-Beschriftung als Treffer (LEER-Scheibe: 20 FP).
→ Korrektes Template-NMS: 259→1.2 FP/Bild (210× Reduktion) ohne Recall-Verlust.

### C1: Template auf 1080p-Standbild

| | 12MP-Foto | 1080p-Standbild |
|---|---|---|
| Loch-Radius | 15.8px | 6.4px |
| Recall@thr0.55 | 72% | 0-60% |
| Für 100% Recall nötig | — | thr0.40 → **287-439 FP** |

→ Auf 1080p-Standbild sind Treffer VORHANDEN (100% findbar) aber NICHT vom JPEG-Rauschen/Textur trennbar.
→ Standbild-Detection auf 1080p = nicht brauchbar.

### C2: Frame-Diff (ohne Registration)

→ Position-Genauigkeit verbessert (1.5-4mm), aber FP-Flut bleibt.
→ Ursache sichtbar im Diff-Bild: **Ring-Linien als Doppelkonturen** = Scheibe verschiebt sich
   über die Zeit (Misregistration). Jede verschobene Ring-Kante erzeugt Diff-Geisterbild.

### C2b: Frame-Diff MIT Image-Registration (ECC) ⭐

| Phase | Shift | Recall | FP | Med-Δ |
|---|---|---|---|---|
| 1a Test | dx=-1.9 dy=2.2px | **4/5 (80%)** | 1 | 1.4mm |
| 1b Hauptlauf | dx=-0.1 dy=4.0px | **5/5 (100%)** | 4 | 6.9mm |

→ **Gesamt: 9/10 echte Treffer, 5 FP** auf 1080p.
→ Registriertes Diff-Bild zeigt 5 klare Treffer-Spots, Ring-Linien verschwunden.

## Kern-Erkenntnis

**Die Hürde war NIE die Auflösung. Es waren zwei fehlende Algorithmus-Bausteine:**

1. **Image-Registration** vor dem Frame-Diff
   - Scheibe/Spektiv driftet 2-4px über Sekunden/Minuten
   - Ohne Korrektur → Ring-Geisterbilder → hunderte Phantome
   - Mit ECC-Alignment → Diff zeigt nur echte Löcher

2. **Sauberes Template-NMS**
   - App-Code markiert Ring-Beschriftungen als Treffer
   - Template-Matching gegen Loch-Form + korrektes NMS → 0 FP auf LEER

**Mit beidem: 1080p reicht für 90% Recall.** Hardware-Upgrade auf 4K war die falsche Spur.

## Empfehlung nächste Schritte

### Sofort umsetzbar (klassische Pipeline, offline verifizierbar)
- **v2.4.0 Detection-Rewrite:** Registration (ECC/phaseCorrelate) + Frame-Diff + Template + NMS
- Gegen das Foto-Set + die 2026-05-31-Detframes validierbar BEVOR Stand-Besuch
- Realistisches Ziel: 85-90% Recall, <2 FP/Serie

### Optional danach (B: ML)
- 130 gelabelte Treffer als Trainingsbasis
- Nur nötig wenn klassische Pipeline unter ~85% bleibt
- Die schweren Fälle (dichte Cluster, überlappende Löcher) sind der ML-Mehrwert

### Offen / zu klären
- 1a-Miss (4/5): vermutlich Ring-5-Außentreffer im weißen Bereich (viele Alt-Löcher dort)
- Registration-Robustheit bei größerem Drift / Rotation testen
- Live-Inkrement: pro Schuss kurzer Diff (1-2s) statt Phasen-Diff (weniger Drift)
