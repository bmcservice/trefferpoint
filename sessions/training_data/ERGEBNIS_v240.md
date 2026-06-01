# v2.4.0 Detection-Pipeline — Validierungs-Ergebnis

**Datum:** 2026-06-01
**Status:** Offline validiert, bereit zur App-Integration

## Ausgangslage

App-Algorithmus (Stand v2.3.184) auf 12 MP-Fotos: **20% Recall, 12% Precision, 15 FP/Bild**.
Vermutete Hürde war Kamera-Auflösung. Empirisch widerlegt.

## v2.4.0 Pipeline

```
Stream (Live):  Spiegel-Detektion → Image-Registration → Frame-Diff
                → Template-Matching → NMS → SNR-Verifikation
Foto (Endausw): Spiegel-Detektion → Template-Matching → NMS → Center-Verifikation
```

Kernbausteine die in der alten App fehlten:
1. **Image-Registration** (ECC): korrigiert 2-4px Scheiben-Drift vor dem Diff.
   Ohne → Ring-Geisterbilder → hunderte Phantome.
2. **Sauberes NMS**: alte App-NMS war buggy → jede Ring-Ziffer = Treffer.
3. **SNR-/Center-Verifikation**: Loch-Kontrast-Plausibilität gegen Rest-Artefakte.

## Resultate (harte Zahlen)

| Metrik | App v2.3.184 | v2.4.0 Foto (12MP) | v2.4.0 Stream (1080p) |
|---|---|---|---|
| Recall | 20% | 72% | **90%** |
| Precision | 12% | **98%** | **90%** |
| Median-Δ Position | 10.2 mm | **0.9 mm** | 2.7 mm |
| FP / Bild bzw. Serie | 15 | 0.2 | **0.5** |
| LEER-Scheibe FP | 20 | **0** | — |

Testbasis: 13 Fotos (130 GT-Treffer) + 2026-05-31 Stream (10 GT-Treffer).

## Kern-Erkenntnis

**1080p reicht.** Der Stream-Modus (1080p) erreicht 90% Recall — HÖHER als der
Foto-Modus (72%), weil Frame-Diff den statischen Hintergrund eliminiert.
Hardware-Upgrade auf 4K war die falsche Spur. Das Problem war der Algorithmus,
konkret die fehlende Image-Registration.

## Restfehler

- **Foto-Modus 72%**: limitiert durch dichte Cluster (Bild 5/9 mit 25-29 Treffern),
  wo überlappende Löcher vom NMS unterdrückt werden. Im Live-Stream weniger relevant,
  da Schüsse zeitlich getrennt ankommen (inkrementeller Diff → keine Überlappung).
- **Stream 1a-Miss (4/5)**: der Ring-5-Außentreffer im weißen Bereich (viele Alt-Löcher
  anderer Schützen erschweren Frame-Diff außerhalb des Spiegels).

## Nächste Schritte

1. **JS-Portierung für App** (v2.4.0):
   - Registration als Translation-brute-force (±10px) — JS-portierbar, kein OpenCV nötig
   - Template-Matching: Cross-Correlation in JS (oder simpler Blob auf Diff)
   - NMS + SNR-Verify: trivial in JS
2. **Inkrementeller Live-Test**: Diff pro Schuss (1-2s Fenster) statt Phasen-Diff
   → sollte Cluster-Problem vermeiden, Recall potenziell > 90%
3. **Optional ML (B)**: nur für die dichten-Cluster-Fälle, falls nötig. 130 Labels da.

## Dateien

- `detection_v240.py` — Referenz-Pipeline (Python/OpenCV)
- `validate_v240.py` — Validierungs-Harness über beide Datensätze
- `visualize_v240.py` — annotierte Demo-Bilder
- `v240_foto_demo.jpg`, `v240_stream_demo.jpg` — visuelle Belege
- `ground_truth_multi.json` — 13 Fotos, 130 Labels
