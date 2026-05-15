# Kontrollierte Mess-Session — Stand-Protokoll (ab v2.3.173)

**Ziel: KEINE gute Erkennung. Sondern EINE saubere, störungsfreie
Roh-Messung**, aus der wir eindeutig ablesen welche Stellschraube als
nächstes dran ist (Szenario A/B/C/D, s.u.).

Genau nach Schritten arbeiten. Jede Abweichung macht die Session wertlos.

---

## Vorbereitung (zuhause oder am Stand)

- [ ] Tablet voll geladen
- [ ] ETF150 frisch power-cycled (aus/an)
- [ ] Tablet ins `APEXEL-ETF150-CE78`-WLAN
- [ ] TrefferPoint v2.3.173 (Info-Button prüfen: Version 2.3.173)

## Setup am Stand

1. [ ] „📡 WLAN-Cam" → „▶ RTSP-Stream starten" → Live-Bild kommt (~1 s Lag)
2. [ ] **Aufwärm-REC**: „⚡ Erkennung starten" → 5 s warten → „⏸ pausieren".
       (Weckt die Cam-Aufnahme; erste REC nach Idle schlägt sonst still fehl.)

## Kalibrierung — VERIFIZIEREN, nicht nur setzen

3. [ ] Disziplin wählen (kk25 oder gk25 — die du schießt)
4. [ ] „✎ Manuell (3 Klicks)": Mitte → 3 Uhr → 6 Uhr **so genau wie möglich**
       auf den Spiegel-Außenrand
5. [ ] **Plausi-Meldung lesen.** Steht „✓ Kalibriert … plausibel" → ok.
       Steht „⚠ … fragwürdig" → Meldung lesen, nochmal kalibrieren.
6. [ ] **Visuell prüfen**: Das **grüne 🔒-Kreuz** muss exakt auf der
       Spiegelmitte sitzen, die **Ringe** müssen auf den echten Ringen liegen.
       Wenn nicht: „📍 Mitte verschieben" → 1 Tap auf die echte Mitte.
7. [ ] Erst weiter wenn Kreuz + Ringe sichtbar korrekt sitzen.

## Diagnose-Modus + Referenz

8. [ ] Häkchen **„🔬 Diagnose-Modus (roh + Frame-Capture)"** setzen.
       (Schaltet Frozen-Spot + Bestätigung AUS, speichert jeden
        Detection-Frame aufs Tablet. „🧊 Frozen-Spot" bleibt AUS.)
9. [ ] „🎬 Auto-REC bei Erkennung" anhaken (Zweit-Referenz, schadet nicht)
10. [ ] Scheibe **leer** (frische/unbeschossene Spiegel) → „📷 Referenzframe
        setzen" → 6 s Warmup-Countdown abwarten („Referenz aus N Frames").

## Die Messung — GEZÄHLTE Schüsse

11. [ ] „⚡ Erkennung starten". Unter dem Häkchen erscheint
        `🔬 Capture: OK …` → Capture läuft. Wenn dort `⚠` steht: STOP,
        Foto von der Meldung, melden.
12. [ ] **Exakt 5 Schüsse**, langsam, einzeln gezielt, **Richtung Zentrum**.
        Nach jedem Schuss ~5 s warten (sauber trennbar).
        **Mitzählen und notieren wohin du tatsächlich getroffen hast**
        (grobe Lage reicht: „Mitte / 9 auf 6 Uhr / …"). Das ist die
        Ground Truth gegen die wir auswerten.
13. [ ] Nach dem 5. Schuss 10 s warten, dann „⏸ Erkennung pausieren".
14. [ ] „💾 Session-Log speichern".

## Optional 2. Durchgang (wenn Zeit)

15. [ ] Disziplin-Wechsel testen ODER 5 weitere Schüsse mit bewusster
        Streuung (1 in jede Ecke + 1 Mitte) — wieder gezählt + notiert.

## Mitbringen / melden

- Wie viele Schüsse **tatsächlich** abgegeben (pro Durchgang)
- Grobe Lage jedes Schusses (deine Notiz)
- Auffälligkeiten (Freeze, Meldungen, Kreuz saß daneben, etc.)

---

## Was die Auswertung daraus ableitet (Szenario-Baum)

| Beobachtung (roh, ohne Heuristiken) | Diagnose | nächster Schritt |
|---|---|---|
| **A** Findet ~5 Schüsse + wenige FP, Ringe stimmen | Kern OK — nur FP-Filter offen | FP-Tuning auf echten Daten |
| **B** Feuert ständig überall, unabh. von Schüssen | Decode-Input/refFrame korrupt | Detection-Frames (Tablet) forensisch prüfen |
| **C** Findet Schüsse, aber Ringzahl falsch | Kalibrierung isoliert bestätigt | Auto-Kalib-Geometrie (Two-Pass) |
| **D** Verpasst echte Schüsse | Signal/Schwelle/refFrame schwach | Sensitivität + Referenzframe |

Jedes Szenario zeigt auf **eine** Schraube. Diese eine Session ersetzt
mehrere spekulative — weil zum ersten Mal **keine** Störvariable
mitläuft (Frozen-Spot aus, Confirm aus, Kalib verifiziert, Input
gesichert, Schusszahl bekannt).

## Daten die entstehen

- `/sdcard/Download/tp_session_*.json` — Hits + ALLE `raw_candidate`-Logs
- `/sdcard/Download/TrefferPoint/detframes/<tag>/df_<epochMs>.jpg` —
  **die echten Detection-Input-Frames** (Ground Truth, 1:1 zu JSON via
  `epochMs - session.start_ms = t_ms`)
- ETF150-SD-Video — Zweit-Referenz (physische Wahrheit, höhere Qualität)
