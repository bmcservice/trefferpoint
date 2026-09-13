# .22 (KK25) — ehrliches Verdikt mit allen verfügbaren Daten (2026-06-15)

12 KK25-GT-Serien, 56 Löcher, LOSO. Temporaler v4-Kern (Extraktion) + RandomForest (Klassifikation).
Daten/Code: `sessions/2026-06-14_gtbatch/clf_expanded.py`, `clf_expanded_data.json`.

## Kernzahlen
- **Extraktions-Decke: 56/56** — jedes Loch wird als Kandidat erfasst. Reines
  Klassifikations-Problem, KEIN Signalproblem mehr.
- RF schlägt feste Schwelle klar: 51%/1,67 vs 41%/3,33 Phantome/Serie.

## Vollständige RF Recall/Phantom-Kurve (LOSO)
| RF-Schwelle | Recall | Phantome/Serie |
|---|---|---|
| 0,90 | 51% | 1,67 |
| 0,70 | 69% | 2,50 |
| 0,60 | 73% | 3,92 |
| 0,50 | 83% | 6,58 |
| 0,30 | 91% | 15,6 |

→ **Audio-Ziel (<0,5 Phantome) ist mit dieser Datenlage NICHT erreichbar.** Selbst am
strengsten Punkt bleiben 1,67/Serie. Mehr Recall kostet überproportional Phantome.

## Was die Phantome/Misses sind (p≥0,6)
- 47 Phantome: nur **3 am Rand, 3 auf hellem Druck** — die übrigen ~41 sitzen verteilt
  im Spiegel. **Keine einfache, per Feature abgreifbare Klasse** (anders als beim
  spatialen Audit-Befund — der temporale Pfad erzeugt andere Phantome).
- 15 Misses: 12 davon mit STARKEM Signal (amp≥40) — echte Löcher, die der Klassifizierer
  nicht von Phantomen trennt. **Loch und Phantom überlappen im Merkmalsraum.**

## Pro Session (p≥0,70) — der entscheidende Hinweis
- **Sauberste Akquisition gewinnt:** S0900 (heute, stabile Belichtung) = **5/5 @ 1 Phantom**
  — nahezu audio-tauglich. Auch 0603, 0529 = 5/5.
- Phantom-Treiber: 0531 (2/5+5), 1528 (5/5+4), A (4/5+4), S0906 (4/5+4) — teils gute
  Serien, die trotzdem 4 Phantome tragen. Phantome NICHT auf wenige Bad-Sessions isolierbar.

## Verdikt
- **Software-Decke für diese Daten ≈ 70% Recall @ 2,5 Phantome/Serie** (RF, typisch);
  beste saubere Serie 5/5 @ 1 Phantom.
- **Massiver Fortschritt vs alte App** (die machte 0/10 echte Treffer + viele Phantome).
  Für eine **visuelle Bestätigungs-Anzeige** bereits brauchbar.
- **Für hands-free Audio** ("8 auf 3 Uhr") noch nicht: Phantomrate müsste ~5× runter.
- Der Limiter ist jetzt die **Trennbarkeit Loch↔Phantom im Merkmalsraum** — kein
  Signal-, kein simples Feature-Problem.

## Die echten verbleibenden Hebel (priorisiert)
1. **AKQUISITION** (der stärkste, noch nicht ausgereizt): Die sauberste Serie (S0900,
   stabile Belichtung, fester Aufbau) erreichte 5/5 @ 1 Phantom. Bessere/stabilere
   Aufnahme = bessere Trennbarkeit AN DER QUELLE. → Belichtung hart verriegeln, Kamera
   bombenfest, evtl. Optik/Beleuchtung. Marker-A/B-Test (Druck schwärzen) klärt, wie viel
   der Druck noch kostet.
2. **Mehr SAUBERE Daten** (S0900-Qualität, nicht beliebige): hebt den Klassifizierer
   weiter, aber mit abnehmendem Grenzertrag wegen der Überlappung.
3. **Reichere Merkmale** (Sprung-Form über Zeit, Patch-Appearance) — limitiert durch n.

## Ehrliches Fazit zur Benchmark-Frage
.22 ist **nicht tot** und **nicht magisch gelöst**. Für Anzeige nutzbar, für Audio noch
nicht. Der Weg nach vorn führt primär über **bessere Akquisition** (Quelle), nicht über
mehr Algorithmus — die sauberste Serie zeigt, dass es dann geht.
