# Konkurrenz-Befund: TargetSpotter (recherchiert 2026-10-16)

**Kurzfassung:** Deutsche Android-App mit nahezu identischem Konzept wie TrefferPoint
(Spektiv + Kamera → elektronische Trefferanzeige auf Papierscheiben, live, Ringwert/
Zehntel/Serie). Sehr jung (Beta-Tester-Aufruf WAFFEN-online ~08/2026), iOS „in Planung".
Kein Hinweis, dass sie das .22-Erkennungsproblem besser gelöst haben als wir —
im Gegenteil: dieselben physikalischen Grenzen, offen dokumentiert.

## Fakten
- Website: https://targetspotter.app/ (dt./engl.), Google-Play-App, Android ≥ 8
- Preis: Freemium — Gratis limitiert (3 Treffer angezeigt, Pause nach 20), **Pro 24,99 €/Jahr**
- Scheiben: DSB/BDS — **25m Präzision (=KK25)**, 25m Duell, 10m LP, 50m Pistole/KK-Gewehr,
  100/300m Gewehr, 6 NRA-Pistolenscheiben
- Kamera-Setups wie bei uns: Handy am Spektiv / **USB-UVC-Kamera** / WLAN-Kamera
  (getestet lt. FAQ: Bresser Spektar 15-45x60 bei ~35x, Budget-Handys)
- Features: Auto-Spiegel/Zentrum-Erkennung, Kalibrier-Schritt, Duell-Programm mit
  ISSF-Timing, „AprèsShot" (Foto-Nachauswertung)

## Erkennungs-Ansatz
1. **Knallerkennung (Mikrofon) als Normalmodus** — Schussknall triggert die Auswertung;
   ohne Mikro 3-Sekunden-Bildvergleich (Akku-Last). = unsere Deep-Dive-Idee C10.
2. **Bewusst auf Innenringe begrenzt** (25m Präzision: ab Ring 6 aufwärts), laut
   Entwickler „um die Rate hoch zu halten" — keine Zweizonen-Erkennung.
3. **Keine Erkennungsraten-Angabe.** FAQ-Caveats: stark kameraabhängig, nicht
   wettkampftauglich, Fehlerkennungen durch Licht/Bewegung/**Vibration**, Outdoor
   unzuverlässig, optischer Zoom problematisch → exakt unsere gemessenen Grenzen
   (inkl. Einschlag-Erschütterung, vgl. P12-Kernbefund).
4. **UX-Fallback:** Verpasster Treffer → App zeigt 4 s das Live-Kamerabild zur
   Selbstkontrolle. <100% Recall wird offen in die Bedienung eingebaut — entspricht
   unserem „visuelle Bestätigungs-Anzeige"-Use-Case aus dem KK25-Verdikt (P11).

## Bedeutung für TrefferPoint
- **Markt-Validierung:** Der Use-Case trägt ein kommerzielles Produkt (25 €/Jahr als
  Preisreferenz) — und derselbe technische Ansatz landet an denselben Grenzen.
- **Kein Technik-Vorsprung erkennbar** beim Kernproblem (.22-Phantome/Recall);
  Indizien: keine Raten, Innenring-Beschränkung, Selbstkontroll-Fallback.
- **Abgucken wert:** (a) Knall-Trigger als Serien-Zähler/Energiesparer,
  (b) 4s-Bild-Fallback als ehrliche UX für Misses, (c) Duell-Programm/ISSF-Timing,
  (d) Scheiben-Breite (NRA, 50-300m) als Ausbaupfad.
- **Unser Vorsprung:** GT-validierte Pipeline mit gemessenen Kurven (P11: ~70% @ 1,5
  Phantome/Serie; P12-Stand: 66% @ 0,9), Qualitäts-Gate (v2.4.20), Klassifizierer,
  Einschlag-Artefakt-Kausalanalyse.

## Offener Punkt
**A/B-Benchmark am Stand:** TargetSpotter parallel zu TrefferPoint auf derselben
Serie laufen lassen (Scheiben-Foto = gemeinsame GT) → deren Erkennung erstmals
quantifizieren und gegen unsere Zahlen stellen.

## Vermarktungsansatz (Lehrstück für TrefferPoint)
- **Freemium mit Komfort-Limits:** Gratis-Version ist nutzbar, aber spürbar begrenzt
  (nur 3 Treffer sichtbar, Zwangspause nach 20) → niedrige Einstiegshürde, Conversion
  über Komfort statt über Kernfunktion. **Pro als Abo 24,99 €/Jahr** (nicht Einmalkauf).
- **Community-Go-to-Market:** Beta-Tester-Aufruf direkt im Zielgruppen-Forum
  (WAFFEN-online), aktive Bitte um Nutzer-Reports (z.B. Outdoor) → kostenlose
  Feldtests + Early-Adopter-Bindung + Sichtbarkeit ohne Werbebudget.
- **Ehrliche Erwartungssteuerung als Verkaufsargument:** FAQ nennt Grenzen offen
  („nicht für offizielle Wertung", kameraabhängig) → weniger Enttäuschung, weniger
  Support, rechtliche Absicherung.
- **„Dein Handy reicht"-Positionierung:** Budget-Handys getestet, konkretes günstiges
  Spektiv (Bresser ~35x) genannt, Setup-Varianten dokumentiert → keine
  Hardware-Kaufhürde vor dem App-Download.
- **Für TrefferPoint ableitbar:** 25 €/Jahr als Preisanker; Forum-Beta als Launch-Weg;
  unser Differenzierer wäre die MESSBARE Ehrlichkeit (wir können belegte
  Erkennungsraten + Qualitäts-Gate nennen — sie nennen gar keine Zahlen) plus
  Tablet/APK-Route und ETF150-WLAN-Setup.

## Quellen
- https://targetspotter.app/en/ · https://targetspotter.app/?p=51 (Was ist TS)
- https://targetspotter.app/?page_id=20 (FAQ) · https://targetspotter.app/?page_id=18 (Anleitung)
- https://forum.waffen-online.de/topic/487775-targetspotter-elektronische-trefferanzeige-im-smartphone-tester-gesucht
- Ähnliche Apps: TargetScan (ISSF, validiert lt. IAT-Studie), Shooting Target Scorer
  (RFSAT, RTSP-fähig), TargetAI (Foto-KI, DSB/BDS), LongShot/TargetVision (Hardware)
