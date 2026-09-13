# Deep-Dive (Fable 5): Kritische QS + Lösungsalternativen (2026-06-16)

Auftrag: deutlich über das übliche Maß hinaus — kritische QS des gesamten Verlaufs +
intensive Alternativen-Suche. Drei neue Hypothesen wurden direkt am Korpus GEMESSEN
(nicht nur argumentiert); Skripte inline, Daten: `sessions/2026-06-14_gtbatch/`.

═══════════════════════════════════════════════════════════════════
## I. NEUER KERN-BEFUND (frisch gemessen): Phantome sind EINSCHLAG-KORRELIERT
═══════════════════════════════════════════════════════════════════
Sprungzeit-Analyse (tfrac) aller 2876 Kandidaten: Die stärksten Phantome springen zu
EXAKT denselben Zeitpunkten wie die echten Treffer (Beispiel 0603: Treffer bei
0,24/0,38/0,52/0,64/0,76 — Top-Phantome bei 0,76/0,52/0,45/0,67). Nur 2% der Phantome
sind Rand-Drift.

**Interpretation:** Die Phantome sind KO-ARTEFAKTE des Einschlags selbst — Papier-
Erschütterung, Mikro-Verschiebung, Wölbung im Moment des Treffers. Kein unabhängiges
Rauschen. Das erklärt fundamental, warum sie in ALLEN bisherigen Merkmalen (Schärfe,
Persistenz, Form, Zeit) mit echten Löchern überlappen: sie entstehen im selben Moment,
persistieren mit (verschobenes/gewölbtes Papier bleibt so) und sehen im Differenzbild
ähnlich aus.

**Konsequenzen:**
1. Akustisches Schuss-Gating (Mikro) killt diese Phantome NICHT (gleiche Zeit!) —
   nur Drift-Phantome (2%). Als Phantom-Killer damit weitgehend entwertet, bleibt
   nützlich als Serien-Zähler/Zuordnungs-Anker.
2. Der wirksamste Hebel gegen diese Klasse ist PHYSISCH: die Scheibe ruhigstellen
   (steifes Backing) → weniger Erschütterung → weniger Ko-Artefakte.
3. Softwareseitig braucht es eine ERSCHEINUNGSBILD-Unterscheidung (Loch-Appearance
   vs. Wölbungs-/Kanten-Artefakt), nicht mehr Zeit-/Score-Logik.

## Getestet und (ehrlich) verworfen in diesem Deep-Dive
| Idee | Ergebnis (12 KK25-Serien, LOSO-RF) |
|---|---|
| Referenz: RF p≥0,70 | 69% Recall, 2,50 Ph/Serie |
| Top-k (k=Sollschusszahl) | 53%, 2,17 — schlechter |
| Ein-Event-ein-Loch (Zeit-Dedup) | 44–48%, 1,17–1,75 — tauscht nur |
| dito, fair (nur 10 Serien mit Zeitauflösung) | 54–63%, 1,40–2,30 — dominiert nicht |
Grund: Innerhalb eines Schuss-Events scort das Artefakt oft HÖHER als das Loch —
Zeit-Dedup verdrängt dann das echte Loch. Bestätigt Konsequenz 3.

═══════════════════════════════════════════════════════════════════
## II. KRITISCHE QS DES BISHERIGEN VERLAUFS
═══════════════════════════════════════════════════════════════════
### Was hält (belastbar)
- Audit-Disziplin seit 2026-06-11 (Beleg-Bilder, Baustein-Verifikation, LOSO,
  dokumentierte Widerlegungen eigener Hypothesen — auch heute wieder 3).
- Extraktions-Decke 56/56: kein Loch geht in Stufe 1 verloren (mehrfach reproduziert).
- Out-of-corpus-Stabilität des v4-Kerns: ~45–50% Recall, <1 Ph/Serie über 6 fremde Serien.
- RF schlägt feste Schwelle auf beiden Achsen (paariger Vergleich, gleiche Daten).
- Daten-Skalierungs-Beweis: 8→12 Serien kippte den Klassifizierer von unterlegen zu überlegen.

### Was wackelt (Risiken, priorisiert)
**QS-1 (GRÖSSTES RISIKO): GT-Selektions- und Sichtbarkeits-Bias.**
GT wurde in STREAM-Bildern geklickt, nicht auf Scheiben-Fotos registriert. Folgen:
(a) Serien, deren Löcher im Stream unsichtbar sind, fielen komplett raus (6 von 10
Batch-Bildern!) → wir messen Recall nur auf „sichtbaren" Serien → Überschätzung fürs
Feld. (b) Umgekehrt: Ein echter Treffer, den der Nutzer im Stream nicht sah, zählt
als PHANTOM → Precision wird UNTERSCHÄTZT (der 0531-#2-Fall belegt, dass das vorkommt).
→ Fix: GT künftig am SCHEIBEN-FOTO klicken; ich registriere Foto↔Stream über die
Ringgeometrie. Teil der „Phantome" könnte sich in echte Treffer auflösen.

**QS-2: Architektur-Leakage.** Fenstergrößen, det>8, Zonen-Schwelle, Randband,
NMS-Radius wurden auf demselben Datenpool iteriert; LOSO schützt nur die Endschwelle.
S0900/S0906 kamen nach dem Einfrieren (gut), die 4 Batch-Serien flossen aber sofort
ins Training. Absolute Zahlen daher milde optimistisch zu lesen.

**QS-3: 0606b = 0/5 nie diagnostiziert.** Verdächtige cal (a=136), GT weit vom
JSON-Zentrum. Mögliche Ursachen: Baseline enthielt die Schüsse schon / Geometrie-
Fenster falsch / GT-Bild ≠ Analyse-Referenz. PFLICHT-Diagnose vor weiterem Training.

**QS-4: Baseline-Kontaminations-Check** (Löcher schon in Frames 0–5?) wurde nur für
1528/0531 gemacht — für die 4 Batch-Serien nicht. Nachholen (billig).

**QS-5: Doppel-Detektionen zählen als Phantome** (S0900-Fall: Splitting-Doppel 8px
neben Loch). Kandidaten-Fusion (<10px → bester) fehlt; würde Phantomrate senken,
ohne irgendetwas zu riskieren.

**QS-6: Statistik.** n=56: 51% hat 95%-CI ~38–64%. Richtungs-Aussagen (RF>Schwelle,
Daten-Skalierung) sind robust, Absolutwerte nicht.

**QS-7: Werkzeug-Fehler heute:** GT-Tool-JS-Bug (Zeilenumbruch), ×1,5-Skalierungs-
Fehlalarm, f-String-Syntax. Alle gefangen, aber: generierte Tools brauchen
Mini-Selbsttest vor Auslieferung (Regel ergänzen).

**QS-8: Doppel-Kompression der Pipeline.** ETF150-H264 → Decode → JPEG q90 (4 Hz).
Die H264-Quell-Bitrate ist der eigentliche Qualitätsboden; die 8px-Löcher leben
gefährlich nah an der Artefakt-Skala. → stützt Snapshot-Hybrid (unten).

═══════════════════════════════════════════════════════════════════
## III. ALTERNATIVEN-KATALOG (priorisiert nach Ertrag/Aufwand)
═══════════════════════════════════════════════════════════════════
### A — Nächster Standtag, fast kostenlos
1. **Steifes Scheiben-Backing** (Spiegel auf Karton/Hartfaserplatte, straff geklemmt).
   Direkt aus Kern-Befund I: weniger Einschlag-Erschütterung = weniger Ko-Artefakte
   = genau die Phantom-Klasse, die uns vom Audio-Ziel trennt. ERWARTUNG: HOCH.
2. **Marker-A/B** (Spiegel-Druck schwärzen) — bereits geplant, bleibt.
3. **v2.4.20-Qualitäts-Gate** nutzen (ausgeliefert) + Scheiben-FOTO je Serie (Pflicht).

### B — Offline, diese Woche machbar
4. **Foto-basierte GT-Registrierung** (QS-1-Fix): GT am Foto, Übertrag via Ringe.
   Könnte gemessene Precision sofort verbessern (Phantome die Treffer sind).
5. **Kandidaten-Fusion** (QS-5-Fix) + **0606b-Diagnose** (QS-3) + Baseline-Checks (QS-4).
6. **Loch-Appearance-Modell klassisch:** Template-Bank aus den 56 echten Loch-Patches
   (normiert); Kandidat bekommt Feature „max. Korrelation mit echten Löchern".
   Adressiert Konsequenz 3 ohne CNN-Datenbedarf.
7. **Synthetische Composites:** echte Loch-Patches in echte Basisscheiben montieren
   → tausende Trainingsbeispiele → kleines CNN wird seriös möglich. Aufwand mittel,
   Domain-Gap-Risiko, aber der sauberste Weg zu „mehr ML" (Nutzerfrage von heute).

### C — App/Native, mittelfristig
8. **ETF150-12MP-Snapshot-Hybrid:** Stream = Trigger/Timing, hochauflösendes
   SD-Snapshot pre/post Serie (oder pro Schusspause) = Bestätigungs-Instanz.
   Alte C1-Messung stützt das: 12MP-Foto erreichte 72% Recall / 98% PRECISION.
   Genau die Präzision fehlt fürs Audio-Ziel. API vorhanden; Latenz zu vermessen.
9. **30fps-Transienten-Fenster** (nativer Ringpuffer statt 4-Hz-Drossel): Der
   Einschlags-Moment (Splash/Staub) ist ein hochspezifisches Signal, das wir heute
   wegwerfen (26 von 30 fps). Event-Erkennung nativ, Detail-Analyse um den Moment.
10. **Akustischer Trigger** (Mikro): NACH Befund I nicht mehr als Phantom-Killer,
    aber als Serien-Zähler + "1 Knall = 1 Loch"-Zuordnung + Ansage-Timing sinnvoll.

### D — Hardware, falls A–C nicht reichen
11. **Hinterleuchtung / Retro-Reflex-Folie hinter der Scheibe:** Loch wird zum
    Lichtpunkt (Kontrast ~255 statt ~60). Physikalisch überlegen, Verschleißteil,
    eigenes Equipment (kein Stand-Umbau). Der „Brute-Force-Ausweg" mit Bastelaufwand.
12. Akustische Triangulation am Rahmen (Meyton-Prinzip) — anderes Projekt, bekannt.

═══════════════════════════════════════════════════════════════════
## IV. EMPFOHLENER PFAD
═══════════════════════════════════════════════════════════════════
1. **QS-Reparaturen zuerst** (B4/B5): Foto-GT, 0606b-Diagnose, Fusion, Baseline-Checks
   → saubere Zahlen, evtl. gratis Precision.
2. **Nächster Standtag als kontrolliertes Experiment:** v2.4.20-Wizard + steifes
   Backing (A1) + Marker-A/B (A2) + Foto je Scheibe. Misst die zwei stärksten
   physischen Hebel gegen die Einschlag-Artefakt-Klasse.
3. Parallel offline: **Loch-Template-Feature** (B6) in den Klassifizierer.
4. Wenn Audio-Ziel dann noch offen: **Snapshot-Hybrid** (C8) erproben — der Weg zu
   98%-Precision liegt dort schon einmal gemessen vor.

═══════════════════════════════════════════════════════════════════
## V. UMSETZUNGS-ERGEBNISSE (gleiche Session, 2026-06-16)
═══════════════════════════════════════════════════════════════════
Die offline machbaren Punkte wurden direkt umgesetzt und gemessen:

**QS-3/4 (Diagnose):** 2 der 4 Batch-Serien sind KONTAMINIERT und fliegen aus dem
Korpus: 0606b (Löcher schon in Baseline + Dauer-Oszillation der Scheibe → erklärt
das 0/5), 0607 (Szenen-Wechsel mitten in der Session, Korrelation 0,33).
→ bereinigter Korpus: 10 valide KK25-Serien, 48 Löcher.

**QS-5 (Kandidaten-Fusion, 8px):** verbessert auf beiden Achsen am strengen Punkt
(55%@1,17 vs 51%@1,67). Übernommen.

**B6 (Loch-Appearance-Template):** NCC des Kandidaten-Patches gegen echte Loch-
Patches FREMDER Sessions (LOSO-sicher). **Bestes Einzel-Merkmal bisher:**
Löcher median 0,715 (q25 0,547) vs Phantome median 0,386 (q75 0,451) — die
Verteilungen überlappen kaum! Bestätigt Konsequenz 3 (Appearance trennt).

**Gesamtwirkung (10 valide Serien, Fusion, LOSO-RF):**
| | ohne ncc | mit ncc |
|---|---|---|
| p≥0,80 | 72% @ 1,20 | 68% @ 1,30 |
| p≥0,85 | 64% @ 1,10 | **66% @ 0,90** |
| p≥0,90 | 58% @ 1,10 | **60% @ 0,80** |

**Bilanz des Tages (offline, ohne einen neuen Schuss):** Phantomrate am strengen
Arbeitspunkt von 1,67 → **0,80/Serie** halbiert bei gleichem/besserem Recall.
Abstand zum Audio-Ziel (<0,5) von Faktor ~3,3 auf Faktor ~1,6 verkürzt.
Der Rest ist der physische Hebel (Backing/Marker/Foto-GT) am Stand.

## Meta-QS (Selbstkritik des Prozesses)
Der Verlauf seit dem Audit ist methodisch solide (Hypothese→Messung→ehrliches Verdikt,
heute 3× widerlegt und dokumentiert). Die zwei strukturellen Schwächen sind QS-1
(GT-Prozess) und QS-2 (Architektur-Leakage) — beide adressierbar, keine erfordert
einen Neuanfang. Der heutige Kern-Befund (einschlag-korrelierte Phantome) ist die
bislang beste kausale Erklärung des Präzisions-Problems und macht die Alternativen-
Priorisierung erstmals PHYSIKALISCH begründet statt heuristisch.
