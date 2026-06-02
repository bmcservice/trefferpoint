#!/usr/bin/env python3
"""Negativ-Kontroll-Test: v2.4.0 auf statischer USB-Session (NULL Schüsse).
Ground Truth = 0 Treffer. Die alte App machte 4 Phantome. Wie viele macht v2.4.0?
"""
import sys, glob, re
from pathlib import Path
import numpy as np, cv2
sys.path.insert(0, str(Path(__file__).parent.parent / "training_data"))
import detection_v240 as det

ROOT = Path(__file__).parent / "run2_frames"
frames = sorted(ROOT.glob("df_*.jpg"), key=lambda f: int(re.search(r'df_(\d+)', f.name).group(1)))
eps = [int(re.search(r'df_(\d+)', f.name).group(1)) for f in frames]
t0 = eps[0]
ts = [(e - t0)/1000 for e in eps]
print(f"{len(frames)} Frames über {ts[-1]:.0f}s (statische Szene, 0 echte Treffer)")

def load(f): return cv2.cvtColor(cv2.imread(str(f)), cv2.COLOR_BGR2GRAY).astype(np.float32)

# Spiegel im 1920-Frame finden (letzter, stabilster)
def find_sp(gray):
    g=gray.astype(np.uint8)
    _,m=cv2.threshold(g,80,255,cv2.THRESH_BINARY_INV)
    m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,np.ones((11,11),np.uint8),iterations=3)
    cs,_=cv2.findContours(m,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_NONE)
    best=None
    for c in cs:
        a=cv2.contourArea(c)
        if a<30000 or len(c)<5: continue
        (cx,cy),(MA,ma),ang=cv2.fitEllipse(c)
        if best is None or a>best[0]: best=(a,cx,cy,MA,ma)
    a,cx,cy,MA,ma=best
    r=int((MA+ma)/4)
    return {'cx':int(cx),'cy':int(cy),'r':r,'px_per_mm':r/100.0}

sp = find_sp(load(frames[-1]))
print(f"Spiegel (CV, 1920): ({sp['cx']},{sp['cy']}) r={sp['r']} px/mm={sp['px_per_mm']:.2f}")

def median_window(idx_lo, idx_hi):
    return np.median([load(frames[i]) for i in range(idx_lo, min(idx_hi, len(frames)))], axis=0)

# Verschiedene ref/post-Paare über die Session — testet ob IRGENDWO Phantome entstehen
N = len(frames)
print(f"\n{'ref-Fenster':>14} {'post-Fenster':>14} {'shift':>10} {'Phantome':>9}")
configs = [
    ("Start(0-10)", "Ende(-10)", (0,10), (N-10,N)),
    ("früh(20-30)", "spät(160-170)", (20,30), (160,170)),
    ("mitte(80-90)", "Ende(-10)", (80,90), (N-10,N)),
    ("stabil(100-110)", "stabil(150-160)", (100,110), (150,160)),
]
total_phantoms = []
for name_r, name_p, (rl,rh), (pl,ph) in configs:
    ref = median_window(rl,rh); post = median_window(pl,ph)
    dets, _, shift = det.stream_detect(ref, post, 'kk25', sp=sp, thr=0.60, nms_factor=1.5)
    print(f"{name_r:>14} {name_p:>14}  dx={shift[0]:+.0f} dy={shift[1]:+.0f}  {len(dets):>6} Phantome")
    total_phantoms.append(len(dets))

print(f"\n=== ERGEBNIS Negativ-Kontrolle (statische Szene, GT=0 Treffer) ===")
print(f"  Alte App-Detection:  4 Phantome")
print(f"  v2.4.0-Pipeline:     {min(total_phantoms)}-{max(total_phantoms)} Phantome (über 4 ref/post-Konfigs)")
print(f"  → v2.4.0 {'SAUBERER' if max(total_phantoms)<4 else 'NICHT besser'} auf statischer Szene")
