# v4-Kern (Phase 2): temporale Sprung-Detektion + Zweizonen + Randband
# Architektur aus Audit + 4 Reviews + Phase-1-Befund (P5_0531_BEFUND.md):
#  - Registrierung aller Frames auf Schluss-Median (global, Phasenkorrelation, verifiziert P0)
#  - Zone aus Basis-Helligkeit: dunkel(<100) -> Schuss=heller(+), hell(>=100) -> Schuss=dunkler(-)
#  - Sprung-Statistik pro Pixel (halbe Aufloesung): amp = mean(letzte 6) - mean(erste 6),
#    Sprungzeit t* via CUSUM, Schaerfe = amp / (pre_std + post_std + 1)
#  - Kandidaten = Blobs der zonen-signierten amp-Karte; Randband r/a 0.85-1.08 separat
# Eval: LOSO ueber Schaerfe-Schwelle, TOL=12 (Vollaufloesung), greedy 1:1
import sys, math, glob, re, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage

SESS = {
 'A':   ('sessions/corpus/1780821347685_kk25/1780821347685_kk25', (1214,506,231), [(1048,762),(1018,594),(1019,404),(1320,501),(1330,506)]),
 'B':   ('sessions/corpus/1780825379534_kk25/1780825379534_kk25', (1072,496,231), [(1007,520),(1070,439),(1158,466),(1065,383),(1262,264)]),
 '0603':('sessions/2026-06-03_etf150_stand/seq_1546/raw',          (986,469,232), [(630,738),(960,426),(1060,408),(1108,506),(936,592)]),
 '0606':('sessions/2026-06-06_stand/1780763553002_kk25',           (1121,496,230), [(1020,704),(1095,556),(1005,415)]),
 '1528':('sessions/corpus_gt/1780500498560_kk25',                  (984,499,232), [(1130,716),(798,659),(948,512),(1011,540),(1100,546)]),
 '0531':('sessions/corpus_gt/1780216449975_kk25',                  (1048,498,310), [(613,477),(820,669),(845,547),(927,552),(846,419)]),
}
TOL = 12

def load(f): return np.asarray(Image.open(f).convert('L'), dtype=np.float32)

def reg_shift(mov, ref, cx, cy, a):
    r = int(a*1.1)
    m = mov[cy-r:cy+r, cx-r:cx+r]; f = ref[cy-r:cy+r, cx-r:cx+r]
    H, W = m.shape; win = np.outer(np.hanning(H), np.hanning(W))
    A = np.fft.fft2((m-m.mean())*win); B = np.fft.fft2((f-f.mean())*win)
    R = A*np.conj(B); R /= np.abs(R)+1e-6
    c = np.fft.ifft2(R).real; pk = np.unravel_index(np.argmax(c), c.shape)
    def par(v,i,n):
        a0=v[(i-1)%n]; a1=v[i]; a2=v[(i+1)%n]; return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy = pk[0]+par(c[:,pk[1]],pk[0],H); dx = pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2: dy-=H
    if dx>W/2: dx-=W
    return dy, dx

def analyze(nm):
    D,(cx,cy,a),GT = SESS[nm]
    fs = sorted(glob.glob(D+'/df_*.jpg'), key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    N = len(fs)
    ref = np.median(np.stack([load(f) for f in fs[-6:]]), axis=0)
    H,W = ref.shape
    # ROI um die Scheibe (bis 2a, im Bild)
    M = 30
    x0,x1 = max(0,cx-int(2.0*a)-M), min(W,cx+int(2.0*a)+M)
    y0,y1 = max(0,cy-int(2.0*a)-M), min(H,cy+int(2.0*a)+M)
    refR = ref[y0:y1, x0:x1]
    yy,xx = np.mgrid[0:H,0:W]; sp = ((xx-cx)**2+(yy-cy)**2)<(a*0.9)**2
    spmed = np.median(ref[sp])
    # alle Frames registrieren (halbe Aufloesung im Stack)
    stk=[]
    for f in fs:
        img = load(f)
        dy,dx = reg_shift(img, ref, cx, cy, a)
        roi = img[y0:y1, x0:x1]
        roi = ndimage.shift(roi, (-dy,-dx), order=1, mode='nearest')
        roi += spmed - np.median(roi[(sp[y0:y1,x0:x1])])
        stk.append(roi[::2,::2])
    S = np.stack(stk)                          # N x h x w
    base = np.median(S[:6], axis=0)
    R = S - base                               # Residuum-Trajektorien
    # Zone aus Basis: dunkel -> +1 (Schuss heller), hell -> -1 (Schuss dunkler)
    zsign = np.where(base < 100, 1.0, -1.0)
    Z = R * zsign                              # zonen-signiert: Schuss = positiver Sprung
    pre  = Z[:6];  post = Z[-6:]
    amp  = post.mean(0) - pre.mean(0)
    sharp = amp / (pre.std(0) + post.std(0) + 1.0)
    # Sprungzeit (CUSUM) + Post-Stabilitaet um t*
    cum = np.cumsum(Z, axis=0)
    k = np.arange(1,N+1)[:,None,None]
    cusum = cum[-1][None]*k/N - cum            # max dort, wo Sprung beginnt
    tstar = np.argmax(cusum, axis=0)
    # Geometrie
    h,w = amp.shape
    Yh,Xh = np.mgrid[0:h,0:w]
    fullx = x0 + Xh*2; fully = y0 + Yh*2
    rr = np.hypot(fullx-cx, fully-cy)/a
    inzone = rr < 1.9
    edge   = (rr>0.85)&(rr<1.08)
    # Kandidaten aus geglaetteter amp-Karte
    amps = ndimage.gaussian_filter(amp, 0.8)
    bgl  = ndimage.median_filter(amps, 21)
    det  = amps - bgl
    cands=[]
    lbl,n = ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        ys,xs = np.where(lbl==kk); ar=len(xs)
        if ar<2 or ar>120: continue
        wgt = det[ys,xs]; cyh=np.average(ys,weights=wgt); cxh=np.average(xs,weights=wgt)
        iy,ix = int(round(cyh)), int(round(cxh))
        c = dict(
            x = x0+cxh*2, y = y0+cyh*2,
            amp = float(det[ys,xs].max()),
            sharp = float(sharp[max(0,iy-1):iy+2, max(0,ix-1):ix+2].max()),
            t = int(np.median(tstar[ys,xs])),
            ra = float(rr[iy,ix]), edge = bool(edge[iy,ix]),
            area = ar*4,
        )
        # Post-Stabilitaet: nach t*+1 soll das Signal stehen (Loch bleibt)
        t0 = min(c['t']+1, N-3)
        seg = Z[t0:, iy, ix]
        c['post_std'] = float(seg.std())
        cands.append(c)
    return dict(N=N, cands=cands, GT=GT)

def score(res, thr, use_edge_penalty=True):
    GT = res['GT']
    sel = [c for c in res['cands'] if c['sharp']>=thr*(2.0 if (use_edge_penalty and c['edge']) else 1.0)]
    used=[False]*len(sel); rec=0
    for gx,gy in GT:
        best=-1; bd=TOL+1
        for i,c in enumerate(sel):
            if used[i]: continue
            d=math.hypot(c['x']-gx, c['y']-gy)
            if d<=TOL and d<bd: bd=d; best=i
        if best>=0: used[best]=True; rec+=1
    return rec, len(GT), used.count(False)

if __name__ == '__main__':
    RES={}
    for nm in SESS:
        print(f'... analysiere {nm}', flush=True)
        RES[nm] = analyze(nm)
        print(f'    {nm}: {len(RES[nm]["cands"])} Roh-Kandidaten', flush=True)
    json.dump({nm:dict(N=r['N'],GT=r['GT'],cands=r['cands']) for nm,r in RES.items()},
              open('sessions/2026-06-11_reviews/v4_core_cands.json','w'))
    THS=[round(x*0.5,1) for x in range(2,40)]
    print()
    print('=== v4-Kern LOSO (Schaerfe-Schwelle, Randband x2-Malus) ===')
    for pen in [True, False]:
        ar=an=af=0; det_per={}
        for held in RES:
            tr=[x for x in RES if x!=held]
            bt=max(THS, key=lambda t: sum(score(RES[n],t,pen)[0] for n in tr) - 0.5*sum(score(RES[n],t,pen)[2] for n in tr))
            r,n,f = score(RES[held],bt,pen); ar+=r; an+=n; af+=f
            det_per[held]=(bt,r,n,f)
        print(f'  Randband-Malus={pen}: Recall {ar}/{an} ({100*ar//an}%), Phantome {af} ({af/6:.2f}/Serie)')
        for nm,(bt,r,n,f) in det_per.items():
            print(f'     {nm:5s} T={bt:4.1f}: {r}/{n} +{f}P')
    print()
    print('Vergleich Stand vorher: 15/28 (53%), 16 Phantome (2.7/Serie)')
