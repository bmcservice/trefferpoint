# TrefferPoint — Roadmap zur funktionierenden App
Stand 2026-06-07 · gegründet auf live-validierten Befunden

## Ausgangslage (Pappe-validiert, A+B Stand 2026-06-07)
- Alte/v2-App-Detektion: **0/10 echte Treffer** → muss ersetzt werden.
- Shadow-Detektor **starkes Signal: 2/2 echt** (zuverlässig). B gesamt 4/5 vs App 0/5.
- **Schwache Treffer** unzuverlässig (B 3/5, A 0/5) — im selben Score-Band wie Phantome.
- **Stabilität ist Vorbedingung** (C durch Bewegung kontaminiert, 06-06 durch Freeze).
- Architektur bestätigt: EMA-CS-Baseline + Zweizonen + Stör-Gate + Form + Persistenz.

## Zielbild (realistisch, ehrlich)
Eine App, die **klare Treffer zuverlässig + mit ~0 Phantomen** meldet, **unsichere markiert**
statt zu raten, und **instabiles Bild erkennt** (Serie als ungültig kennzeichnet).
NICHT garantiert: 5/5 auf grenzwertigen Löchern — das hängt am Akquisitions-SNR.

---

## Phase 0 — Labeled GT-Korpus (offline, SOFORT, ich)
**Was:** Aus den sauberen Sessions (A, B, 06-03, 06-06) die echten Lochpositionen extrahieren
(final−init bei stabilen Serien) → ein versioniertes, gelabeltes Validierungs-Set.
**Output:** `sessions/corpus/` mit Frames-Referenz + GT-Positionen + `score_corpus.py`.
**Gate:** Korpus existiert, reproduzierbar, ≥4 Sessions mit GT.

## Phase 1 — Detektor offline härten (offline, ich) — gated by P0
**Was:**
- **Kaliber-bewusster Form-Filter** (.22/.25/.357 → AMAX skaliert mit Kaliber·pxPerMm).
- **Score-Schwelle „confident hit"** datengetrieben aus dem Korpus (Trennband stark vs Phantom).
- Tuning NUR gegen den Korpus, **Leave-one-out** (keine Einzel-Session-Überanpassung).
**Gate (messbar, am Korpus):** confident-Band Precision ≥95 %, Recall klarer Treffer ≥80 %,
≤2 Phantome/Session. JS-Port-Äquivalenz zum Python-Referenz ±1 Treffer.

## Phase 2 — Stabilitäts-Wächter (code, ich) — parallel zu P1
**Was:**
- **Freeze-Erkennung** (identische Frames wie 06-06) → keine toten Frames loggen + Warnung.
- **Disturbance-Flag** (globaler Frame-Sprung wie C) → Serie als „instabil" markieren.
- In-App-Hinweis „Bild instabil — Serie ungültig".
**Gate:** 06-06-Freeze + C-Bewegung werden korrekt geflaggt (an den vorhandenen Daten geprüft).

## Phase 3 — Shadow → primärer Detektor in der App (earned) — gated by P1+P2
**Was:** Den validierten Detektor von Shadow auf **primär** heben (ersetzt die kaputte Pipeline).
Konfidenz-Stufen: **sichere Treffer angezeigt, unsichere markiert.** Shadow-Logging bleibt.
**Gate:** 1 Live-Stand-Session reproduziert die Korpus-Leistung (confident-Treffer = echte Löcher,
0 angezeigte Phantome). Erst dann ist es der „echte" Detektor.

## Phase 4 — Schwache Treffer / Stretch (data-driven) — gated by P3
**Was:** Recall der schwachen Treffer heben — über **besseres Signal** (Belichtung/Stabilität/
ggf. Auflösung) oder Akkumulation/Orts-Tracking. Ehrlich gegen die SNR-Decke.
**Gate:** messbare Recall-Steigerung am Korpus OHNE Phantom-Anstieg; sonst dokumentiert als Grenze.

---

## Querschnitt (immer)
- **GT-Pflicht je Stand-Session:** Scheibenfoto + Schusszahl + Detektor-Modus (Wizard macht das fast).
- **Regression-Gate:** keine Änderung wird ausgeliefert, die den Korpus verschlechtert.
- **Disziplin:** jede Aussage mit Kontrolle + Konfidenz; keine Version ohne bestandenes Gate.

## Arbeitsteilung
- **Ich, offline (sofort):** P0, P1, P2 — komplett aus vorhandenen Daten.
- **Du, Stand (wenn P1+P2 stehen):** je 1 saubere Serie pro Kaliber, Scheibe+Foto → P3-Gate.
- Stabiles Setup am Stand (Stativ/ruhig) hilft direkt (C war Bewegung).
