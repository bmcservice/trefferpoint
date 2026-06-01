# Training-Set für Loch-Detection-Algorithmen

## Zweck

Empirische Quote-Messung verschiedener Detection-Algorithmen auf perfekten Standbildern, **bevor** Live-Pipeline-Änderungen gemacht werden. Hierarchie:

1. **Stufe 1 — Foto-Detection**: Standbild, beliebig Rechenzeit. Ziel: >95% Quote
2. **Stufe 2 — Frame-Diff**: Pre/Post aus Live-Stream
3. **Stufe 3 — Live-Detection**: Echtzeit-Trigger

Wenn Stufe 1 nicht funktioniert, ist Stufe 2/3 hoffnungslos. Daher zuerst Stufe 1 sauber bekommen.

## Konvention

Datei: `<disziplin>_<lfdnr>.<ext>`
- `kk25_01.jpg` … geschossen mit **.22** (KK)
- `gk25_01.jpg` … geschossen mit **9mm** (GK)
- (später ggf. `lg10_01.jpg`, `lp10_01.jpg`, `kk50_01.jpg`)

> **Wichtig:** Bei KK25 und GK25 wird auf **dieselbe Scheibe Krüger 3100** geschossen
> (mit identischen Einsteckspiegeln). Der Disziplin-Tag im Dateinamen unterscheidet
> nur das **Kaliber** (.22 = 5.6 mm vs 9 mm) — relevant für Detection-Filter, NICHT
> für Scheiben-Geometrie.

## Geometrie ist normiert

Aus CLAUDE.md (verifiziert gegen DSB-Sportordnung 01.01.2026), Krüger 3100:

| Element | Wert |
|---|---|
| Ring 10 Radius | 25 mm |
| Ring 1 Radius | 250 mm |
| **Spiegel-Radius** | **100 mm** (∅ 200 mm) |
| Schritt pro Ring | 25 mm |

| Kaliber | Lochdurchmesser (nominal) |
|---|---|
| .22 (KK25) | 5.6 mm |
| 9 mm (GK25) | 9.0 mm |

→ **Spiegel-Durchmesser = 200 mm = automatische Skala** (kein Lineal nötig).
→ **Erwartete Loch-Größe in Pixeln** = (5.6 oder 9) × pxPerMm — wichtig für Detection-Filter.

## Anforderungen pro Foto

- Frontalsicht (Schräge max ~15°)
- Komplette Scheibe sichtbar inkl. Ring 1 außen
- Schwarzer Spiegel-Außenrand klar erkennbar (für Skalierung)
- Smartphone-Auflösung reicht (>= 8 MP)

## Ground Truth

Pro Foto: `ground_truth_spiegel.html`-Tool öffnen, Treffer einklicken, JSON in `<dateiname>.gt.json` neben dem Foto ablegen.
