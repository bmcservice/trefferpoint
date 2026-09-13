# Phase 1 — 0531-Phantom-Diagnose (2026-06-11)

Galerie: `reports/P5_0531_galerie.png` (17 Kandidaten × Basis/Schluss/Residuum/Zeitverlauf)
Script: `sessions/2026-06-11_reviews/p5_0531_phantome.py`

## Datenlage
14 Frames; Zeitverläufe zeigen: Frames 0–6 = vor dem Schießen, ab Frame 7 = nach
Schussbeginn (alle Signale springen exakt bei Frame 7, inkl. der echten Löcher).

## Befund: Die 12 Phantome sind KEINE Zufallsklasse — drei Familien

**Familie 1 — Spiegel-Rand-Geister (7 von 12):** #3,#4,#5,#6,#7,#8,#10 liegen alle auf
x≈781–791, also auf dem KREISBOGEN des linken Spiegelrands (r/a 0,88–0,99), und springen
alle exakt bei Frame 7 (+7…+35). Interpretation: Die Scheibe hat sich beim Schussbeginn
leicht verschoben/verformt (Einschlag bewegt das Papier) → die globale Registrierung kann
Scheibe UND Hintergrund nicht gleichzeitig ausrichten → Hell-Geister entlang der
stärksten Kante (Spiegelrand). **0531 ist ein Mini-C357** (dieselbe Ursachen-Familie wie
die ausgeschlossene Session, nur kleiner).

**Familie 2 — Zentrum auf heller Basis (#1, #2):** sitzen fast exakt im Spiegelzentrum,
aber auf lokal HELLER Basis (148/166 — im schwarzen Spiegel!). Verdacht: Altlöcher oder
helle Struktur bereits in der Basisscheibe → Dipol-Reste. #2 hat einen sauberen
Frame-7-Sprung (+43) — könnte auch ein echter, nicht geklickter Treffer sein
(Gegenprobe mit der physischen Scheibe nötig, falls vorhanden).

**Familie 3 — Klein/diffus (#0, #9, #11):** #0 ist ein großes Gebilde mit NEGATIVEM
Zeitverlauf (Verdunkelung, vermutlich lokale Papier-Verformung), #9/#11 schwache
Sprünge auf dunkler/heller Druckstruktur.

## Drei Nebenbefunde mit Konsequenz

1. **Scoring-Lücke entdeckt:** GT-Loch (613,477) liegt bei r/a=1,40 — AUSSERHALB der
   Spiegel-Detektionsmaske. Es konnte nie gefunden werden (zählt aber als Miss).
   Sein Zeitverlauf zeigt einen klaren Sprung (+31). → Detektionszone muss über den
   Spiegel hinausgehen (Zweizonen!), Recall war leicht unterschätzt.
2. **Polaritäts-Ausnahme:** GT-Loch (846,419) wird beim Schuss DUNKLER (−23) — im
   schwarzen Spiegel! (Hintergrund/Beleuchtungs-abhängig.) → Polarität ist ein starkes
   FEATURE, aber kein harter Filter; pro Session/Zone schätzen oder dem Klassifizierer geben.
3. **Temporale Sprünge sind extrem sauber:** Selbst die zwei „zu schwachen" verpassten
   Löcher (+13/+14, unter T=20) haben Vorher-Rauschen <1 → Sprung-SNR >10. Eine
   Sprung-Detektion (vorher-Mittel vs nachher-Mittel) findet sie trivial — das spatiale
   T=20 ist die falsche Messlatte für schwache Löcher.

## Phase-1-Verdikt für die v4-Architektur (Phase 2)

- **Kern = temporale Geburt** (Sprung-Detektion pro Ort, vorher/nachher-Statistik) —
  nicht spatiale Schwelle auf dem End-Residuum. Hebt schwache Löcher UND präzisiert die Zeit.
- **Spiegel-Rand-Band (r/a > ~0,85) als eigene Klasse** behandeln (Scheiben-Verschiebungs-
  Geister) — oder besser: Scheiben-Shift separat schätzen (Registrierung NUR auf
  Spiegelrand) und kompensieren.
- **Zweizonen-Maske** (Detektion auch außerhalb des Spiegels, dort umgekehrte Polarität).
- **Polarität als Feature** im Patch-Klassifizierer, nicht als harter Filter.
- 0531 bleibt im Korpus (kein Ausschluss nötig) — seine Phantome sind jetzt erklärt
  und modellierbar.
