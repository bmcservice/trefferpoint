# Stand-Test: rettet „Druck schwärzen" die .22-Mitte-Erkennung?
Kontrollierter A/B-Test. Ziel: eindeutig zeigen, ob die aufgedruckte Ringstruktur
der Blocker ist — oder ob der JPEG/Drift-Boden die Grenze ist.

## Hypothese
8 Software-Hebel sind durch (negativ). Befund: die .22-Mitte-Löcher sind absolut
STARK (Kontrast 190+), werden aber im temporalen Diff von den flackernden Druckkanten
(Ziffern + Ringlinien) zugedeckt. Wenn wir den Druck im Spiegel physisch entfernen,
müssten die starken Löcher auftauchen.

## Material
- **Matter** schwarzer Permanentmarker, breite Spitze (matt! glänzend → neue Reflex-Artefakte).
- 2 identische .22-Scheiben (Standard DSB, KK25).
- **Stabile** Kamera-Halterung (Stativ/Klemme). WÄHREND der Serie NICHTS anfassen (Lehre aus C: ein Stoß zerstört die Auswertung).

## Aufbau (für BEIDE Serien identisch)
- ETF150 am Spektiv, Bild steht ruhig, gleicher Zoom/Ausschnitt, gleiche Position.
- App v2.4.19: Diagnose-Test (aktiviert automatisch Detektor v3 + Stabilitäts-Log + shadow_log).
- Wenn die App „⚠ Stream eingefroren" zeigt → Serie ungültig, neu.

## Serie 1 — KONTROLLE (normale Scheibe)
1. Normale, unveränderte Scheibe.
2. Diagnose-Test, **5 Schuss Richtung Zentrum** (so dass Löcher in den inneren Ringen/Ziffern landen — genau die schweren Fälle).
3. Scheibe intakt aufheben, mit „K-22" beschriften.

## Serie 2 — BEHANDLUNG (Druck geschwärzt)
1. Zweite Scheibe NEHMEN und **vor dem Schießen** den **schwarzen Spiegel innen schwärzen**:
   - die 12 weißen **Ziffern** komplett übermalen,
   - die **dünnen Ringlinien INNERHALB des schwarzen Spiegels** übermalen,
   - Ziel: der Spiegel wird eine **möglichst gleichmäßige schwarze Fläche** (matt, ohne Streifen).
   - Die äußeren Ringe (auf dem Weißen) sind egal — die App wertet aus der Kalibrierung.
2. Gleicher Aufbau, gleiche Kameraposition wie Serie 1.
3. Diagnose-Test, **5 Schuss Richtung Zentrum**.
4. Scheibe intakt aufheben, „B-22" beschriften.

## Wichtig für die Auswertung
- **Beide Serien back-to-back**, gleiche Bedingungen (nur der Druck unterscheidet sich → sauberer A/B).
- Beide Scheiben **mitnehmen + frontal fotografieren** (Spiegel ∅200 mm = Maßstab).
- Pro Serie: Detframe-Session + Session-JSON bleiben auf dem Tablet (automatisch).

## Was ich danach mache
- shadow_log + Foto je Serie → GT-Löcher klicken → Detektion scoren.
- **Vergleich:** Recall/Precision der Mitte-Löcher BEHANDLUNG vs KONTROLLE.
  - Behandlung ≫ Kontrolle → der Druck WAR der Blocker → wir verfolgen den Weg (Marker als Trainingsroutine, oder vorgeschwärzte Scheiben).
  - Behandlung ≈ Kontrolle → der JPEG/Drift-Boden ist die Grenze → .22 ist ehrlich am Methodenlimit.

## Nebenbedingungen / Fallen
- Marker MATT (Glanz = neue Phantome).
- Wirklich ruhiges Setup (kein Bump).
- Kurze Serien, AE einschwingen lassen (nach Referenz 2–3 s warten).
- Falls möglich: gleiche 5 ungefähren Zielpunkte in beiden Serien (vergleichbare Lochlagen).
