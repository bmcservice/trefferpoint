# TrefferPoint — Lage für kritischen Review (2026-06-07)

## Ziel
Echtzeit-, hands-free Trefferanzeige + Audio-Ansage ("8 auf 3 Uhr") für Sportschützen.
Kamera (Apexel ETF150, RTSP ~2 fps, JPEG/H264, 1080p) am Spektiv, schaut auf die
Scheibe (schwarzer Spiegel ∅200 mm). Disziplinen: KK25 (.22) und GK25 (9 mm), 25 m.
**.22 ist die Benchmark — ohne .22 ist die Idee tot (Nutzer-Setzung).**

## Stand der App
- Alte/v2-Pipeline live: **0/10 echte Treffer** (Pappe-validiert).
- Neuer validierter Detektor (EMA-Center-Surround-Baseline + Zweizonen-Polarität +
  Stör-Gate + Form-/Persistenz-Filter): **~44% Recall bei 0 Phantomen** auf 4-Session-
  Korpus (18 GT-Löcher, Leave-one-out). Als "Detektor v3" primär ausgeliefert,
  konfidenz-gestuft (sichere Treffer angezeigt, schwache nur geloggt). Plus Stabilitäts-
  Wächter (Freeze/Disturbance) und shadow_log.

## Die Decke: ~56% (schwache/Mitte-Löcher)
Pappe-validiert pro Session: ~1 starkes Loch klar erkannt, der Rest (Mitte) nicht.

## Schlüssel-Befund (Kontrast-Messung an den GT-Löchern, volle Auflösung)
Die VERPASSTEN Löcher sind NICHT schwach: absoluter Kontrast **190–242**, gut aufgelöst
(6–9 px). Was hoch ist, ist das lokale σ (50–97) drumherum = die **aufgedruckte
Ringstruktur** (12 Ziffern @ 3/6/9/12 Uhr × 3 Radien + Ringlinien), in der die Mitte-
Treffer landen (Schütze zielt zur Mitte). Die getroffenen Löcher sitzen in SAUBEREN
Bereichen (σ 7–17, SNR 16–32).

→ Diagnose: **Kein SNR-Problem.** Die Löcher sind stark. Aber der einzige Weg, sie gegen
den statischen Druck zu finden — der **temporale Diff** (Frame vs. mitlaufende Baseline) —
wird genau im bedruckten Zentrum vom **Jitter/Drift der Druckkanten** korrumpiert. Das
zerstört Amplitude UND Form des Loch-Signals im Diff.

## 8 Offline-Hebel getestet — ALLE negativ für .22-Mitte
1. globale Amplituden-Schwelle — Loch-Score überlappt Phantom-Band
2. Persistenz/Permanenz (K bis 12, mean/sum) — keine Trennung
3. temporale Mittelung — keine Trennung
4. digitale Auflösung (voll statt halb) — Signal+Rauschen steigen zusammen
5. Sub-Pixel-Phasenkorrelations-Registrierung — leicht schlechter
6. Farbkanäle R/G/B + R-B — keine Trennung (Rotfilter-Hypothese widerlegt)
7. digit-aware räumliche Schwelle (hoch an bekannten Ziffernorten) — schlechter
   (Rauschboden ist verteilt, nicht nur an Ziffern; echte Löcher liegen oft selbst dort)
8. Form/Template (NCC mit rundem Template) — schwache Löcher erscheinen im Diff GAR NICHT
   rund (Clutter zerstört die Form), Phantome zufällig runder

## Akquisitions-Randbedingungen
- Standbeleuchtung NICHT änderbar (nur eigenes Equipment).
- Optische Vergrößerung: Löcher sind schon gut aufgelöst (nicht auflösungslimitiert) →
  fraglich, ob es hilft (kann nicht offline simuliert werden — fehlende Photonen).
- IR: ETF150 hat IR-Sperrfilter; LED-Standlicht strahlt kaum IR → passiv-IR praktisch tot.
- Ziffernlose Scheiben gibt es nicht.

## Geplanter letzter "Strohhalm" (kontrollierter A/B-Stand-Test)
Zwei .22-Serien back-to-back, identischer Aufbau: eine normale Scheibe (Kontrolle), eine
mit **schwarz übermaltem Spiegel-Druck** (Marker über Ziffern + Ringlinien → gleichmäßig
schwarze Fläche; App wertet aus Kalibrierung). Frage: rettet "Druck physisch weg" die
Mitte-Erkennung? B≫K → Druck war der Blocker; B≈K → JPEG/Drift-Boden ist die Grenze.

## Strategische Optionen
1. Marker/vorgeschwärzte Scheiben als Routine (falls A/B positiv).
2. Andere Sensorik — akustische Triangulation am Rahmen (wie Meyton/Sius). Anderes Projekt.
3. Scope ehrlich begrenzen: klare/äußere Treffer + größere Kaliber; .22-Mitte = Grenze.

## Disziplin-Kontext (warum diese Lage glaubwürdig ist)
Validierung durchgehend gegen physische Pappe (Schütze klickt GT). Leave-one-out, feste
Parameter, Kontrollen. Mehrere eigene Fehler unterwegs gefunden+korrigiert (Patch-max-
Artefakt, Fokus-Filter-Bug, Vorzeichen-Bug). Frühere "5/5"-Behauptungen waren Selbstbetrug,
durch GT widerlegt.
