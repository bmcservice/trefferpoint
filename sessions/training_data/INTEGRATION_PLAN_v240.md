# Integrations-Plan: v2.4.0-Detection in index.html

**Stand:** Offline-Pipeline (Python + JS) vollständig validiert — 90% Recall, 90% Precision
auf 1080p-Stream. JS-Portierung matcht Python exakt. Performance ~370ms/Detektion.

## Warum nicht einfach runDetection() ersetzen

Der Live-Loop in index.html (~7000 Zeilen) ist fragil:
- läuft ~30fps pro-Frame; v2.4.0-Detektion braucht 370ms → kann NICHT pro-Frame laufen
- viele Sonderfälle: SurfaceView vs USB-Cam vs MJPEG, Warmup, Cooldown, snapshotGen,
  Diag-Modus, Frozen-Spots, Confirm-Banner
- Memory-Lektion `lesson_patch_spiral`: 2× in fragilen Live-Code geschnitten → Spirale

## Architektur: Zwei-Stufen-Trigger (additiv, opt-in)

```
Pro Frame (billig, ~1ms):     Grob-Trigger
  refFrame vs cur → Diff-Fläche im Spiegel > Schwelle?
  → JA: "Schuss könnte passiert sein", Frame als post-Kandidat merken

Bei Trigger + Stabilisierung (teuer, ~370ms, EINMAL):  v2.4.0-Präzision
  registerTranslation(refFrame, postFrame, ±10px)
  → centerSurround auf Diff → localMaxima → NMS → SNR-Verify
  → NEUE Treffer (nicht in hits[]) committen
  refFrame := postFrame (Basis für nächsten Schuss)
```

**Vorteil:** Grob-Trigger ist die bestehende billige Logik. Die teure v2.4.0 läuft nur
~1× pro Schuss (alle paar Sekunden) → 370ms unmerklich. Inkrementeller refFrame-Update
nach jedem Schuss → kein Cluster-Überlappungsproblem (jeder Schuss einzeln im Diff).

## Sicherheits-Prinzip: additiv, nicht ersetzend

1. v2.4.0-Funktionen als isolierter `<script>`-Block (TPDetect-Namespace) einbauen —
   bricht NICHTS Bestehendes.
2. Neuer Detection-Pfad hinter Checkbox „⚙ v2.4-Präzisions-Detection (experimentell)".
   Default AUS → alte Pipeline bleibt unangetastet.
3. Am Stand per Toggle umschaltbar, A/B-Vergleich möglich.

## Offline-Testbarkeit (vor Stand)

- Browser-Harness: lädt PNG-Frames (1a/1b), ruft den in index.html eingebetteten
  TPDetect-Pfad, vergleicht gegen GT. Beweist: eingebettete Logik == Node-Referenz.
- Was NICHT offline testbar ist (braucht echten Stream):
  - Einzelframe-Rauschen (Offline nutzt Median über 15 Frames; Live nimmt Einzelframes)
  - Live-Performance auf Tablet-Adreno-CPU (370ms im Node ≠ Tablet)
  - Grob-Trigger-Timing (wann ist der Schuss "fertig" für post-Frame)

## Offene Robustheits-Fragen

1. **Einzelframe vs Median:** Offline-90% nutzte Median-über-15-Frames (rauscharm).
   Live hat einzelne, verrauschtere Frames. Mitigation: 3-5 Frames um den Trigger
   mitteln vor der Detektion (kostet ~150ms mehr, akzeptabel).
2. **Spiegel-Kalibrierung:** v2.4.0 braucht (cx,cy,r,pxPerMm). Im Browser ist `calib`
   bereits gesetzt (manuelle/Auto-Kalib). ABER: der heutige Cal-Versatz-Bug
   (Touch-Mapping, Befund 2026-05-31) muss zuerst geklärt sein, sonst sitzt die
   Zonen-Maske falsch. → Cal-Mapping-Fix ist Voraussetzung.
3. **Registration-Drift-Limit:** ±10px getestet. Bei größerem Spektiv-Stoß (>10px)
   greift Registration nicht. Mitigation: maxShift adaptiv, oder Re-Kalibrierungs-Warnung.

## Reihenfolge für die nächste(n) Session(en)

1. **Cal-Mapping-Fix** (Touch→Stream-Koordinaten) — Voraussetzung, eigener Block.
   Braucht Stream/Tablet zum Verifizieren.
2. **v2.4.0 additiv einbauen** + Browser-Harness — offline machbar.
3. **Stream-Test am Tablet** — Einzelframe-Robustheit, Performance, Trigger-Timing.
4. **Stand-Test** mit echten Schüssen, A/B alt vs v2.4.

## Fazit

Offline-Forschung ist abgeschlossen und maximal validiert. Die Live-Integration ist
bewusst als nächster, separat abzunehmender Block geplant — nicht als Blind-Patch in
den fragilen Live-Loop. Voraussetzung ist der Cal-Mapping-Fix.
