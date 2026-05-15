# Phase-2-Analyse — Praxistest 2026-05-14 (v2.3.170)

Datenbasis: 3 JSON-Sessions (S1/S2/S3) + 6 Praxis-Videos (138 MB, ETF150-SD).
Methodik: JSON-Statistik (100% verlässlich) + rechnerische Video-Pixel-Analyse
(keine Bild-Tools — Memory-Regel `feedback_no_image_tools.md`).

---

## Die 3 belastbaren Kernbefunde

### 1. Kalibrierung war ungeschützt und systematisch 50–90 mm daneben

| Session | cal-Zentrum | Schwerpunkt aller Detektionen | Versatz | cal.locked |
|---|---|---|---|---|
| S1 | (1071,437) | (946,642) | 75 mm | **n/a** |
| S2 | (1279,457) | (1068,625) | 88 mm | **n/a** |
| S3 | (1279,457) | (1123,469) | 51 mm | **n/a** |

`cal.locked = n/a` heißt: in **keiner** Session war eine geschützte manuelle
Kalibrierung aktiv. Die App lief auf alten/automatischen localStorage-Werten —
**genau der Bug den du beschrieben hast** ("manuell kalibriert, trotzdem daneben").
Der Versatz ist über alle Sessions konsistent (cal-Zentrum oben-rechts vom echten
Trefferschwerpunkt → echte Schüsse werden zu weit vom Zentrum gerechnet → zu
niedrige Ringe gemeldet). Das erklärt die "Ring 4–5 obwohl alles im Schwarzen".

→ **v2.3.171 behebt das direkt**: Cal-Lock (manuell = `locked:true`), Cal-Cross
(🔒-Kreuz sichtbar), Tap-to-correct (Schnellkorrektur). Das ist der größte Hebel
und ist bereits ausgeliefert.

### 2. Frozen-Spot-Muster ist real — aber Ursache liegt NICHT im SD-Video

S1 + S2 hatten je einen Cluster aus 7–10 `user_discarded` bei Ring 9–10, nahe
Bildmitte, über 40–90 s an exakt derselben Pixelstelle.

Rechnerische Video-Prüfung am S1-Cluster (1010,503):
- Zeitliche Std über 73 s: **2.2** (extrem stabil)
- Helligkeit Ø=151, leere Referenz am selben Punkt Ø=157 → **Δ=2, praktisch identisch**
- Lokaler Kontrast: std=2.0, range=8 → **flache, ruhige Fläche, keine Kante**
- Frame-zu-Frame-Differenz: 0.1–0.8 = **so stabil wie eine tote Bildecke**

**Im SD-Video passiert an dieser Stelle NICHTS.** Trotzdem hat die Detection dort
10+ mal gefeuert. Schlussfolgerung:

> Die Detection läuft auf dem **RTSP→MediaCodec→SurfaceView→PixelCopy**-Pfad.
> Das SD-Video ist ein **unabhängiger ETF150-interner Parallel-Encode** (300 kbit/s).
> Die False-Positive-Quelle (H.264-Decoder-Artefakte, PixelCopy-Frame-Tears im
> Live-Pfad) ist im SD-Video gar nicht enthalten.

Das ist eine wichtige Architektur-Erkenntnis: **das SD-Video ist KEIN getreues
Abbild dessen, was der Algorithmus tatsächlich verarbeitet hat.** Für echte
Ground Truth müssen wir die *tatsächlichen Detection-Frames* (PixelCopy-Ausgabe)
mitschneiden, nicht die ETF150-SD-Aufnahme.

### 3. Rapid-Fire bestätigt

S1: 24× dt<1s, 8× dt<0,5s. S2: 20× dt<1s. → Banner-Race-Fix in v2.3.171
(Cooldown solange Banner offen + `banner_replaced`-Logging) adressiert das direkt.

---

## Was das für die Strategie heißt

**Gut:** Die zwei größten Praxis-Blocker (Kalib-Drift, Rapid-Fire) sind in
v2.3.171 bereits behoben. Beim nächsten Stand-Test solltest du den Unterschied
unmittelbar sehen (🔒-Kreuz sitzt sichtbar, Tap-Korrektur in Sekunden).

**Neu erkannt:** Frozen-Spot-FPs kommen aus dem Live-Decode-Pfad, nicht aus der
Szene. Die Ursache (Decoder-Artefakt vs. refFrame-Kontamination) ist sekundär —
ein **session-gelernter Frozen-Spot-Filter** wirkt unabhängig von der Ursache:
Wenn sich an einer Pixelstelle wiederholt Rejections häufen, wird der Spot für
die restliche Session automatisch maskiert.

**Limitierung offen:** Video↔Session-Zeitabgleich konnte nicht bestätigt werden
(5/6 Hits zeigten am angenommenen Offset keine messbare Änderung — Mix aus
möglichem Zeitversatz UND .22-Löcher unter der SD-Encode-Rauschschwelle). Für
verlässliche Ground Truth braucht es Detection-Frame-Capture (siehe v2.3.172).

---

## v2.3.172 — konkrete Spec (datengetrieben, keine Hypothesen)

| # | Feature | Begründung aus den Daten |
|---|---|---|
| A | **Frozen-Spot-Maske** — pro Session: ≥4 Rejections in 30px-Radius über >20s → Spot wird maskiert (keine Detection mehr dort) | S1+S2 hatten je 7–10 Pseudo-Hits am selben Punkt; rein zeitlich/räumlich erkennbar ohne Ursachenkenntnis |
| B | **Detection-Frame-Capture (Debug)** — optionaler Modus: jeder Detection-Input-Frame (PixelCopy) wird mit Timestamp als JPG gespeichert | SD-Video ist nicht der Detection-Input → echte Ground Truth nur so möglich |
| C | **REC-Start-Wallclock in JSON** — `session.rec_start_iso` schreiben | macht künftigen Video↔Session-Abgleich eindeutig (heute nicht rekonstruierbar) |
| D | **Cal-Cross Soll/Ist-Vergleich** — bei aktiver Detection den Detektions-Schwerpunkt als 2. Marker zeichnen; wenn er stark vom cal-Zentrum abweicht → Warnung "Kalibrierung prüfen" | Versatz cal↔Cluster war in allen 3 Sessions 50–90 mm; automatisch erkennbar |

A + D sind reine Praxis-Verbesserungen für die nächste Runde. B + C schaffen die
Grundlage für späteres echtes Algorithmus-Tuning gegen verlässliche Ground Truth.

---

## Empfehlung Reihenfolge

1. **Nächster Stand-Test mit v2.3.171** — Kalib-Lock/Cross/Tap + Banner-Race
   sind die schwersten Blocker und schon drin. Erst messen ob das reicht.
2. **Parallel v2.3.172 A+D bauen** (Frozen-Spot-Maske + Cal-Soll/Ist) — reine
   Gewinne, kein Risiko für den bestehenden Pfad.
3. **v2.3.172 B+C** wenn echtes Algorithmus-Tuning ansteht (Detection-Frame-
   Capture + Wallclock-Logging) — dann haben wir endlich verlässliche Ground Truth.
