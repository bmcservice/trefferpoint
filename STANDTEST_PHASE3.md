# Stand-Test Phase 3 — Validierungsdaten für den v4-Kern (2026-06-11)

**Ziel:** Frische, unabhängige Daten für (a) die Bestätigung des eingefrorenen v4-Kerns
(Offline-Stand: 71% Recall / 0,67 Phantome pro Serie, LOSO) und (b) das spätere
ML-Training. Der Engpass ist DATENMENGE, nicht Software — die App ist bereit.

## Vorbereitung (zu Hause)
- [ ] Tablet: App öffnen, Version prüfen → muss **v2.4.19** sein (Prozessregel!)
- [ ] 4–6 frische KK25-Scheiben einpacken, Stift zum Beschriften
- [ ] Optional für Marker-A/B: matter schwarzer Permanentmarker (siehe STANDTEST_22_marker.md)
- [ ] Stabile Kamera-Halterung (Lehre aus C357/0531: Scheiben-/Kamera-Bewegung ist
      die Haupt-Phantomquelle — alles so fest wie möglich)

## Am Stand, je Serie (3–4 Serien)
1. Frische Scheibe aufhängen, **gut festklemmen** (Papier soll sich beim Einschlag
   möglichst wenig bewegen)
2. **Diagnose-Test-Wizard** komplett durchlaufen (Weiter-Weiter-Weiter) —
   der aktiviert die Frame-Aufzeichnung automatisch, volle Sequenz, kein Handgriff nötig
3. Nach dem Referenzframe **2–3 s warten** (Belichtung einschwingen lassen)
4. **5 Schuss**, Richtung Zentrum (die schweren Fälle), im normalen Tempo
5. Während der Serie: **nichts am Setup anfassen**, nicht ans Stativ stoßen
6. Zeigt die App „⚠ Stream eingefroren" → Serie ungültig, mit neuer Scheibe wiederholen
7. Scheibe abnehmen, beschriften (**P3-S1**, **P3-S2**, …), unbeschädigt mitnehmen

## Nach der letzten Serie
- [ ] Jede Scheibe **frontal fotografieren** (Spiegel formatfüllend, gerade,
      gleichmäßiges Licht) — das ist die Ground-Truth, Pflicht-Artefakt
- [ ] Auf dem Tablet NICHTS löschen — Frames + Session-JSONs liegen unter
      `/Android/data/de.bmcservice.trefferpoint/files/detframes/<session>/` bzw. Download
- [ ] Optional: eine Leer-Serie (Wizard durchlaufen, 0 Schuss, 2–3 min laufen lassen)
      als Negativ-Kontrolle für die Phantom-Rate

## Optional: Marker-A/B (wenn Zeit für Serie 4+5 ist)
Wie in STANDTEST_22_marker.md: eine Serie normal (Kontrolle), eine mit geschwärztem
Spiegel-Druck — mit dem v4-Kern wird der Vergleich doppelt aussagekräftig
(misst direkt, wie viel die Druckkanten den temporalen Detektor noch kosten).

## Was danach passiert (meine Seite)
1. GT-Klick-Tool für die neuen Scheiben-Fotos
2. v4-Kern v1 UNVERÄNDERT gegen die neuen Sessions laufen lassen → der ehrliche
   Out-of-Corpus-Test (Recall + Phantome/Serie mit Beleg-Bildern)
3. Korpus erweitern → ML-Klassifizierer neu trainieren → Schwellen datengetrieben
4. Wenn die Zahlen halten: JS-Portierung des v4-Kerns in die App (v2.5.x)
