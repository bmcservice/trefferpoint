#!/usr/bin/env python3
"""Stand-Test-Auswertung: bestimmt die optimale v2.4-Verifier-Commit-Schwelle
(_v24SnrCommit) datengetrieben aus dem SNR-Log + Ground-Truth-Lochpositionen.

Input:
  1. Session-JSON (v2.4.10+) mit Feld `v24_verifier_log` = [{gx,gy,bestSnr,bestX,bestY,decision,...}]
  2. Ground-Truth: echte Lochpositionen in Stream-Koordinaten (px), z.B. aus dem
     Scheibenfoto via gt_multi.html übertragen, oder manuell als Liste.

Logik:
  Jeder Log-Eintrag mit einem bestX/bestY wird gegen die GT-Löcher geprüft.
    - nahe (< TOL_MM) einem echten Loch  → ECHT
    - sonst                              → PHANTOM
  Daraus: SNR-Verteilung echt vs phantom → Schwelle, die beide am besten trennt.

Aufruf:
  python analyze_v24_session.py SESSION.json --gt "x1,y1 x2,y2 ..." [--tol-mm 8]
  (GT-Positionen in Stream-Pixel; pxPerMm wird aus der Session-Calibration gelesen)
"""
import sys, json, argparse
from pathlib import Path
try: sys.stdout.reconfigure(encoding="utf-8")   # Windows-Konsole: Unicode robust
except Exception: pass

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session", help="Session-JSON mit v24_verifier_log")
    ap.add_argument("--gt", default="", help="Echte Lochpositionen in Stream-px: 'x1,y1 x2,y2 ...'")
    ap.add_argument("--tol-mm", type=float, default=8.0, help="Match-Toleranz in mm (default 8)")
    args = ap.parse_args()

    data = json.loads(Path(args.session).read_text(encoding="utf-8"))
    log = data.get("v24_verifier_log", [])
    calib = data.get("calibration", {}) or {}
    ppm = calib.get("pxPerMm") or (calib.get("a", 0) / 100 if calib.get("a") else None)
    if not log:
        print("FEHLER: kein v24_verifier_log in der Session (v2.4.10+ nötig).")
        return
    if not ppm:
        print("WARN: pxPerMm unbekannt — Toleranz wird in px interpretiert.")
        ppm = 1.0
    tol_px = args.tol_mm * ppm

    gt = []
    for tok in args.gt.split():
        if "," in tok:
            x, y = tok.split(","); gt.append((float(x), float(y)))

    print(f"Session: {args.session}")
    print(f"Log-Einträge: {len(log)} | GT-Löcher: {len(gt)} | pxPerMm={ppm:.2f} | Tol={args.tol_mm}mm ({tol_px:.0f}px)\n")

    real, phantom = [], []
    for e in log:
        snr = e.get("bestSnr")
        if snr is None:
            phantom.append(0.0); continue   # kein Signal = klares Phantom
        bx, by = e.get("bestX"), e.get("bestY")
        is_real = False
        if gt and bx is not None:
            for gx, gy in gt:
                if ((bx - gx) ** 2 + (by - gy) ** 2) ** 0.5 < tol_px:
                    is_real = True; break
        (real if is_real else phantom).append(snr)

    def stats(name, arr):
        if not arr:
            print(f"  {name}: (keine)"); return
        s = sorted(arr)
        print(f"  {name}: n={len(arr)} min={s[0]:.1f} med={s[len(s)//2]:.1f} max={s[-1]:.1f}")

    print("SNR-Verteilung:")
    stats("ECHT   ", real)
    stats("PHANTOM", phantom)

    if not gt:
        print("\n(Kein GT übergeben → nur Verteilung. Für Schwellen-Empfehlung --gt setzen.)")
        # Ohne GT: zeige bei welcher Schwelle wie viele Kandidaten committet würden
        print("\nKandidaten >= Schwelle (ohne GT-Klassifikation):")
        for thr in [4, 6, 8, 10, 12, 15]:
            n = sum(1 for e in log if (e.get("bestSnr") or 0) >= thr)
            print(f"  snr>={thr:>2}: {n} committed")
        return

    # Optimale Schwelle: maximiere (echte behalten) - (phantome behalten)
    print("\nSchwellen-Sweep (Recall echt | durchgelassene Phantome):")
    best_thr, best_score = None, -1e9
    for thr10 in range(20, 251):
        thr = thr10 / 10
        keep_real = sum(1 for v in real if v >= thr)
        keep_ph = sum(1 for v in phantom if v >= thr)
        recall = keep_real / len(real) if real else 1.0
        score = keep_real - keep_ph        # einfache Trenn-Gütefunktion
        if score > best_score:
            best_score, best_thr = score, thr
    for thr in [4, 6, 8, 10, 12, 15]:
        kr = sum(1 for v in real if v >= thr); kp = sum(1 for v in phantom if v >= thr)
        rec = 100 * kr / len(real) if real else 100
        print(f"  snr>={thr:>2}: Recall {rec:3.0f}% ({kr}/{len(real)}) | Phantome {kp}")
    print(f"\n=> EMPFOHLENE Commit-Schwelle _v24SnrCommit = {best_thr:.1f}")
    if real and phantom:
        margin = min(real) - max(phantom)
        print(f"  (Trenn-Marge echt-min {min(real):.1f} vs phantom-max {max(phantom):.1f} = {margin:+.1f})")

if __name__ == "__main__":
    main()
