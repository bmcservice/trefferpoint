# TrefferPoint — Konsolidierter Audit-Bericht (P0–P4)
Datum: 2026-06-11 · HTML mit Bildern: `reports/AUDIT_BERICHT.html` (in Chrome öffnen)

## Kernergebnis
Mein früheres Verdikt „.22 nicht lösbar, ~50%-Decke, Algorithmus erschöpft" war auf einer
**kaputten Analyse-Pipeline** (Block-Registrierung mit Kachel-Artefakten) gebaut. Nach
Reparatur + Verifikation: **~50–60% Recall auf verifiziertem Fundament** — besser als die
alte (fehlerhafte) Zahl, aber kein sauberer Solve. Schwache .22-Löcher bleiben die Grenze.

## P0 — Bausteine verifiziert
- Registrierung (global, Phasenkorrelation): Sub-Pixel, Fehler 0.32px → SOLIDE.
- Detektor: gepflanztes Loch exakt gefunden → SOLIDE.
- Kalibrierung: A/0603 korrekt; B leicht versetzt (irrelevant); C357 grob daneben.
- Fehler steckte spezifisch in der **Block-Registrierung** (40px-Kacheln einzeln verschoben),
  nicht im Grundverfahren.

## P1 — Basisscheiben-Subtraktion (Schlussbild gegen echte unbeschossene Basis, global registriert)
- Trennbarkeit 32/32 (vs kaputte Block-Variante 17/32).
- **Selbstkorrektur:** „32/32" ist nur Feasibility (Peak>99,5%-Boden), KEIN Detektor-Recall.
  Wieder eine Kennzahl als Durchbruch fehlgelesen.

## P2/P3 — Phantome = Weiß-Druck-Reste (vom Nutzer-Auge diagnostiziert)
- Ringlinien + Ziffern: ~200 Kontrast, 0,3px Rest-Versatz → ~60 Residuum an Kante.
- 0606: 6/6 Phantome auf weißem Druck; 0531: 10/16. Echte Löcher fast nie auf Druck (1/5).
- 2 Kalib-Fehler gefunden (beide Nutzer-Auge): B leicht (egal); C357 = Scheibe bewegte sich
  ~2 Ringe WÄHREND der Session → Ausreißer, ausgeschlossen.
- Meine automatische Zentrumsmessung ist unzuverlässig → Kalibrierung per Nutzer-Klick.

## P4 — Echter Detektor (6 saubere Sessions / 28 Löcher, C357 raus)
| Variante | Recall | Phantome |
|---|---|---|
| globaler Schwellwert T35 | 19/28 (68%) | 23 |
| + Mindestfläche≥8 | 16/28 (57%) | 13 |
| + Weiß-Druck-Unterdrückung | 14/28 (50%) | 5 |
| lokal-adaptiv (Top-Hat) best | ~60% | 12 |
| + Edge-Signatur (Vorzeichen-Konsistenz) | Recall fällt | 12→3 |

Konsistent: **~50–60% Recall ist die Decke**; Phantome runter nur auf Kosten des Recalls.

## Korrekturen an früheren Schlüssen
- „~50%-Decke, Algorithmus erschöpft" → FALSCH (Block-Registrierungs-Bug). Verworfen.
- „8 Hebel negativ für .22" → auf ungeprüften Pipelines; Basis-Subtraktion war kaputt
  implementiert, nicht die Idee.
- „C357 fundamental kontaminiert" → präzisiert: ~2-Ring-Bewegung während der Session.
- „App-Kalibrierung zuverlässig" → teils falsch (B, C357). Nutzer-Klick ist die Quelle.

## QS-Maßnahmen (ab sofort verbindlich)
1. Jede Pipeline-Stufe visualisieren + ansehen, bevor einer Zahl geglaubt wird.
2. Bausteine auf bekannten/synthetischen Eingaben verifizieren.
3. Keine Skalar-Metrik ohne gepaartes Beleg-Bild.
4. Methode vs. Implementierung trennen — „X geht nicht" erst nach Beweis korrekter X-Impl.
5. Analyse-Code reviewen (ggf. zweites Modell).
6. Bilder über Chrome ausliefern; Befund immer zuerst als Text.

## Ehrlicher Endstand
- .22 nicht tot, nicht magisch gelöst. Verifizierte Decke ~50–60% Recall.
- Fundament solide: Basis-Subtraktion + globale Registrierung + bekannte Phantomquelle
  (Weiß-Druck) + Kalibrierung per Nutzer-Klick.
- Fehlende ~40% = schwach-Kontrast-Löcher → Akquisitions-Thema (Belichtung/Optik/Stabilität),
  kein reines Algorithmus-Problem mehr.
- 0531 bleibt schwierigste „saubere" Session (a=310, schwache Loch-Residuen).
- Gewinn des Audits = Verlässlichkeit, nicht eine höhere Zahl.
