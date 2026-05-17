# Befund Kontrolltest 2026-05-17 (v2.3.174 Wizard) — ENTSCHEIDEND

## Ergebnis: SZENARIO B gesichert — Detection trackt die echten Schüsse nicht

Datenbasis: Session `202605171129440` (1 Wizard-Lauf, 900 s, 4 progressive
Saves, s_3 = vollständigste, 12 committed Hits). Ground Truth: 3×5 reale
Schüsse, vom Schützen auf maßstabsgetreuer KK25-Scheibe markiert
(`ground_truth_spiegel.html`).

### Translationsinvarianter Test (3 Serien)

| | RMS-Streuung |
|---|---|
| REAL Serie A / B / C | 113 / 67 / 62 mm (Median **67**) |
| APP Zeitgruppe 1 / 2 / 3 | 27 / 16 / 21 mm (Median **21**) |
| Faktor real/app | **3,2×** |

Eine reine Kalibrier-Verschiebung ist eine **starre Translation** und
**erhält die Streuung exakt**. Real streut 3,2× weiter als die App-Hits —
über **alle drei unabhängigen Serien** konsistent. Keine Translation kann
einen 21-mm-Cluster in ein 67-mm-Muster überführen.

→ **Szenario C (Kalibrierung) als alleinige/primäre Ursache ausgeschlossen.**

Zusätzlich:
- Real 15 Schuss (3×5), App **12** committed, Zeitgruppen **7/3/2 ≠ 5/5/5**
- App-Hits eng lower-left vom (nie verifizierten) Kalib-Zentrum geclustert
- `pts_out_of_range`: **2169×**, ALLE 2501–12816 px (mittel 5931), 0 im
  Rauschbereich → massive Großflächen-Änderungen dominieren

→ Die committed „Hits" sind Artefakte einer **bild-instabilen Detection**,
nicht die 5,6-mm-Löcher. **Szenario B.**

### Konfidenz & Caveats (ehrlich)
- Hoch. Das Streuungs-Argument ist translationsinvariant → hält trotz:
  (a) App↔Serie-Mapping nur aus Timing erschlossen, (b) Markierung mm-grob,
  (c) detframes fehlen. Über 3 Serien reproduziert.
- Was NICHT bestimmbar (Werkzeuge versagten): WAS die Großflächen-Änderung
  verursacht (Spektiv/Cam-Bewegung am Stand? Licht? Referenzframe? Decode?).

## Tragweite

Sämtliches FP-Tuning der letzten Wochen (3-Frame, Frozen-Spot, Kalib-Lock,
Banner-Race, Sens) lag **stromabwärts einer Detection, die die Schüsse nicht
sieht**. Das ist „Schraube 0/2" (Detection-Input / Referenzframe) aus der
Strategie-Analyse — nie verifiziert, immer an Schraube 4 gedreht.

## Zwei Instrumentierungs-Bugs blockierten die Diagnose

1. **detframes-Capture schrieb 0 Frames** trotz aktivem Diagnose-Modus
   (`raw_candidate`-Logs vorhanden → Diag-Modus lief; aber 0 `df_*.jpg` auf
   dem ganzen Tablet). Ohne die echten Input-Frames bleibt die Ursache der
   Großflächen-Änderung unsichtbar.
2. **Wizard-Overlay opak vollflächig** (`position:fixed;inset:0;
   background:rgba(8,10,13,0.97);z-index:10001`) → verdeckte das Kamerabild
   bei Schritt 6 (Kalib-Kreuz prüfen — war blind, „Weiter" auf Vertrauen)
   und während der 5 Schüsse (Schütze konnte Szene/Stabilität nicht
   beobachten → Antwort „nichts zu sehen außer Testanzeige").

## Kritischer Pfad (keine weitere Tuning-Arbeit)

Tuning ist sinnlos solange die Detection die Löcher nicht sieht UND wir den
Input nicht sehen können. Reihenfolge:

1. **Bug-Fix Wizard-Overlay** — bei Sicht-/Schieß-Schritten nicht-deckend
   (schmaler Balken, Kamera+Kreuz+Ringe sichtbar). Sonst jeder Folgetest blind.
2. **Bug-Fix detframes-Capture** — `setDetCapture` produzierte nichts;
   Ursache einkreisen (JS-Wiring vs. nativer Schreibpfad).
3. **EIN** Re-Test mit funktionierender Instrumentierung → endlich Schraube 0
   sichtbar machen (was ändert sich großflächig — Bewegung? Licht? refFrame?).
4. Erst DANN datengetriebene Detection-Korrektur.

ETF150 bleibt der Produktions-Track; v2.3.174 ist diagnostisch, nicht
praxisreif bis Schraube 0 geklärt.
