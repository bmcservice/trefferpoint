#!/usr/bin/env python3
"""Algorithmus-Benchmark für Loch-Detection auf Standbildern.

Pipeline pro Foto:
  1. Spiegel finden (Skala → pxPerMm)
  2. Detection-Algorithmus laufen
  3. Vergleich gegen Ground-Truth (.gt.json daneben)
  4. Metriken: Recall, Precision, Position-Genauigkeit

Algorithmen:
  - app_replica  : Was die TrefferPoint-App heute macht
  - hough        : OpenCV HoughCircles
  - dog_blob     : Difference-of-Gaussians + ScaleSpace
  - template     : Template-Matching mit synthetischer Loch-Form
"""
import json
import re
import sys
from pathlib import Path
from collections import defaultdict
import numpy as np
import cv2
from PIL import Image

ROOT = Path(__file__).parent

# ============================================================================
# Schritt 1: Spiegel-Detection (gemeinsam für alle Algorithmen)
# ============================================================================
def find_spiegel(gray):
    """Größter dunkler runder Bereich = Spiegel. Liefert (cx, cy, r_px, px_per_mm)."""
    H, W = gray.shape
    # Bilateraler Filter glättet ohne Kanten zu verwischen
    smooth = cv2.bilateralFilter(gray, 9, 75, 75)
    # Threshold um 60 (Spiegel-Schwarz)
    _, mask = cv2.threshold(smooth, 70, 255, cv2.THRESH_BINARY_INV)
    kernel = np.ones((9,9), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=3)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours: return None
    # Nehme nur Kontur die ungefähr rund ist (Kreisförmigkeits-Test)
    candidates = []
    for c in contours:
        area = cv2.contourArea(c)
        if area < 5000: continue
        perim = cv2.arcLength(c, True)
        if perim == 0: continue
        roundness = 4 * np.pi * area / (perim ** 2)  # 1.0 = perfekt rund
        (cx, cy), r = cv2.minEnclosingCircle(c)
        candidates.append({'roundness': roundness, 'area': area, 'cx': cx, 'cy': cy, 'r': r})
    if not candidates: return None
    # Wähle die runde Kontur mit größtem Bereich
    candidates.sort(key=lambda x: -x['area'] * x['roundness'])
    best = candidates[0]
    px_per_mm = best['r'] / 100.0  # Spiegel-Radius = 100 mm
    return {'cx': int(best['cx']), 'cy': int(best['cy']), 'r': int(best['r']),
            'px_per_mm': px_per_mm, 'roundness': best['roundness']}

# ============================================================================
# Algorithmus 1: App-Replica (Pixel-Threshold + Connected Component)
# ============================================================================
def detect_app_replica(img_bgr, spiegel, disziplin='kk25'):
    """Was die App heute tut: Frame-Diff fehlt (kein Pre-Frame), also reine
    Helligkeitsanalyse: Im schwarzen Spiegel helle Pixel = Loch."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    H, W = gray.shape
    sp_cx, sp_cy, sp_r = spiegel['cx'], spiegel['cy'], spiegel['r']
    px_per_mm = spiegel['px_per_mm']
    kaliber_mm = 5.6 if disziplin == 'kk25' else 9.0

    yy, xx = np.ogrid[:H, :W]
    dist = np.hypot(xx - sp_cx, yy - sp_cy)
    in_spiegel = dist < sp_r * 0.95

    # Threshold: Spiegel-Median + 35 (wie in TrefferPoint-Code 3448)
    spiegel_med = int(np.median(gray[in_spiegel]))
    bright = (gray > spiegel_med + 35) & in_spiegel

    # Außerhalb: dunkler als Median - 60
    outside_ring = (dist > sp_r * 1.05) & (dist < sp_r * 2.5)
    out_med = int(np.median(gray[outside_ring]))
    dark = (gray < out_med - 60) & outside_ring

    combined = (bright | dark).astype(np.uint8) * 255

    # Connected Components
    n, labels, stats, cents = cv2.connectedComponentsWithStats(combined, 8)
    detections = []
    min_area = int((kaliber_mm * 0.5 * px_per_mm) ** 2 * np.pi)  # halbes Kaliber Mindest-Größe
    max_area = int((kaliber_mm * 2.5 * px_per_mm) ** 2 * np.pi)  # 2.5× Kaliber max
    for k in range(1, n):
        x, y, w, h, area = stats[k]
        if area < min_area or area > max_area: continue
        if max(w, h) / max(1, min(w, h)) > 3.0: continue
        cx, cy = cents[k]
        detections.append({'x': int(cx), 'y': int(cy), 'size': int(area)})
    return detections

# ============================================================================
# Algorithmus 2: HoughCircles
# ============================================================================
def detect_hough(img_bgr, spiegel, disziplin='kk25'):
    """OpenCV HoughCircles tuned auf Loch-Größe."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    px_per_mm = spiegel['px_per_mm']
    kaliber_mm = 5.6 if disziplin == 'kk25' else 9.0
    min_r = int(kaliber_mm * 0.4 * px_per_mm)
    max_r = int(kaliber_mm * 1.5 * px_per_mm)

    # Suche im Spiegel (helle Punkte) und außerhalb (dunkle) getrennt
    sp_cx, sp_cy, sp_r = spiegel['cx'], spiegel['cy'], spiegel['r']
    H, W = gray.shape
    yy, xx = np.ogrid[:H, :W]
    dist = np.hypot(xx - sp_cx, yy - sp_cy)

    detections = []

    # Im Spiegel: HELLE Kreise — Bild invertieren um die als „dunkel" zu sehen
    spiegel_mask = (dist < sp_r * 0.95).astype(np.uint8) * 255
    inv = 255 - gray
    inv_masked = cv2.bitwise_and(inv, inv, mask=spiegel_mask)
    circles = cv2.HoughCircles(
        inv_masked, cv2.HOUGH_GRADIENT, dp=1, minDist=int(2.5 * kaliber_mm * px_per_mm),
        param1=120, param2=20, minRadius=min_r, maxRadius=max_r)
    if circles is not None:
        for x, y, r in circles[0]:
            detections.append({'x': int(x), 'y': int(y), 'size': int(np.pi * r * r), 'kind': 'in_spiegel'})

    # Außerhalb: DUNKLE Kreise
    out_mask = ((dist > sp_r * 1.05) & (dist < sp_r * 2.5)).astype(np.uint8) * 255
    gray_masked = cv2.bitwise_and(gray, gray, mask=out_mask)
    circles = cv2.HoughCircles(
        gray_masked, cv2.HOUGH_GRADIENT, dp=1, minDist=int(2.5 * kaliber_mm * px_per_mm),
        param1=120, param2=20, minRadius=min_r, maxRadius=max_r)
    if circles is not None:
        for x, y, r in circles[0]:
            detections.append({'x': int(x), 'y': int(y), 'size': int(np.pi * r * r), 'kind': 'outside'})

    return detections

# ============================================================================
# Algorithmus 3: DoG Blob Detection
# ============================================================================
def detect_dog_blob(img_bgr, spiegel, disziplin='kk25'):
    """Difference-of-Gaussians: detektiert Blobs spezifischer Größe."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    px_per_mm = spiegel['px_per_mm']
    kaliber_mm = 5.6 if disziplin == 'kk25' else 9.0
    sigma_target = kaliber_mm * 0.5 * px_per_mm

    # DoG: Gauss bei zwei Skalen subtrahieren
    g1 = cv2.GaussianBlur(gray, (0,0), sigma_target * 0.7)
    g2 = cv2.GaussianBlur(gray, (0,0), sigma_target * 1.4)
    dog = g1 - g2  # positive Antworten = helle Punkte auf dunklem Hintergrund

    sp_cx, sp_cy, sp_r = spiegel['cx'], spiegel['cy'], spiegel['r']
    H, W = gray.shape
    yy, xx = np.ogrid[:H, :W]
    dist = np.hypot(xx - sp_cx, yy - sp_cy)
    in_spiegel = dist < sp_r * 0.95
    outside_ring = (dist > sp_r * 1.05) & (dist < sp_r * 2.5)

    detections = []
    # Im Spiegel: positive DoG-Maxima
    masked_in = dog.copy(); masked_in[~in_spiegel] = -1e9
    bright_thresh = np.percentile(dog[in_spiegel], 99.7)
    peaks_in = (masked_in > bright_thresh)
    n, labels, stats, cents = cv2.connectedComponentsWithStats(peaks_in.astype(np.uint8), 8)
    for k in range(1, n):
        x, y, w, h, area = stats[k]
        if area < 5: continue
        detections.append({'x': int(cents[k][0]), 'y': int(cents[k][1]), 'size': int(area), 'kind': 'in_spiegel'})

    # Außerhalb: NEGATIVE DoG-Maxima (Inverse für dunkle Punkte)
    inv_dog = -dog
    masked_out = inv_dog.copy(); masked_out[~outside_ring] = -1e9
    dark_thresh = np.percentile(inv_dog[outside_ring], 99.8)
    peaks_out = (masked_out > dark_thresh)
    n, labels, stats, cents = cv2.connectedComponentsWithStats(peaks_out.astype(np.uint8), 8)
    for k in range(1, n):
        x, y, w, h, area = stats[k]
        if area < 5: continue
        detections.append({'x': int(cents[k][0]), 'y': int(cents[k][1]), 'size': int(area), 'kind': 'outside'})

    return detections

# ============================================================================
# Algorithmus 4: Template Matching mit synthetischem Loch
# ============================================================================
def detect_template(img_bgr, spiegel, disziplin='kk25'):
    """Erzeuge synthetische Loch-Templates für beide Zonen, matche per NCC."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    px_per_mm = spiegel['px_per_mm']
    kaliber_mm = 5.6 if disziplin == 'kk25' else 9.0
    r_px = kaliber_mm * 0.5 * px_per_mm

    # Template 1: helles Loch im Schwarzen (für Spiegel-Bereich)
    tsize = int(r_px * 4) | 1  # ungerade
    tmpl_bright = np.zeros((tsize, tsize), dtype=np.float32) + 30  # Hintergrund schwarz
    cv2.circle(tmpl_bright, (tsize//2, tsize//2), int(r_px), 200, -1)
    tmpl_bright = cv2.GaussianBlur(tmpl_bright, (3,3), 1)

    # Template 2: dunkles Loch im Weißen (für Außenbereich)
    tmpl_dark = np.zeros((tsize, tsize), dtype=np.float32) + 200
    cv2.circle(tmpl_dark, (tsize//2, tsize//2), int(r_px), 50, -1)
    tmpl_dark = cv2.GaussianBlur(tmpl_dark, (3,3), 1)

    sp_cx, sp_cy, sp_r = spiegel['cx'], spiegel['cy'], spiegel['r']
    H, W = gray.shape

    detections = []
    for tmpl, kind_filter in [(tmpl_bright, 'in_spiegel'), (tmpl_dark, 'outside')]:
        result = cv2.matchTemplate(gray.astype(np.float32), tmpl, cv2.TM_CCOEFF_NORMED)
        # Schwellwert für Match
        loc = np.where(result > 0.55)
        for pt in zip(*loc[::-1]):
            cx_t, cy_t = pt[0] + tsize//2, pt[1] + tsize//2
            d = np.hypot(cx_t - sp_cx, cy_t - sp_cy)
            if kind_filter == 'in_spiegel' and not (d < sp_r * 0.95): continue
            if kind_filter == 'outside' and not (sp_r * 1.05 < d < sp_r * 2.5): continue
            detections.append({'x': cx_t, 'y': cy_t, 'size': int(tsize*tsize), 'kind': kind_filter, 'score': float(result[pt[1], pt[0]])})

    # Non-Maximum-Suppression: nahe Detections zusammenfassen
    nms = []
    used = [False] * len(detections)
    for i, d in enumerate(sorted(detections, key=lambda x: -x.get('score', 0))):
        if used[i]: continue
        nms.append(d)
        for j, dj in enumerate(detections):
            if used[j] or i == j: continue
            if np.hypot(d['x'] - dj['x'], d['y'] - dj['y']) < r_px * 1.5:
                used[j] = True
    return nms

# ============================================================================
# Benchmark-Schleife
# ============================================================================
ALGORITHMS = {
    'app_replica': detect_app_replica,
    'hough': detect_hough,
    'dog_blob': detect_dog_blob,
    'template': detect_template,
}

def match_gt(detections, gt_treffer, spiegel, max_dist_mm=15):
    """Greedy: matche jede Detection mit nächstem GT-Punkt."""
    cx, cy = spiegel['cx'], spiegel['cy']
    px_per_mm = spiegel['px_per_mm']
    matches = []
    used_gt = set()
    used_det = set()
    pairs = []
    for di, d in enumerate(detections):
        dx_mm = (d['x'] - cx) / px_per_mm
        dy_mm = (d['y'] - cy) / px_per_mm
        for gi, g in enumerate(gt_treffer):
            dist = np.hypot(dx_mm - g['x_mm'], dy_mm - g['y_mm'])
            pairs.append((dist, di, gi))
    pairs.sort()
    for dist, di, gi in pairs:
        if di in used_det or gi in used_gt: continue
        if dist > max_dist_mm: break  # alles weitere ist zu weit
        used_det.add(di); used_gt.add(gi)
        matches.append({'dist_mm': dist, 'det_idx': di, 'gt_idx': gi})
    return {
        'matches': matches,
        'unmatched_det': [d for di, d in enumerate(detections) if di not in used_det],
        'unmatched_gt':  [g for gi, g in enumerate(gt_treffer) if gi not in used_gt],
    }

def benchmark_image(img_path, disziplin):
    """Pro Foto alle Algorithmen laufen und vergleichen."""
    gt_path = img_path.with_suffix('.gt.json')
    if not gt_path.exists():
        gt_path = img_path.parent / (img_path.stem + '.gt.json')
    if not gt_path.exists():
        print(f"  → KEIN GT für {img_path.name} (erwartet: {gt_path.name})")
        return None
    gt = json.load(open(gt_path, encoding='utf-8'))
    # GT-Treffer extrahieren — flexibles Format
    gt_treffer = []
    if 'serien' in gt:
        for s in gt['serien'].values():
            gt_treffer.extend(s.get('treffer', []))
    elif 'treffer' in gt:
        gt_treffer = gt['treffer']

    img_bgr = cv2.imread(str(img_path))
    if img_bgr is None:
        print(f"  → Bild nicht lesbar: {img_path}")
        return None
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    spiegel = find_spiegel(gray)
    if not spiegel:
        print(f"  → Spiegel nicht gefunden in {img_path.name}")
        return None
    print(f"\n=== {img_path.name} ({disziplin}) ===")
    print(f"  Spiegel: ({spiegel['cx']},{spiegel['cy']}) r={spiegel['r']} px/mm={spiegel['px_per_mm']:.2f}  GT-Treffer: {len(gt_treffer)}")

    results = {}
    for algo_name, algo_fn in ALGORITHMS.items():
        detections = algo_fn(img_bgr, spiegel, disziplin)
        m = match_gt(detections, gt_treffer, spiegel)
        recall = len(m['matches']) / max(1, len(gt_treffer))
        precision = len(m['matches']) / max(1, len(detections))
        median_dist = np.median([x['dist_mm'] for x in m['matches']]) if m['matches'] else None
        results[algo_name] = {
            'n_detections': len(detections),
            'n_matched': len(m['matches']),
            'n_missed': len(m['unmatched_gt']),
            'n_false_pos': len(m['unmatched_det']),
            'recall': round(recall, 2),
            'precision': round(precision, 2),
            'median_dist_mm': round(median_dist, 1) if median_dist else None,
        }
        print(f"  {algo_name:14s}: Recall={recall:.0%} Precision={precision:.0%}  "
              f"({len(m['matches'])}/{len(gt_treffer)} matched, {len(m['unmatched_det'])} false-pos)  "
              f"Median-Δ={median_dist:.1f}mm" if median_dist else f"  {algo_name:14s}: kein Match")
    return {'image': img_path.name, 'disziplin': disziplin, 'gt_count': len(gt_treffer),
            'spiegel': spiegel, 'algorithms': results}

if __name__ == '__main__':
    images = sorted(ROOT.glob('*.jpg')) + sorted(ROOT.glob('*.jpeg')) + sorted(ROOT.glob('*.png'))
    images = [p for p in images if not p.stem.endswith('_annot')]
    if not images:
        print("Noch keine Fotos im Ordner. Bitte Scheiben-Fotos als kk25_xx.jpg / gk25_xx.jpg ablegen.")
        sys.exit(0)
    print(f"Gefunden: {len(images)} Fotos")
    all_results = []
    for img_path in images:
        disziplin = 'gk25' if img_path.stem.startswith('gk') else 'kk25'
        r = benchmark_image(img_path, disziplin)
        if r: all_results.append(r)

    # Sammel-Statistik
    print("\n\n" + "="*70)
    print("GESAMTBILANZ pro Algorithmus")
    print("="*70)
    agg = defaultdict(lambda: {'total_gt':0, 'total_matched':0, 'total_fp':0, 'dists':[]})
    for r in all_results:
        for algo, a in r['algorithms'].items():
            agg[algo]['total_gt'] += r['gt_count']
            agg[algo]['total_matched'] += a['n_matched']
            agg[algo]['total_fp'] += a['n_false_pos']
            if a['median_dist_mm']: agg[algo]['dists'].append(a['median_dist_mm'])
    print(f"{'Algo':15s} {'Recall':>8s} {'Precision':>10s} {'Median-Δ':>10s} {'FP/Bild':>9s}")
    for algo, a in agg.items():
        r = a['total_matched'] / max(1, a['total_gt'])
        p = a['total_matched'] / max(1, a['total_matched'] + a['total_fp'])
        d = np.median(a['dists']) if a['dists'] else 0
        fpr = a['total_fp'] / len(all_results)
        print(f"{algo:15s} {r:>7.0%}  {p:>9.0%}  {d:>8.1f}mm  {fpr:>8.1f}")

    json.dump(all_results, open(ROOT/'benchmark_results.json', 'w'), indent=2, default=str)
    print(f"\nGesamtergebnis in benchmark_results.json")
