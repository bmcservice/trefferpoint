#!/usr/bin/env python3
"""Finale Visualisierung v2.4.0: Foto-Modus + Stream-Modus annotiert."""
import json, re
from pathlib import Path
import numpy as np
import cv2
from PIL import Image, ImageDraw
import sys
sys.path.insert(0, str(Path(__file__).parent))
import detection_v240 as det

ROOT = Path(__file__).parent
STREAM_DIR = ROOT.parent / "2026-05-31_v184_test"

def match(dets, gt, sp, mx=15):
    cx,cy=sp['cx'],sp['cy']; ppm=sp['px_per_mm']; pairs=[]
    for di,d in enumerate(dets):
        for gi,g in enumerate(gt):
            pairs.append((np.hypot((d['x']-cx)/ppm-g['x_mm'],(d['y']-cy)/ppm-g['y_mm']),di,gi))
    pairs.sort(); ud,ug=set(),set(); mm={}
    for dist,di,gi in pairs:
        if dist>mx: break
        if di in ud or gi in ug: continue
        ud.add(di);ug.add(gi); mm[di]=gi
    return ud, ug, mm

def annotate(img_pil, dets, gt, sp, used_det, used_gt, title):
    d = ImageDraw.Draw(img_pil, 'RGBA')
    cx,cy,r=sp['cx'],sp['cy'],sp['r']; ppm=sp['px_per_mm']
    d.ellipse([cx-r,cy-r,cx+r,cy+r], outline=(255,180,40,180), width=3)
    # GT
    for gi,g in enumerate(gt):
        gx=cx+g['x_mm']*ppm; gy=cy+g['y_mm']*ppm
        if gi in used_gt:
            d.ellipse([gx-22,gy-22,gx+22,gy+22], outline=(255,220,60,255), width=4)
        else:
            d.line([gx-16,gy-16,gx+16,gy+16], fill=(80,140,255,255), width=5)
            d.line([gx-16,gy+16,gx+16,gy-16], fill=(80,140,255,255), width=5)
    # Detections
    for di,dd in enumerate(dets):
        col=(60,255,60,255) if di in used_det else (255,60,60,230)
        s=13 if di in used_det else 9
        d.rectangle([dd['x']-s,dd['y']-s,dd['x']+s,dd['y']+s], outline=col, width=3)
    d.rectangle([15,15,760,70], fill=(0,0,0,200))
    d.text((22,22), title, fill=(255,255,255))
    d.text((22,44), "Gelb=GT-getroffen  BlauX=verpasst  Gruen=korrekt  Rot=FP", fill=(200,200,200))
    return img_pil

# === FOTO-MODUS: 3 repräsentative Bilder ===
gt_photo = json.load(open(ROOT/'ground_truth_multi.json', encoding='utf-8'))
foto_show = [4, 6, 9]  # easy KK, med .357, hard KK
tiles=[]
for sidx in foto_show:
    s = next(x for x in gt_photo['scheiben'] if x['idx']==sidx)
    img = cv2.imread(str(ROOT/s['file']))
    disz='kk25' if s['disziplin']=='leer' else s['disziplin']
    dets,_=det.photo_detect(img, disz, sp=s['spiegel'], thr=0.55, nms_factor=1.2)
    ud,ug,_=match(dets, s['treffer'], s['spiegel'])
    pil=Image.open(ROOT/s['file']).convert('RGB')
    pil=annotate(pil, dets, s['treffer'], s['spiegel'], ud, ug,
                 f"FOTO bild_{sidx} ({s['disziplin']}): {len(ug)}/{len(s['treffer'])} Treffer, {len(dets)-len(ud)} FP")
    sc=700/pil.width; pil=pil.resize((int(pil.width*sc),int(pil.height*sc)))
    tiles.append(pil)
tw,th=tiles[0].size
grid=Image.new('RGB',(tw*len(tiles)+8,th),(40,40,40))
for i,t in enumerate(tiles): grid.paste(t,(i*(tw+4),0))
grid.save(ROOT/'v240_foto_demo.jpg', quality=84)
print("v240_foto_demo.jpg")

# === STREAM-MODUS: beide Phasen ===
frames=sorted((STREAM_DIR/'detframes').glob('df_*.jpg'))
sess=json.load(open(STREAM_DIR/'sess_late_v184.json',encoding='utf-8'))
start=sess['session']['start_ms']
ts=np.array([(int(re.search(r'df_(\d+)',f.name).group(1))-start)/1000 for f in frames])
def medf(tc,w=8):
    idxs=np.where(np.abs(ts-tc)<w)[0]
    if len(idxs)==0: idxs=[int(np.argmin(np.abs(ts-tc)))]
    return np.median([cv2.cvtColor(cv2.imread(str(frames[i])),cv2.COLOR_BGR2GRAY).astype(np.float32) for i in idxs[:15]],axis=0)
def find_sp_roi(g):
    g2=g.copy().astype(np.uint8); g2[:,1300:]=255
    return det.find_spiegel(g2,(150,350))
GT_1A=[{'x_mm':33,'y_mm':-76},{'x_mm':25,'y_mm':-46},{'x_mm':45,'y_mm':-6},{'x_mm':-30,'y_mm':10},{'x_mm':135.5,'y_mm':-54.7}]
GT_1B=[{'x_mm':-74.9,'y_mm':-7.6},{'x_mm':28.6,'y_mm':-38.7},{'x_mm':26.1,'y_mm':20.2},{'x_mm':58.1,'y_mm':25.3},{'x_mm':5.9,'y_mm':69.0}]
stiles=[]
for name,tr,tp,gt in [('1a Test',76,185,GT_1A),('1b Hauptlauf',608,793,GT_1B)]:
    ref=medf(tr); post=medf(tp); sp=find_sp_roi(post)
    dets,_,shift=det.stream_detect(ref,post,'kk25',sp=sp,thr=0.60,nms_factor=1.5)
    ud,ug,_=match(dets,gt,sp)
    # Post-Frame als Hintergrund
    pil=Image.fromarray(np.clip(post,0,255).astype(np.uint8)).convert('RGB')
    pil=annotate(pil,dets,gt,sp,ud,ug,
                 f"STREAM {name} (1080p): {len(ug)}/{len(gt)} Treffer, {len(dets)-len(ud)} FP, shift dy={shift[1]:+.1f}px")
    sc=900/pil.width; pil=pil.resize((int(pil.width*sc),int(pil.height*sc)))
    stiles.append(pil)
tw,th=stiles[0].size
grid2=Image.new('RGB',(tw,th*2+4),(40,40,40))
for i,t in enumerate(stiles): grid2.paste(t,(0,i*(th+4)))
grid2.save(ROOT/'v240_stream_demo.jpg', quality=84)
print("v240_stream_demo.jpg")
