#!/usr/bin/env python3
"""Benchmark der 4 Detection-Algorithmen gegen ground_truth_multi.json.

Output:
  - Pro Scheibe: Recall, Precision, mediane mm-Position-Genauigkeit pro Algo
  - Gesamtbilanz: aggregierte Quoten + Schwierigkeitsklassen
  - Annotierte Übersichts-Bilder
"""
import json
from pathlib import Path
from collections import defaultdict
import numpy as np
import cv2
from PIL import Image, ImageDraw

ROOT = Path(__file__).parent

# Import der Detection-Algorithmen
import sys
sys.path.insert(0, str(ROOT))
from benchmark import find_spiegel, detect_app_replica, detect_hough, detect_dog_blob, detect_template

ALGORITHMS = {
    'app_replica': detect_app_replica,
    'hough':       detect_hough,
    'dog_blob':    detect_dog_blob,
    'template':    detect_template,
}

MATCH_THRESHOLD_MM = 15.0  # ein Treffer-Match gilt bis 15mm Abstand

def match_greedy(detections, gt_treffer, spiegel, max_dist_mm=MATCH_THRESHOLD_MM):
    """Greedy-Matching nach Distanz."""
    cx, cy = spiegel['cx'], spiegel['cy']
    ppm = spiegel['px_per_mm']
    pairs = []
    for di, d in enumerate(detections):
        dx_mm = (d['x'] - cx) / ppm
        dy_mm = (d['y'] - cy) / ppm
        for gi, g in enumerate(gt_treffer):
            dist = float(np.hypot(dx_mm - g['x_mm'], dy_mm - g['y_mm']))
            pairs.append((dist, di, gi))
    pairs.sort()
    used_det, used_gt = set(), set()
    matches = []
    for dist, di, gi in pairs:
        if dist > max_dist_mm: break
        if di in used_det or gi in used_gt: continue
        used_det.add(di); used_gt.add(gi)
        matches.append({'dist_mm': dist, 'det': detections[di], 'gt': gt_treffer[gi]})
    return matches, [d for di,d in enumerate(detections) if di not in used_det], [g for gi,g in enumerate(gt_treffer) if gi not in used_gt]

# Lade GT
gt_data = json.load(open(ROOT / 'ground_truth_multi.json', encoding='utf-8'))
print(f"Lade {gt_data['n_scheiben']} Scheiben...")

results = []
for scheibe in gt_data['scheiben']:
    fpath = ROOT / scheibe['file']
    if not fpath.exists():
        print(f"  {scheibe['file']}: FEHLT")
        continue
    img_bgr = cv2.imread(str(fpath))
    disziplin = scheibe['disziplin']
    spiegel = scheibe['spiegel']
    gt_treffer = scheibe['treffer']
    is_leer = disziplin == 'leer'

    print(f"\n=== bild_{scheibe['idx']:02d} ({disziplin}, {len(gt_treffer)} GT) ===")

    # Bei "leer": Algorithmen mit kk25-Parametern fahren — wir wollen sehen ob sie 0 finden
    test_disz = 'kk25' if is_leer else disziplin
    if disziplin == 'gk25' and scheibe.get('kaliber_mm', 0) > 9.5:
        # .357: extra-große Löcher — laufen mit gk25-Parametern (großes Kaliber)
        test_disz = 'gk25'

    scheibe_result = {
        'idx': scheibe['idx'], 'file': scheibe['file'],
        'disziplin': disziplin, 'gt_count': len(gt_treffer),
        'algorithms': {}
    }
    for algo_name, algo_fn in ALGORITHMS.items():
        try:
            detections = algo_fn(img_bgr, spiegel, test_disz)
        except Exception as e:
            print(f"  {algo_name}: ERROR {e}")
            scheibe_result['algorithms'][algo_name] = {'error': str(e)}
            continue
        matches, false_pos, missed = match_greedy(detections, gt_treffer, spiegel)
        recall = len(matches) / max(1, len(gt_treffer)) if not is_leer else None
        # Bei "leer": False-Positives sind alle Detections (nichts soll gefunden werden)
        if is_leer:
            n_false_pos = len(detections)
            precision = None
        else:
            n_false_pos = len(false_pos)
            precision = len(matches) / max(1, len(detections))
        med_dist = float(np.median([m['dist_mm'] for m in matches])) if matches else None
        scheibe_result['algorithms'][algo_name] = {
            'n_det': len(detections), 'n_match': len(matches),
            'n_missed': len(missed), 'n_false_pos': n_false_pos,
            'recall': round(recall, 3) if recall is not None else None,
            'precision': round(precision, 3) if precision is not None else None,
            'median_dist_mm': round(med_dist, 1) if med_dist else None,
        }
        rec_str = f"R={recall:.0%}" if recall is not None else "R=—"
        prec_str = f"P={precision:.0%}" if precision is not None else "P=—"
        med_str = f"Δ={med_dist:.1f}mm" if med_dist else "Δ=—"
        print(f"  {algo_name:14s}: {len(detections):3d} Det, {len(matches):2d} matched ({rec_str} {prec_str}) FP={n_false_pos:3d}  {med_str}")
    results.append(scheibe_result)

# Speichern
json.dump(results, open(ROOT / 'benchmark_results.json', 'w'), indent=2, default=str)

# === Aggregate ===
print(f"\n{'='*100}")
print("GESAMTBILANZ")
print('='*100)
print(f"\n{'Algo':16s} {'Total-GT':>9s} {'Matched':>8s} {'Recall':>8s} {'Precision':>10s} {'Med-Δ':>9s} {'FP/Bild':>8s} {'LEER-FP':>9s}")
agg = defaultdict(lambda: {'gt':0,'match':0,'fp':0,'med':[],'leer_fp':0,'leer_count':0})
for r in results:
    is_leer = r['disziplin'] == 'leer'
    for algo, a in r['algorithms'].items():
        if 'error' in a: continue
        if not is_leer:
            agg[algo]['gt'] += r['gt_count']
            agg[algo]['match'] += a['n_match']
            agg[algo]['fp'] += a['n_false_pos']
            if a['median_dist_mm']: agg[algo]['med'].append(a['median_dist_mm'])
        else:
            agg[algo]['leer_fp'] += a['n_false_pos']
            agg[algo]['leer_count'] += 1
n_pos_imgs = sum(1 for r in results if r['disziplin']!='leer')
for algo, a in agg.items():
    recall = a['match'] / max(1, a['gt'])
    precision = a['match'] / max(1, a['match'] + a['fp'])
    med = np.median(a['med']) if a['med'] else 0
    fp_per_img = a['fp'] / max(1, n_pos_imgs)
    leer_fp_avg = a['leer_fp'] / max(1, a['leer_count'])
    print(f"{algo:16s} {a['gt']:>9d} {a['match']:>8d}  {recall:>6.0%}  {precision:>8.0%}  {med:>6.1f}mm {fp_per_img:>7.1f}  {leer_fp_avg:>8.1f}")

# Aufschlüsselung nach Schwierigkeit
print(f"\nNach Schwierigkeit (Recall pro Algorithmus):")
DIFFICULTY = {1:'easy',2:'easy',3:'easy',4:'easy',7:'easy',8:'easy',11:'easy',  # 7 easy: 3-5 Treffer
              6:'med',10:'med',12:'med',                                       # 3 med: 15+
              5:'hard',9:'hard',                                                # 2 hard: 25+
              13:'leer'}
diff_agg = defaultdict(lambda: defaultdict(lambda: {'gt':0,'match':0,'fp':0,'count':0}))
for r in results:
    diff = DIFFICULTY.get(r['idx'], '?')
    for algo, a in r['algorithms'].items():
        if 'error' in a: continue
        diff_agg[diff][algo]['gt'] += r['gt_count']
        diff_agg[diff][algo]['match'] += a['n_match']
        diff_agg[diff][algo]['fp'] += a['n_false_pos']
        diff_agg[diff][algo]['count'] += 1

print(f"\n{'Schwierigkeit':14s} {'#Bilder':>8s}  " + "  ".join(f"{a:>14s}" for a in ALGORITHMS.keys()))
for diff in ['easy','med','hard','leer']:
    if diff not in diff_agg: continue
    row = f"{diff:14s} {diff_agg[diff][list(ALGORITHMS.keys())[0]]['count']:>8d}"
    for algo in ALGORITHMS.keys():
        a = diff_agg[diff][algo]
        if diff == 'leer':
            avg_fp = a['fp'] / max(1, a['count'])
            row += f"  FP/Bild={avg_fp:>5.1f}"
        else:
            r = a['match'] / max(1, a['gt'])
            row += f"  R={r:>4.0%} FP={a['fp']:>3d}"
    print(row)

print(f"\nGespeichert: benchmark_results.json")
