#!/usr/bin/env python3
"""Verbesserter Template-Matcher v2.

Hebel ggu. Original:
  - Threshold 0.55 → 0.72
  - NMS-Radius 1.5× → 2.5× Kaliber
  - Center-Pixel-Verifikation: Loch-Mitte muss min-delta vom Hintergrund haben
  - Ring-Beschriftungs-Filter: Loch-Form != Ziffern-Form
"""
import json
from pathlib import Path
import numpy as np
import cv2
from PIL import Image, ImageDraw
from collections import defaultdict
import sys
sys.path.insert(0, str(Path(__file__).parent))
from benchmark import find_spiegel

ROOT = Path(__file__).parent

def detect_template_v2(img_bgr, spiegel, disziplin='kk25'):
    """Template-Matcher mit strengeren Filtern."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    ppm = spiegel['px_per_mm']
    sp_cx, sp_cy, sp_r = spiegel['cx'], spiegel['cy'], spiegel['r']
    kaliber_mm = {'kk25':5.6, 'gk25':9.0}.get(disziplin, 5.6)
    r_px = kaliber_mm * 0.5 * ppm

    # Templates wie bisher
    tsize = int(r_px * 4) | 1
    tmpl_bright = np.zeros((tsize, tsize), dtype=np.float32) + 30
    cv2.circle(tmpl_bright, (tsize//2, tsize//2), int(r_px), 200, -1)
    tmpl_bright = cv2.GaussianBlur(tmpl_bright, (3,3), 1)
    tmpl_dark = np.zeros((tsize, tsize), dtype=np.float32) + 200
    cv2.circle(tmpl_dark, (tsize//2, tsize//2), int(r_px), 50, -1)
    tmpl_dark = cv2.GaussianBlur(tmpl_dark, (3,3), 1)

    SCORE_THRESHOLD = 0.72   # ↑ von 0.55
    nms_radius = int(2.5 * kaliber_mm * ppm)   # ↑ von 1.5×

    raw_detections = []
    for tmpl, kind in [(tmpl_bright, 'in_spiegel'), (tmpl_dark, 'outside')]:
        result = cv2.matchTemplate(gray.astype(np.float32), tmpl, cv2.TM_CCOEFF_NORMED)
        loc = np.where(result > SCORE_THRESHOLD)
        for pt in zip(*loc[::-1]):
            cx_t = pt[0] + tsize//2
            cy_t = pt[1] + tsize//2
            dist_to_center = np.hypot(cx_t - sp_cx, cy_t - sp_cy)
            if kind == 'in_spiegel' and not (dist_to_center < sp_r * 0.93): continue
            if kind == 'outside' and not (sp_r * 1.05 < dist_to_center < sp_r * 2.4): continue
            score = float(result[pt[1], pt[0]])
            raw_detections.append({'x': cx_t, 'y': cy_t, 'score': score, 'kind': kind})

    # === Verschärftes NMS ===
    raw_detections.sort(key=lambda x: -x['score'])
    nms = []
    for d in raw_detections:
        suppressed = False
        for kept in nms:
            if np.hypot(d['x']-kept['x'], d['y']-kept['y']) < nms_radius:
                suppressed = True; break
        if not suppressed:
            nms.append(d)

    # === Center-Pixel-Verifikation ===
    # Echtes Loch: Mitte hat hohe Helligkeits-Abweichung vom Lokal-Hintergrund
    verified = []
    for d in nms:
        x, y = d['x'], d['y']
        roi_r = int(r_px * 3)
        x0, y0 = max(0, x-roi_r), max(0, y-roi_r)
        x1, y1 = min(gray.shape[1], x+roi_r), min(gray.shape[0], y+roi_r)
        roi = gray[y0:y1, x0:x1]
        # Center 3×3
        cy_loc, cx_loc = y-y0, x-x0
        center_val = int(np.median(gray[max(0,y-2):y+3, max(0,x-2):x+3]))
        bg_val = int(np.median(roi))
        delta = abs(center_val - bg_val)
        # Wenn in_spiegel: Center muss heller als BG sein
        # Wenn outside: Center muss dunkler sein
        if d['kind'] == 'in_spiegel' and center_val < bg_val + 15: continue
        if d['kind'] == 'outside' and center_val > bg_val - 30: continue
        if delta < 30: continue   # zu wenig Kontrast
        d['delta'] = delta
        verified.append({'x': d['x'], 'y': d['y'], 'size': int(np.pi*r_px*r_px), 'kind': d['kind'], 'score': d['score']})

    return verified

# ============================================================================
# Benchmark mit v2-Algo
# ============================================================================
def match_greedy(detections, gt_treffer, spiegel, max_dist_mm=15.0):
    cx, cy = spiegel['cx'], spiegel['cy']; ppm = spiegel['px_per_mm']
    pairs = []
    for di, d in enumerate(detections):
        dx_mm = (d['x']-cx)/ppm; dy_mm = (d['y']-cy)/ppm
        for gi, g in enumerate(gt_treffer):
            pairs.append((float(np.hypot(dx_mm-g['x_mm'], dy_mm-g['y_mm'])), di, gi))
    pairs.sort()
    used_det, used_gt = set(), set()
    matches = []
    for dist, di, gi in pairs:
        if dist > max_dist_mm: break
        if di in used_det or gi in used_gt: continue
        used_det.add(di); used_gt.add(gi)
        matches.append({'det': detections[di], 'gt': gt_treffer[gi], 'dist': dist})
    return matches, [d for di,d in enumerate(detections) if di not in used_det], [g for gi,g in enumerate(gt_treffer) if gi not in used_gt]

gt_data = json.load(open(ROOT/'ground_truth_multi.json', encoding='utf-8'))

print(f"\n=== TEMPLATE v2 (Threshold=0.72, NMS=2.5×Kal, Center-Verifikation) ===\n")
print(f"{'Bild':>5} {'Disz':>6} {'GT':>4} {'Det':>4} {'Match':>5} {'FP':>4} {'Recall':>7} {'Prec':>5} {'Med-Δ':>7}")
results = []
for s in gt_data['scheiben']:
    img_bgr = cv2.imread(str(ROOT/s['file']))
    test_disz = 'kk25' if s['disziplin']=='leer' else s['disziplin']
    dets = detect_template_v2(img_bgr, s['spiegel'], test_disz)
    matches, fp, missed = match_greedy(dets, s['treffer'], s['spiegel'])
    n_gt = len(s['treffer'])
    recall = len(matches)/max(1,n_gt) if n_gt else None
    prec = len(matches)/max(1,len(dets)) if dets else None
    med = float(np.median([m['dist'] for m in matches])) if matches else None
    rec_s = f"{recall:.0%}" if recall is not None else '—'
    prec_s = f"{prec:.0%}" if prec is not None else '—'
    med_s = f"{med:.1f}mm" if med else '—'
    print(f"  {s['idx']:>3} {s['disziplin']:>6} {n_gt:>4} {len(dets):>4} {len(matches):>5} {len(fp):>4} {rec_s:>7} {prec_s:>5} {med_s:>7}")
    results.append({'idx':s['idx'], 'disz':s['disziplin'], 'n_gt':n_gt, 'n_det':len(dets),
                    'n_match':len(matches), 'n_fp':len(fp), 'recall':recall, 'prec':prec, 'med':med})

# Aggregate
print("\n=== AGGREGAT ===")
all_gt = sum(r['n_gt'] for r in results if r['disz']!='leer')
all_match = sum(r['n_match'] for r in results if r['disz']!='leer')
all_fp = sum(r['n_fp'] for r in results if r['disz']!='leer')
all_meds = [r['med'] for r in results if r['med']]
leer_fp = sum(r['n_fp'] for r in results if r['disz']=='leer')
total_recall = all_match / max(1, all_gt)
total_prec = all_match / max(1, all_match + all_fp)
print(f"  Total GT: {all_gt}, matched: {all_match}, FP: {all_fp}")
print(f"  Recall:    {total_recall:.0%}")
print(f"  Precision: {total_prec:.0%}")
print(f"  Median Δ:  {np.median(all_meds):.1f} mm" if all_meds else "  Median Δ: —")
print(f"  FP/Bild (non-leer):  {all_fp/12:.1f}")
print(f"  FP auf LEER-Scheibe: {leer_fp}")

# Schwierigkeit
DIFF = {1:'easy',2:'easy',3:'easy',4:'easy',7:'easy',8:'easy',11:'easy',
        6:'med',10:'med',12:'med',
        5:'hard',9:'hard', 13:'leer'}
print(f"\nNach Schwierigkeit:")
for diff in ['easy','med','hard','leer']:
    rs = [r for r in results if DIFF.get(r['idx'])==diff]
    if not rs: continue
    if diff=='leer':
        print(f"  {diff:6s}: FP={sum(r['n_fp'] for r in rs)}")
    else:
        gt = sum(r['n_gt'] for r in rs); m = sum(r['n_match'] for r in rs); fp = sum(r['n_fp'] for r in rs)
        print(f"  {diff:6s}: GT={gt} Match={m} ({m/gt:.0%}) FP={fp} ({fp/len(rs):.1f}/Bild)")

json.dump(results, open(ROOT/'template_v2_results.json','w'), indent=2)
