# Externe Reviews des Audit-Berichts (2026-06-11)

Drei unabhängige Reviews des .22-Detektors. Volltext deepseek: `reports/deepseek_review.txt`.
Befund-Eingabe an alle: Basisscheiben-Subtraktion + globale Phasenkorrelations-Reg →
Blob-Detektion; Phantome = Weiß-Druck-Kanten; LOSO-verifiziert ~53% Recall, ~2,7 Phantome/Serie.

## Konvergenz (Codex GPT-5 + ChatGPT GPT-5 stimmen überein)

**1. Der eigentliche Berg ist die PRÄZISION, nicht der Recall.**
ChatGPT: „Recall >60% ist leicht erreichbar; <0,5 Phantome/Serie ist der eigentliche Berg."
Ziel <0,5 = Faktor 5 unter aktuell 2,7/Serie. Daran hängt die Audio-Tauglichkeit.

**2. Phantome = ZWEITE OBJEKTKLASSE, kein Rauschen.**
Systematischer Störprozess an bekannten Druckkanten. „Mit nur Schwellen, Top-Hat und Rundheit
nicht stabil lösbar" → explizites Druck-/Kantenmodell + Klassifikation nötig.

**3. Persistenz präzisiert (mein naiver Ansatz war falsch):**
NICHT „Blob in mehreren Frames sichtbar" — Druckkanten-Phantome sind STABILER BIAS und bleiben auch.
RICHTIG: Step-Change (plötzliche Geburt) + stabile registrierte Position + Vorzeichen/Form stabil
+ korreliert NICHT mit dem geschätzten Frame-Shift (Phantome korrelieren, Löcher nicht).

**4. Empfohlene Zielarchitektur (beide ~deckungsgleich):**
```
NICHT:   final − baseline → TopHat → Blob → Treffer
SONDERN: frame_t vs baseline → geometrisch normalisiert (Polar, Spiegelzentrum/Radius)
         → Druckmaske (kantenbewusst gewichtete Registrierung)
         → Kandidat → temporale Bestätigung (Geburt+Persistenz)
         → kleiner Patch-Klassifizierer → Treffer → erst dann Audio
```

**5. Druckmaske > kosmetisches Inpainting.** Kandidaten AUF Kanten nicht hart löschen,
sondern „edge-near" separat klassifizieren (echte Treffer auf Ringlinie müssen behandelbar bleiben).

**6. Patch-Klassifizierer JA, CNN NEIN (n zu klein).**
Modelle: LogReg / RandomForest / SVM / GradientBoosting, LOSO.
Features: Rundheit/Elliptizität, radiales Intensitätsprofil, Symmetrie, Residuum-Vorzeichenmuster,
Persistenz, Korrelation mit Druckkante, Vorher/Nachher-Patch-Diff, JPEG-Blocknähe.

**7. Hilfsmittel, nicht Kern-Detektor:** Polar-Unwrapping (Interpolation verschmiert 8px-Loch →
nur für Reg/Maskierung) und lokal-affine Registrierung (nur auf stabilen Strukturen, Trefferregionen
maskiert, begrenzt — sonst „fittet" sie echte Löcher weg).

**8. Statistik-Warnung:** n=28 zu klein. 53% Recall → 95%-Wilson-Intervall 36–70%.
2,7 Phantome/Serie → Poisson ca. 1,5–4,3. 12px-Match großzügig (Loch ~8px) — ok für Anzeige/Audio,
fragwürdig für Scheibenauswertung. → Mehr Daten + Stratifikation (Abstand zur Druckkante,
Ringlinie/Ziffer/blank, Kontrast, Ringnähe).

**Grundsatz (ChatGPT):** „Dein Ansatz ist nicht falsch. Aber der aktuelle Schwerpunkt ist falsch."
Basis-Subtraktion richtig — der Fehler ist, Schwellen/Top-Hat/Rundheit zum Kern zu machen und
Druckkanten nur nachträglich zu behandeln.

## deepseek-r1 (lokal, 32B)
Bestätigt alle Punkte (TOL, LOSO, n=28, Hebel a–e), liefert aber nichts über Codex/ChatGPT hinaus —
paraphrasiert weitgehend die Eingabe. Schwächster der drei.

## Fable-5-Review (2026-06-11, viertes Modell — mit eigenen numerischen Tests)

Anders als die Text-Reviews wurden hier 4 eigene Hypothesen direkt am Korpus getestet:

| # | Hypothese | Ergebnis |
|---|---|---|
| F1 | B-Boden = lokale Photometrie-Drift | **widerlegt** (Korrektur ändert nichts) — UND: B-Rätsel existiert in der verifizierten Pipeline gar nicht mehr (Boden 39 statt 185, 5/5 trennbar; „185" war Artefakt der kaputten Pipeline). **Offener Audit-Punkt B geschlossen.** |
| F2 | `abs()` wirft Polarität weg; Loch im Spiegel = IMMER hell (positiv), Druckkanten-Geister = Dipole | **BESTÄTIGT — neuer Befund.** Bei festem T=20: Phantome B 14→0, 0603 5→1, 0606 11→1, Recall unverändert. Ohne 0531: **2 Phantome/5 Sessions = 0,4/Serie bei gleichem Recall** — das <0,5-Ziel ist auf 5 von 6 Sessions mit einem Ein-Zeilen-Wechsel (Vorzeichen statt Betrag) erreichbar. ABER: LOSO-Aggregat zeigt das nicht (35% Recall), weil die Schwellwert-Wahl über die Utility r−0,5f bei pos andere Arbeitspunkte wählt → die Utility-Funktion selbst ist ein Review-Punkt (willkürliche Gewichtung). |
| F3 | AE-Einschwingen verschmutzt Baseline-Frames | **widerlegt** (Drift 0,1–0,2 Stufen, stabil) |
| F4 | Baseline-Kontamination in den 14-Frame-Sessions (1528/0531) | **widerlegt** (0/10 Löcher im Baseline-Fenster) — Korpus ist sauber. Aber: 1528/0531 haben nur 14 Frames → temporale Persistenz-Methoden dort nicht testbar; künftige Sessions mehr Frames speichern. |

**0531 ist der eigentliche Endgegner:** höchste Vergrößerung (a=310), schwächste Loch-Peaks (53–91),
12 Phantome die AUCH positiv sind (Polaritäts-Filter wirkungslos), AE-Sprung bei Frame 7.
Eigene Ursachen-Analyse nötig (was sind diese hellen Nicht-Löcher?).

**Einordnung:** kein Widerspruch zu den GPT-5-Reviews, aber komplementär — die GPT-5-Reviews
lieferten Architektur (Druckmaske, Geburt/Persistenz, Patch-Klassifizierer: bleibt alles gültig);
Fable lieferte Code-Ebene-Befunde (abs→signed als sofortiger Phantom-Killer, B-Rätsel geschlossen,
Korpus-Sauberkeit verifiziert). Polarität gehört als Feature/Vorfilter in die v4-Architektur.

## Konsequenz für die Roadmap
Die Reviews bestätigen das Audit-Fundament UND geben eine klare neue Richtung, weg vom Detektor-
Tuning: Druckmaske + temporale Geburt/Persistenz + Patch-Klassifizierer, mit PRÄZISION (<0,5
Phantome/Serie) als Hauptziel. Recall gilt als „leicht" — der Test ist die Phantom-Rate.
