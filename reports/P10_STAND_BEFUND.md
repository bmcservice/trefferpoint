# Phase 3 — erster Out-of-Corpus-Test des v4-Kerns (Stand 2026-06-14)

Zwei frische ETF150-KK25-Serien (je 5 Schuss), GT vom Nutzer geklickt gegen die
Original-Scheiben. Erster echter Test des eingefrorenen v4-Kerns auf Daten, die NICHT
im Tuning-Korpus waren. Bild: `reports/P10_stand_final.png`.

## Datenqualität (Bausteincheck zuerst — Audit-Regel)
- Auflösung 1920×1080, Freeze 0% (byte-identisch). cal sitzt nativ in Detframe-Koord
  (mein ×1,5-Skalierungs-Fehlalarm wurde im Check gefangen).
- **09:00-Serie: sauber** (Belichtung stabil, Drift 2,5).
- **09:06-Serie: kontaminiert** — physische Manipulation (Spiegelwechsel/Hantieren) ab
  Frame 262 (Helligkeit fällt 158→89). Median der letzten Frames zeigte das falsche
  „Hintergrundblatt". KORRIGIERT: nur sauberes Fenster Frame 0–261 verwendet.
  → Die alte App meldete hier 34 „Treffer" = Phantom-Explosion durch die Manipulation.

## Ergebnis am Korpus-Schwellwert (Schärfe 19,5)
| Serie | v4 Recall | Phantome |
|---|---|---|
| 09:00 (sauber) | 3/5 | 1 (Splitting-Doppel) |
| 09:06 (sauberes Fenster) | 2/5 | 0 |
| **gesamt** | **5/10 (50%)** | **0,5/Serie** |

## DER zentrale Befund: Extraktion top, Schwellwert überträgt nicht
Miss-Diagnose jeder verpassten GT-Position: **ALLE Misses haben starkes Signal**
(amp 62–106, det 33–56) — die Löcher sind klar da. Sie scheitern nur an der
**Schärfe-Schwelle** (15,8–19,2, knapp unter 19,5). Die Schwelle ist auf dem Korpus
getunt; der frische Stream rauscht etwas stärker (JPEG/Registrierung) → dieselbe
amp/σ-Schärfe fällt knapp drunter.

**Schwellwert-Sweep (beide Serien, refine an):**
| Schärfe-Schwelle | Recall | Phantome/Serie |
|---|---|---|
| 19,5 (Korpus) | 5/10 | 0,5 |
| **16** | **7/10** | **0,5** |
| 12 | 8/10 | 2,0 |
| 8 | 10/10 | 3,0 |

Bei Schwelle 16 → **7/10 bei GLEICHER Phantomrate** wie 19,5. Die Korpus-Schwelle war
für diesen Stream schlicht zu streng. Die letzten 30% (→90–100%) kosten Phantome.

## Interpretation (deckt sich exakt mit den 3 Reviews)
- v4s **Signal-Extraktion funktioniert out-of-corpus** — kein Loch geht verloren, alle
  erzeugen starke amp. Das Fundament trägt.
- Der **feste Schwellwert generalisiert nicht** (n=28 zu klein, Reviewer-Warnung
  bestätigt). Fix ist NICHT mehr Algorithmus, sondern: **adaptiver Schwellwert**
  (Schärfe pro Session am echten Rauschboden normieren) bzw. der ML-Klassifizierer mit
  mehr Daten.
- Die `refine`-Zentrumssuche half (5/10 statt 4/10) — bleibt an.
- 09:06-Kontamination = harter Beleg, dass der **Live-Stabilitätswächter** (v2.4.18)
  so eine Serie als „⚠ gestört" verwerfen muss statt Phantome anzusagen.

## Adaptiver Schwellwert GETESTET — negativ (2026-06-14)
Hypothese „fester Schwellwert überträgt nicht, Pro-Session-Normierung hilft" im LOSO über
alle 8 Sessions (38 Löcher) geprüft:
- FEST (Schärfe): 18/38 (47%), 2,0 Phantome/Session
- ADAPTIV (Z=(Schärfe−Median)/MAD): 8/38 (21%), 1,75/Session → **SCHLECHTER**

**Warum:** Die Trenn-Struktur zeigt in A/B/0606/S0906 echte Löcher mit NIEDRIGERER Schärfe
als einzelne Phantome (z.B. A: bestes Loch 59, Phantom 69; S0906: Löcher 15–20, Phantome
25–30). Das ist eine **echte Merkmals-Überlappung** — keine Schwelle (fest oder adaptiv)
trennt überlappende Verteilungen. Genau die von allen 3 Reviews vorhergesagte Wand:
ein Einzelmerkmal reicht nicht.

## Konsequenz (ehrlich)
Der nächste Hebel ist NICHT ein cleverer Schwellwert, sondern der **Multi-Merkmal-
Klassifizierer** (Schärfe + Form + Druckkanten-Nähe + Persistenz + Polarität) — und der
braucht **mehr Daten**. Wir sind an der DATEN-Wand, nicht an einer Algorithmus-Wand.

## Nächste Schritte
1. **Mehr saubere Serien sammeln** (heute nur 1,5 saubere) — DAS ist der Engpass.
   Protokoll: während der Serie nicht ins Bild fassen, Spiegelwechsel erst nach Stop,
   Belichtung vor Start stabilisieren.
2. Mit erweitertem Korpus den Patch-Klassifizierer (LogReg/RF) neu trainieren.
3. Cleanup: `refine_center` half am Stand (+1), könnte aber Korpus-Sessions verschieben —
   bei der nächsten Korpus-Auswertung sauber gegen Festzentrum vergleichen.
4. Erst danach JS-Portierung entscheiden.

## Praxis-Lehren fürs Protokoll
- Während der Serie NICHTS am Aufbau anfassen; Spiegelwechsel erst nach „Stop".
- Belichtung vor Serienstart stabilisieren (09:06 driftete 94 Stufen — AE-Lock prüfen).
