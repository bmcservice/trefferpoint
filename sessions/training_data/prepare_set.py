#!/usr/bin/env python3
"""
1. HEIC → JPG konvertieren
2. Spiegel-Detection auf jedem Bild
3. Auto-Loch-Detection (Pre-Fill für Ground Truth)
4. Disziplin-Vorschlag aus Loch-Größe (.22 ≈ 5.6mm, 9mm ≈ 9mm)
5. Übersichts-Annotated-Sheet rendern
"""
import json
from pathlib import Path
import numpy as np
import cv2
from PIL import Image
import pillow_heif
pillow_heif.register_heif_opener()

ROOT = Path(__file__).parent

def find_spiegel(gray):
    smooth = cv2.bilateralFilter(gray, 9, 75, 75)
    _, mask = cv2.threshold(smooth, 70, 255, cv2.THRESH_BINARY_INV)
    kernel = np.ones((9,9), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=3)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cands = []
    for c in cnts:
        area = cv2.contourArea(c)
        if area < 50000: continue
        perim = cv2.arcLength(c, True)
        if perim < 1: continue
        roundness = 4*np.pi*area/(perim**2)
        (cx, cy), r = cv2.minEnclosingCircle(c)
        cands.append({'roundness':roundness,'area':area,'cx':cx,'cy':cy,'r':r})
    if not cands: return None
    cands.sort(key=lambda x: -x['area']*x['roundness'])
    b = cands[0]
    return {'cx':int(b['cx']),'cy':int(b['cy']),'r':int(b['r']),
            'px_per_mm':b['r']/100.0,'roundness':b['roundness']}

def detect_holes(img_bgr, sp):
    """Hough Circles innen + außen — gibt Kandidaten mit Position+Radius."""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    ppm = sp['px_per_mm']
    sp_cx, sp_cy, sp_r = sp['cx'], sp['cy'], sp['r']
    H, W = gray.shape
    yy, xx = np.ogrid[:H, :W]
    dist = np.hypot(xx-sp_cx, yy-sp_cy)

    holes = []
    # Im Spiegel
    in_mask = (dist < sp_r*0.95).astype(np.uint8)*255
    inv = 255 - gray
    inv_m = cv2.bitwise_and(inv, inv, mask=in_mask)
    inv_m = cv2.GaussianBlur(inv_m, (5,5), 1)
    circs = cv2.HoughCircles(inv_m, cv2.HOUGH_GRADIENT, dp=1,
                              minDist=int(8*ppm), param1=120, param2=18,
                              minRadius=int(1.5*ppm), maxRadius=int(7*ppm))
    if circs is not None:
        for x,y,r in circs[0]:
            holes.append({'x':int(x),'y':int(y),'r_px':float(r),'zone':'in'})
    # Außen
    out_mask = ((dist > sp_r*1.05) & (dist < sp_r*2.5)).astype(np.uint8)*255
    gray_m = cv2.bitwise_and(gray, gray, mask=out_mask)
    gray_m = cv2.GaussianBlur(gray_m, (5,5), 1)
    circs = cv2.HoughCircles(gray_m, cv2.HOUGH_GRADIENT, dp=1,
                              minDist=int(8*ppm), param1=120, param2=18,
                              minRadius=int(1.5*ppm), maxRadius=int(7*ppm))
    if circs is not None:
        for x,y,r in circs[0]:
            holes.append({'x':int(x),'y':int(y),'r_px':float(r),'zone':'out'})
    return holes

def estimate_caliber(holes, ppm):
    """Aus Median-Loch-Radius Kaliber schätzen."""
    if not holes: return 'unknown'
    radii_mm = [h['r_px']/ppm*2 for h in holes]  # Durchmesser in mm
    med = float(np.median(radii_mm))
    # .22 ≈ 5.6mm, 9mm ≈ 9mm. Schwelle bei 7mm.
    return ('gk25', med) if med > 7 else ('kk25', med)

# === Konvertieren + Analysieren ===
heic_files = sorted(ROOT.glob('IMG_*.HEIC'))
print(f"Gefunden: {len(heic_files)} HEIC-Bilder")
manifest = []
for i, heic in enumerate(heic_files, 1):
    pil = Image.open(heic).convert('RGB')
    # Vereinfachtes Resize für Speicher: max 2400 lang
    if max(pil.size) > 2400:
        ratio = 2400 / max(pil.size)
        new_size = (int(pil.size[0]*ratio), int(pil.size[1]*ratio))
        pil = pil.resize(new_size, Image.LANCZOS)
    jpg_path = ROOT / f"bild_{i:02d}.jpg"
    pil.save(jpg_path, quality=92)
    img_bgr = cv2.imread(str(jpg_path))
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    sp = find_spiegel(gray)
    if not sp:
        print(f"  bild_{i:02d}: ⚠ Spiegel nicht gefunden")
        manifest.append({'idx':i,'file':jpg_path.name,'spiegel':None,'holes':[]})
        continue
    holes = detect_holes(img_bgr, sp)
    disz, med_mm = estimate_caliber(holes, sp['px_per_mm'])
    print(f"  bild_{i:02d}.jpg  spiegel=({sp['cx']},{sp['cy']}) r={sp['r']}  "
          f"ppm={sp['px_per_mm']:.2f}  holes={len(holes)}  ⌀={med_mm:.1f}mm → {disz}")
    manifest.append({
        'idx':i,'file':jpg_path.name,'source_heic':heic.name,
        'spiegel':sp,'holes':holes,
        'auto_disziplin':disz, 'med_hole_diameter_mm':round(med_mm,1),
    })

json.dump(manifest, open(ROOT/'manifest_auto.json','w',encoding='utf-8'), indent=2, ensure_ascii=False)
print(f"\nManifest: manifest_auto.json")

# === Annotated Übersichts-Sheet ===
print("\nRendere Übersichts-Sheet...")
N = len(manifest)
cols = 4; rows = (N + cols - 1) // cols
tile = 480
sheet = Image.new('RGB', (tile*cols, tile*rows + 40), (40,40,40))
from PIL import ImageDraw
d = ImageDraw.Draw(sheet)
d.text((8, 8), f"Trainings-Set: {N} Bilder. Spiegel = orange, Auto-Loch-Detection = grün (innen) / rot (außen).", fill=(255,255,255))
for i, m in enumerate(manifest):
    r, c = i // cols, i % cols
    if not m['spiegel']:
        continue
    img = Image.open(ROOT / m['file']).convert('RGB')
    sc = tile / max(img.size)
    new_size = (int(img.size[0]*sc), int(img.size[1]*sc))
    img_s = img.resize(new_size)
    sp = m['spiegel']
    dd = ImageDraw.Draw(img_s)
    dd.ellipse([(sp['cx']-sp['r'])*sc, (sp['cy']-sp['r'])*sc,
                (sp['cx']+sp['r'])*sc, (sp['cy']+sp['r'])*sc], outline=(255,180,40,255), width=2)
    for h in m['holes']:
        col = (60,255,60,255) if h['zone']=='in' else (255,80,80,255)
        dd.ellipse([(h['x']-h['r_px']*1.8)*sc, (h['y']-h['r_px']*1.8)*sc,
                    (h['x']+h['r_px']*1.8)*sc, (h['y']+h['r_px']*1.8)*sc], outline=col, width=2)
    dd.text((4,4), f"#{m['idx']}: {len(m['holes'])} Löcher  {m['auto_disziplin']} (⌀{m['med_hole_diameter_mm']}mm)", fill=(255,255,180))
    sheet.paste(img_s, (c*tile, 40 + r*tile))

sheet.save(ROOT/'overview_auto.jpg', quality=85)
print(f"  → overview_auto.jpg")
print(f"\nNächster Schritt: Übersicht öffnen, prüfen ob Disziplin + Loch-Anzahl pro Bild plausibel.")
