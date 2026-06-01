#!/usr/bin/env python3
"""TrefferPoint Detection-Pipeline v2.4.0 (Offline-Referenzimplementierung).

Zwei Modi:
  - photo_detect():     Einzelbild (Endauswertung nach Serie). Template direkt.
  - stream_detect():    Frame-Diff + Image-Registration (Live-Pipeline).

Bausteine:
  1. find_spiegel():    robuste Spiegel-Detektion (roundness + zentral + Radius-Range)
  2. register():        Translation-Registration (JS-portierbar: brute-force ±N px)
  3. Template-Matching: Loch-Form-Korrelation
  4. NMS:               korrektes Non-Maximum-Suppression
  5. center_verify():   Loch-Mitte muss Kontrast zum Umfeld haben

Genormte Geometrie (Krüger 3100, KK25/GK25):
  Spiegel ∅200mm (r=100mm), Ring 10 r=25mm, Schritt 25mm/Ring, Ring 1 r=250mm.
"""
import numpy as np
import cv2

KALIBER = {'kk25': 5.6, 'gk25': 9.0, 'leer': 5.6}
RINGS = [(10,25),(9,50),(8,75),(7,100),(6,125),(5,150),(4,175),(3,200),(2,225),(1,250)]


# ============================================================================
# 1. Spiegel-Detektion (robust gegen dunkle Wand-Hintergründe)
# ============================================================================
def find_spiegel(gray, expected_r_range=(150, 700)):
    """Größte runde dunkle Region nahe Bildmitte = Spiegel.

    Score = area * roundness^2 * centrality
    roundness gewichtet doppelt → schließt nicht-runde Wand-Flächen aus.
    """
    H, W = gray.shape
    img_cx, img_cy = W/2, H/2
    smooth = cv2.bilateralFilter(gray, 9, 75, 75)
    _, mask = cv2.threshold(smooth, 70, 255, cv2.THRESH_BINARY_INV)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((9,9),np.uint8), iterations=3)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    best = None
    for c in cnts:
        area = cv2.contourArea(c)
        if area < 20000: continue
        perim = cv2.arcLength(c, True)
        if perim < 1: continue
        roundness = 4*np.pi*area/(perim*perim)   # 1.0 = perfekter Kreis
        if roundness < 0.6: continue              # Wand/Rechteck raus
        (cx, cy), r = cv2.minEnclosingCircle(c)
        if not (expected_r_range[0] <= r <= expected_r_range[1]): continue
        centrality = 1.0 / (1.0 + np.hypot(cx-img_cx, cy-img_cy)/(W*0.5))
        score = area * roundness**2 * centrality
        if best is None or score > best[0]:
            best = (score, cx, cy, r, roundness)
    if best is None: return None
    _, cx, cy, r, rnd = best
    return {'cx': int(cx), 'cy': int(cy), 'r': int(r),
            'px_per_mm': r/100.0, 'roundness': round(rnd,3)}


# ============================================================================
# 2. Registration (Translation, JS-portierbar via brute-force)
# ============================================================================
def register_translation(ref, post, max_shift=10, step=1):
    """Findet (dx,dy) der ref auf post ausrichtet (minimiert MSE im Overlap).
    Translation-only, brute-force — direkt nach JS portierbar.
    Liefert (aligned_ref, (dx,dy))."""
    ref = ref.astype(np.float32); post = post.astype(np.float32)
    H, W = ref.shape
    # Suchbereich auf zentrale Region (Spiegel-Umfeld) beschränken für Speed
    cy0, cx0 = H//2, W//2
    rh, rw = H//3, W//3
    p_roi = post[cy0-rh:cy0+rh, cx0-rw:cx0+rw]
    best = (1e18, 0, 0)
    for dy in range(-max_shift, max_shift+1, step):
        for dx in range(-max_shift, max_shift+1, step):
            r_roi = ref[cy0-rh+dy:cy0+rh+dy, cx0-rw+dx:cx0+rw+dx]
            if r_roi.shape != p_roi.shape: continue
            mse = np.mean((r_roi - p_roi)**2)
            if mse < best[0]: best = (mse, dx, dy)
    _, dx, dy = best
    M = np.float32([[1,0,dx],[0,1,dy]])
    aligned = cv2.warpAffine(ref, M, (W,H), flags=cv2.INTER_LINEAR)
    return aligned, (dx, dy)

def register_ecc(ref, post):
    """ECC-Registration (Euclidean: Translation+Rotation). Höhere Qualität,
    nicht trivial JS-portierbar. Fallback auf Translation bei Fehler."""
    ref8 = np.clip(ref,0,255).astype(np.uint8)
    post8 = np.clip(post,0,255).astype(np.uint8)
    warp = np.eye(2,3,dtype=np.float32)
    try:
        crit = (cv2.TERM_CRITERIA_EPS|cv2.TERM_CRITERIA_COUNT, 100, 1e-5)
        cv2.findTransformECC(post8, ref8, warp, cv2.MOTION_EUCLIDEAN, crit, None, 5)
        aligned = cv2.warpAffine(ref, warp, (ref.shape[1],ref.shape[0]),
                                 flags=cv2.INTER_LINEAR+cv2.WARP_INVERSE_MAP)
        return aligned, (float(warp[0,2]), float(warp[1,2]))
    except cv2.error:
        return register_translation(ref, post)


# ============================================================================
# 3+4. Template-Matching + NMS
# ============================================================================
def _make_blob(r_px):
    ts = max(5, int(r_px*4)|1)
    blob = np.zeros((ts,ts), np.float32)
    cv2.circle(blob, (ts//2,ts//2), max(1,int(r_px)), 255, -1)
    return cv2.GaussianBlur(blob, (3,3), 1), ts

def _nms(dets, radius):
    dets = sorted(dets, key=lambda x:-x['score'])
    keep = []
    for d in dets:
        if all(np.hypot(d['x']-k['x'], d['y']-k['y']) >= radius for k in keep):
            keep.append(d)
    return keep

def _template_zone(field, blob, ts, thr, sp, zone, normalize=False):
    """Template-Matching in einer Zone (in/out)."""
    cx0, cy0, r0 = sp['cx'], sp['cy'], sp['r']
    H, W = field.shape
    f = field.astype(np.float32)
    if normalize:
        f = np.clip(f, 0, None); f = f/(f.max()+1e-6)*255
    res = cv2.matchTemplate(f, blob, cv2.TM_CCOEFF_NORMED)
    out = []
    for pt in zip(*np.where(res>thr)[::-1]):
        x = pt[0]+ts//2; y = pt[1]+ts//2
        d = np.hypot(x-cx0, y-cy0)
        if zone=='in' and not d < r0*0.93: continue
        if zone=='out' and not r0*1.05 < d < r0*2.4: continue
        out.append({'x':x,'y':y,'score':float(res[pt[1],pt[0]])})
    return out


# ============================================================================
# 5. Center-Pixel-Verifikation
# ============================================================================
def center_verify(gray, dets, r_px, sp, min_delta=25):
    """Loch-Mitte muss Kontrast zum lokalen Hintergrund haben.
    Im Spiegel: Mitte heller. Außen: Mitte dunkler."""
    H, W = gray.shape
    cx0, cy0, r0 = sp['cx'], sp['cy'], sp['r']
    out = []
    for d in dets:
        x, y = d['x'], d['y']
        rr = int(r_px*3)
        x0,y0 = max(0,x-rr), max(0,y-rr); x1,y1 = min(W,x+rr), min(H,y+rr)
        roi = gray[y0:y1, x0:x1]
        if roi.size == 0: continue
        c = int(np.median(gray[max(0,y-2):y+3, max(0,x-2):x+3]))
        bg = int(np.median(roi))
        dist = np.hypot(x-cx0, y-cy0)
        in_spiegel = dist < r0*0.93
        if in_spiegel and c < bg + min_delta: continue
        if not in_spiegel and c > bg - min_delta*1.5: continue
        out.append(d)
    return out


# ============================================================================
# High-Level: Foto-Modus
# ============================================================================
def photo_detect(img_bgr, disziplin='kk25', sp=None, thr=0.55, nms_factor=1.2, verify=True):
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    if sp is None:
        sp = find_spiegel(gray)
        if sp is None: return [], None
    ppm = sp['px_per_mm']
    kal = KALIBER[disziplin]
    r_px = max(3, kal*0.5*ppm)
    blob_bright, ts = _make_blob(r_px)
    # blob = heller Kreis auf dunklem Grund.
    # in-Zone (schwarzer Spiegel): Loch ist HELL → blob matcht direkt auf gray
    # out-Zone (weißer Bereich): Loch ist DUNKEL → auf invertiertem gray matchen
    g = gray.astype(np.float32)
    inv = (255 - gray).astype(np.float32)
    raw  = _template_zone(g,   blob_bright, ts, thr, sp, 'in')
    raw += _template_zone(inv, blob_bright, ts, thr, sp, 'out')
    nms = _nms(raw, max(4,int(nms_factor*kal*ppm)))
    if verify:
        nms = center_verify(gray, nms, r_px, sp)
    return nms, sp


# ============================================================================
# High-Level: Stream-Modus (Frame-Diff + Registration)
# ============================================================================
def stream_detect(ref_gray, post_gray, disziplin='kk25', sp=None,
                  thr=0.60, nms_factor=1.5, do_register=True):
    post_gray = post_gray.astype(np.float32)
    ref_gray = ref_gray.astype(np.float32)
    if sp is None:
        sp = find_spiegel(post_gray.astype(np.uint8))
        if sp is None: return [], None, (0,0)
    shift = (0,0)
    if do_register:
        ref_gray, shift = register_ecc(ref_gray, post_gray)
    diff = post_gray - ref_gray
    ppm = sp['px_per_mm']
    kal = KALIBER[disziplin]
    r_px = max(3, kal*0.5*ppm)
    blob, ts = _make_blob(r_px)
    raw  = _template_zone(diff,  blob, ts, thr, sp, 'in',  normalize=True)
    raw += _template_zone(-diff, blob, ts, thr, sp, 'out', normalize=True)
    nms = _nms(raw, max(4,int(nms_factor*kal*ppm)))
    # Diff-Magnitude-Verify: am Loch muss die lokale Diff-Energie deutlich
    # über dem Median-Rauschen liegen (filtert Beleuchtungs-Rest-Artefakte)
    diff_abs = np.abs(diff)
    noise = float(np.median(diff_abs)) + 1e-6
    verified = []
    for d in nms:
        x, y = d['x'], d['y']
        rr = int(r_px*1.5)
        patch = diff_abs[max(0,y-rr):y+rr+1, max(0,x-rr):x+rr+1]
        if patch.size == 0: continue
        local_peak = float(patch.max())
        if local_peak < noise * 6: continue   # zu schwach = Artefakt
        d['snr'] = local_peak / noise
        verified.append(d)
    return verified, sp, shift


# ============================================================================
# Hilfsfunktionen: Ring/Position
# ============================================================================
def to_mm(d, sp):
    return ((d['x']-sp['cx'])/sp['px_per_mm'], (d['y']-sp['cy'])/sp['px_per_mm'])

def ring_of(dist_mm):
    for r, rad in RINGS:
        if dist_mm <= rad: return r
    return 0
