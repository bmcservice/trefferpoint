#!/usr/bin/env python3
"""Validierung v2.4.0 über beide Datensätze.

  A) Foto-Modus:   13 Trainings-Fotos (12 MP), photo_detect()
  B) Stream-Modus: 2026-05-31 Detframes (1080p), stream_detect()
"""
import json, re
from pathlib import Path
import numpy as np
import cv2
import sys
sys.path.insert(0, str(Path(__file__).parent))
import detection_v240 as det

ROOT = Path(__file__).parent
STREAM_DIR = ROOT.parent / "2026-05-31_v184_test"

def match(dets, gt, sp, mx=15):
    cx,cy = sp['cx'],sp['cy']; ppm = sp['px_per_mm']
    pairs = []
    for di,d in enumerate(dets):
        for gi,g in enumerate(gt):
            pairs.append((np.hypot((d['x']-cx)/ppm-g['x_mm'],(d['y']-cy)/ppm-g['y_mm']),di,gi))
    pairs.sort(); ud,ug=set(),set(); ms=[]
    for dist,di,gi in pairs:
        if dist>mx: break
        if di in ud or gi in ug: continue
        ud.add(di); ug.add(gi); ms.append(dist)
    return len(ms), len(dets)-len(ud), ms

# ============================================================================
# A) FOTO-MODUS
# ============================================================================
print("="*90)
print("A) FOTO-MODUS — 13 Trainings-Fotos (12 MP), photo_detect()")
print("="*90)
gt_photo = json.load(open(ROOT/'ground_truth_multi.json', encoding='utf-8'))
foto_agg = {'gt':0,'match':0,'fp':0,'dists':[],'leer_fp':0}
print(f"{'Bild':>5} {'Disz':>6} {'GT':>3} {'Det':>4} {'Mat':>4} {'FP':>4} {'Recall':>7} {'Δmm':>6}")
for s in gt_photo['scheiben']:
    img = cv2.imread(str(ROOT/s['file']))
    disz = 'kk25' if s['disziplin']=='leer' else s['disziplin']
    sp = s['spiegel']  # nutze GT-Spiegel (sauber detektiert)
    dets, _ = det.photo_detect(img, disz, sp=sp, thr=0.55, nms_factor=1.2)
    m, fp, ds = match(dets, s['treffer'], sp)
    is_leer = s['disziplin']=='leer'
    if is_leer:
        foto_agg['leer_fp'] += len(dets)
        rec='—'
    else:
        foto_agg['gt'] += len(s['treffer']); foto_agg['match'] += m; foto_agg['fp'] += fp
        foto_agg['dists'].extend(ds)
        rec = f"{m/max(1,len(s['treffer'])):.0%}"
    med = np.median(ds) if ds else 0
    print(f"  {s['idx']:>3} {s['disziplin']:>6} {len(s['treffer']):>3} {len(dets):>4} {m:>4} {fp:>4} {rec:>7} {med:>5.1f}")

# ============================================================================
# B) STREAM-MODUS
# ============================================================================
print("\n"+"="*90)
print("B) STREAM-MODUS — 2026-05-31 Detframes (1080p), stream_detect() Frame-Diff+Reg")
print("="*90)
frames = sorted((STREAM_DIR/'detframes').glob('df_*.jpg'))
sess = json.load(open(STREAM_DIR/'sess_late_v184.json', encoding='utf-8'))
start = sess['session']['start_ms']
eps = np.array([int(re.search(r'df_(\d+)',f.name).group(1)) for f in frames])
ts = (eps-start)/1000

GT_1A=[{'x_mm':33,'y_mm':-76},{'x_mm':25,'y_mm':-46},{'x_mm':45,'y_mm':-6},{'x_mm':-30,'y_mm':10},{'x_mm':135.5,'y_mm':-54.7}]
GT_1B=[{'x_mm':-74.9,'y_mm':-7.6},{'x_mm':28.6,'y_mm':-38.7},{'x_mm':26.1,'y_mm':20.2},{'x_mm':58.1,'y_mm':25.3},{'x_mm':5.9,'y_mm':69.0}]

def median_frame(t_center, window_s=8):
    idxs = np.where(np.abs(ts-t_center)<window_s)[0]
    if len(idxs)==0: idxs=[int(np.argmin(np.abs(ts-t_center)))]
    return np.median([cv2.cvtColor(cv2.imread(str(frames[i])),cv2.COLOR_BGR2GRAY).astype(np.float32) for i in idxs[:15]],axis=0)

def find_sp_roi(gray):
    g2=gray.copy().astype(np.uint8); g2[:,1300:]=255
    return det.find_spiegel(g2, expected_r_range=(150,350))

stream_agg = {'gt':0,'match':0,'fp':0,'dists':[]}
print(f"{'Phase':>14} {'Shift':>14} {'GT':>3} {'Det':>4} {'Mat':>4} {'FP':>4} {'Recall':>7} {'Δmm':>6}")
for name,t_ref,t_post,gt in [('1a Test',76,185,GT_1A),('1b Hauptlauf',608,793,GT_1B)]:
    ref = median_frame(t_ref); post = median_frame(t_post)
    sp = find_sp_roi(post)
    dets, _, shift = det.stream_detect(ref, post, 'kk25', sp=sp, thr=0.60, nms_factor=1.5)
    m, fp, ds = match(dets, gt, sp)
    stream_agg['gt'] += len(gt); stream_agg['match'] += m; stream_agg['fp'] += fp
    stream_agg['dists'].extend(ds)
    med = np.median(ds) if ds else 0
    print(f"  {name:>12} dx={shift[0]:+.1f} dy={shift[1]:+.1f} {len(gt):>3} {len(dets):>4} {m:>4} {fp:>4} {m/len(gt):>6.0%} {med:>5.1f}")

# ============================================================================
# GESAMTBILANZ
# ============================================================================
print("\n"+"="*90)
print("GESAMTBILANZ v2.4.0")
print("="*90)
fr = foto_agg['match']/max(1,foto_agg['gt'])
fp_prec = foto_agg['match']/max(1,foto_agg['match']+foto_agg['fp'])
print(f"\nFOTO-MODUS  (12 MP, statisch):")
print(f"  Recall:    {fr:.0%}  ({foto_agg['match']}/{foto_agg['gt']})")
print(f"  Precision: {fp_prec:.0%}")
print(f"  Median-Δ:  {np.median(foto_agg['dists']):.1f} mm")
print(f"  FP/Bild:   {foto_agg['fp']/12:.1f}")
print(f"  LEER-FP:   {foto_agg['leer_fp']}")

sr = stream_agg['match']/max(1,stream_agg['gt'])
sp_prec = stream_agg['match']/max(1,stream_agg['match']+stream_agg['fp'])
print(f"\nSTREAM-MODUS (1080p, Frame-Diff+Reg):")
print(f"  Recall:    {sr:.0%}  ({stream_agg['match']}/{stream_agg['gt']})")
print(f"  Precision: {sp_prec:.0%}")
print(f"  Median-Δ:  {np.median(stream_agg['dists']):.1f} mm")
print(f"  FP/Serie:  {stream_agg['fp']/2:.1f}")

print(f"\nVERGLEICH zur App heute (app_replica): Recall 20%, Precision 12%, 15 FP/Bild")
