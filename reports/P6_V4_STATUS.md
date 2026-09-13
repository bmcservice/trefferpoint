# Phase 2 — v4-Kern, erster Messstand (2026-06-11)

Beleg: `reports/P6_v4_beleg.png` (je Session: Schlussbild | Sprungkarte | Detektion vs GT)
Code: `sessions/2026-06-11_reviews/v4_core.py` (+ `v4_viz.py`), Kandidaten: `v4_core_cands.json`

## Architektur (aus Audit + 4 Reviews + Phase-1-Befund)
1. Alle Frames global auf Schluss-Median registriert (Phasenkorrelation, P0-verifiziert)
2. Zone aus Basis-Helligkeit: dunkel(<100) → Schuss=heller, hell(≥100) → Schuss=dunkler
   (Nutzer-bestätigt 2026-06-11; deckt äußere Ringe + Vignette automatisch ab)
3. Temporale Sprung-Statistik pro Pixel (halbe Auflösung): amp = mean(letzte 6) − mean(erste 6),
   Schärfe = amp/(σ_vorher+σ_nachher+1), Sprungzeit via CUSUM
4. Kandidaten = Blobs der Sprungkarte; Spiegelrand-Band (r/a 0,85–1,08) braucht 2× Schärfe
   (Scheiben-Shift-Geister, Phase-1-Familie 1)

## Ergebnis (LOSO, TOL=12, n=28 Löcher / 6 Sessions)
| Variante | Recall | Phantome |
|---|---|---|
| vorher (Schlussbild-Residuum, abs) | 15/28 (53%) | 16 (2,7/Serie) |
| **v4-Kern** | **20/28 (71%)** | **4 (0,67/Serie)** |
| v4 ohne Randband-Malus | 20/28 (71%) | 28 (4,67/Serie) |

Pro Session (T=19,5): A 4/5+0 · B 3/5+0 · **0603 5/5+0** · 0606 3/3+1 · 1528 4/5+0 · 0531 1/5+3
**Ohne 0531: 19/23 (83%), 0,2 Phantome/Serie** — unter der Audio-Schwelle (<0,5).

## Ehrliche Vorbehalte
- n=28: 71% hat ein 95%-Intervall von grob 52–84%. Kein Endstand, eine Richtung.
- Randband-Malus ×2 ist handgewählt (eine Stellschraube; Klassifizierer soll das ersetzen).
- 1528/0531 haben nur 14 Frames → temporale Statistik dort unterversorgt; v4 profitiert
  von vollen Sequenzen (Phase 3: App muss alle Frames speichern).
- 0531 (1/5) bleibt der Endgegner: Scheiben-Shift + schwächste Löcher + 14 Frames.
- Schwelle LOSO-gewählt, aber Architektur-Entscheidungen (Fenstergrößen, Zone-Schwelle 100,
  Bandgrenzen) sind auf demselben Korpus entstanden → echte Bestätigung braucht neue Daten.

## Phase-2-Abschluss (2026-06-11, nach Miss-Diagnose + Extraktions-Iterationen + ML)

**Miss-Diagnose (P7_v4_misses.png):** Alle 8 verpassten Löcher haben klares Signal
(amp 45–152). 2× Extraktions-Bug (Doppeltreffer-Verschmelzung A, Flächen-Cap-Kill B),
6× Schärfe knapp unter Schwelle (5 davon in den 14-Frame-Sessions).

**Extraktions-Iterationen (alle LOSO):**
| Variante | Recall | Phantome | Lehre |
|---|---|---|---|
| v1 Komponenten+Gates | **20/28 (71%)** | **4 (0,67/Serie)** | bester Arbeitspunkt |
| v2 pure Lokal-Maxima | 21/28 | 28 | Flächen-CAP war der heimliche Phantom-Killer |
| v3 Hybrid | 21/28 | 28 | Mindestfläche war es nicht |
| v4 Hybrid+Form-Gate | 19/28 | 41 | Segment-Max-Schärfe hebt Phantome — Stopp |

**ML-Klassifizierer (14 Features, Extraktions-Decke 27/28):**
LogReg 82%/2,67 · RF 53%/0,33 · beste ML-Punkte (RF t0,75: 60%/1,0; Ens t0,85: 60%/0,67)
alle UNTER v1. Befund wie von Codex vorhergesagt: n=28 Positive zu wenig — Fold-Schwellen
springen 0,65–0,90. **Physik-Gates schlagen ML bei dieser Datenmenge.**

## Entscheidung
- **v4-Kern v1 wird eingefroren** als Portierungs-Kandidat (71% / 0,67 P/Serie; ohne den
  bekannten Ausreißer 0531: 83% / 0,2 P/Serie).
- Kein weiteres Offline-Tuning auf diesem Korpus — jede weitere Verbesserung braucht DATEN:
  volle Frame-Sequenzen (5 der 8 Misses liegen in den 14-Frame-Sessions!) und mehr Löcher.
- ML-Klassifizierer wird erst mit größerem Korpus (Phase 3) erneut trainiert.

## Offene Punkte für Phase 3 / Portierung
- App muss volle Frame-Sequenzen speichern (nicht 14) — Voraussetzung für temporale Statistik
- 1 Loch fehlt der ML-Extraktion ganz (0603, Decke 27/28) — bei Portierung v1-Extraktion nutzen
- Scheiben-Shift-Kompensation (Rand-Registrierung) als spätere Alternative zum Randband-Malus
- Doppeltreffer (A) bleibt im v1-Pfad ein bekannter Miss (Splitting brachte netto nichts)
