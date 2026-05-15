# Befund Praxistest 2026-05-15 (v2.3.172)

Daten: 3 JSON (S1-first Smoke 5s, S1-full 615s, S2 166s) + 4 Videos
(VID0003-0006, diesmal **3.6 Mbit/s** — analyse-tauglich).

---

## TEIL 1 — Was funktioniert (alle Infrastruktur-Fixes greifen)

| Fix | Beleg |
|---|---|
| **Cal-Lock (v2.3.171)** | `cal.locked = True` in **allen** Sessions (2026-05-14 war es `n/a`) — manuelle Kalib wird jetzt geschützt |
| **HEAD→GET (v2.3.171)** | `rec.video_paths` ist befüllt (`VID0005`/`VID0006`) — keine "verlorenen" Videos mehr |
| **REC-Anker (v2.3.172-C)** | `rec_start_iso` + `rec_start_vid_base` vorhanden → Video↔Session eindeutig |
| **Frozen-Spot-Lernen (v2.3.172-A)** | greift, lernt, exportiert (`frozen_spots[]`, `frozen_spot_learned`) |
| **Auto-REC** | VID0006 (164s) ≈ S2-Dauer (166s) → saubere 1:1-Aufnahme |

→ Die gesamte Datenkette steht. Das ist der Fortschritt ggü. 2026-05-14.

---

## TEIL 2 — Zwei kritische Probleme

### Problem 1: Frozen-Spot-Maske erstickt die Detection

| Session | Dauer | Hits | frozen_spots | `frozen_spot`-Rejects |
|---|---:|---:|---:|---:|
| S1-full | 615 s (10 min) | **1** | **28** | **1627** |
| S2 | 166 s | **2** | 18 | 752 |

S1: in 10 Minuten **1 einziger Treffer**, weil die Maske 28 Spots gelernt
und damit **1627 Detektionen** geblockt hat. Die 28 Spots (je r=35px) decken
quasi die ganze Scheibenfläche ab. Die Maske hat die Erkennung **abgewürgt**.

Ursache: Die Schwelle (≥4 Treffer / 18 s / 35 px) ist viel zu locker. Bei
einer fehlerhaften Kalibrierung (s. Problem 2) feuert die Detection überall →
der Frozen-Spot-Tracker lernt die **ganze Szene** als "eingefroren" → maskiert
alles → kaum noch echte Treffer. Die Maßnahme verstärkt das Kalib-Problem
statt zu helfen.

### Problem 2: Kalibrierung weiterhin grob falsch — jetzt aber *fest verriegelt falsch*

| Session | cal-Zentrum | Treffer-Schwerpunkt | Versatz |
|---|---|---|---|
| S1-full | (998,483) | (761,777) | **122 mm** |
| S2 | (998,483) | (1038,546) | 24 mm |

`cal.locked=True` → die Kalibrierung ist geschützt. Aber sie wurde auf einen
**falschen Punkt** verriegelt (S1: 122 mm = ~4-5 Ringe daneben). Der Lock
schützt jetzt einen schlechten Wert.

Schlimmer — die beiden Probleme verzahnen sich tödlich:
```
falsche Kalib → FPs über die ganze Scheibe
            → Frozen-Spot-Tracker lernt alles
            → 1627 Rejects, ~1 Hit
            → nie ≥3 Hits → Cal-Soll/Ist-Warnung feuert NIE
            → User bekommt keinerlei Feedback dass Kalib falsch ist
```
Die Cal-Soll/Ist-Warnung (v2.3.172-D, ab 3 Hits) konnte nicht greifen, weil
die Frozen-Spot-Maske vorher alle Hits weggefiltert hat.

### Nebenbefund: ETF150-REC bei langer Session sporadisch

S1 = 615 s Session, aber Videos VID0003-0005 sind nur 4-38 s lang. Während
der 10-min-Session brach REC mehrfach ab/neu (passt zum "erste REC nach Idle
schlägt fehl"-Muster + Mid-Session-Dropouts). S2 (kurz) → 1 sauberer Clip.

---

## TEIL 3 — Was das heißt

Die v2.3.172-Features sind einzeln korrekt implementiert, aber **die
Frozen-Spot-Maske war ein Fehlentwurf in dieser Form**: sie sollte ein
Symptom (Pseudo-Hits an festen Stellen) bekämpfen, hat aber bei der
eigentlichen Krankheit (falsche Kalibrierung) das Bild komplett zugemacht.

**Reihenfolge muss umgekehrt werden: erst Kalibrierung robust, dann
Frozen-Spot.** Solange die Kalibrierung 100+ mm daneben liegt, ist jede
nachgelagerte Filter-Heuristik Makulatur.

---

## TEIL 4 — Konkreter Plan v2.3.173

| # | Maßnahme | Begründung |
|---|---|---|
| **1** | **Frozen-Spot-Maske drastisch entschärfen ODER per Default AUS** | 28 Spots/10min ist Total-Blockade. Optionen: (a) Default-aus, Toggle im Debug; (b) Schwelle ≥8 Treffer/≥45 s/20 px-Radius + Hard-Cap max 5 Spots/Session + nie mehr als X% der Spiegelfläche maskieren |
| **2** | **Cal-Soll/Ist-Warnung schon ab 1 Hit** + ohne Frozen-Spot-Abhängigkeit | Warnung muss feuern *bevor* die Maske alles frisst. Schwelle 1 Hit, Versatz>40 mm → sofort roter Banner + Ton |
| **3** | **Lock darf Re-Kalibrierung nicht behindern** prüfen | User muss eine falsch verriegelte Kalib trivial neu setzen können (Tap-to-correct oder 3-Klick überschreibt Lock ohne Hürde) |
| **4** | **Auto-Kalib-Grundproblem** (Phase-2 offen): Two-Pass / besserer Anchor | 122 mm Versatz ist der Kern. Solange der nicht gelöst ist, hilft kein Filter |

**Sofort-Hebel für den nächsten Test: #1 + #2.** Frozen-Spot raus aus dem
kritischen Pfad, Kalib-Warnung früh und unabhängig. Dann liefert der nächste
Stand-Test wieder echte Hit-Daten statt einer leeren Session.
