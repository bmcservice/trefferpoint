# v4-Kern v3: Hybrid-Extraktion = Komponenten (Mindestflaeche, Rausch-Schutz)
#             + Maxima-Splitting INNERHALB einer Komponente (Doppeltreffer, grosse Blobs)
# Lehre aus v2: pure Lokal-Maxima ohne Flaechen-Gate -> Phantom-Explosion (4 -> 28)
import sys, math, glob, re, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
sys.path.insert(0, 'sessions/2026-06-11_reviews')
from v4_core import SESS, TOL, load, reg_shift

def analyze3(nm):
    D,(cx,cy,a),GT = SESS[nm]
    fs = sorted(glob.glob(D+'/df_*.jpg'), key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    N=len(fs)
    ref = np.median(np.stack([load(f) for f in fs[-6:]]), axis=0)
    H,W = ref.shape; M=30
    x0,x1 = max(0,cx-int(2.0*a)-M), min(W,cx+int(2.0*a)+M)
    y0,y1 = max(0,cy-int(2.0*a)-M), min(H,cy+int(2.0*a)+M)
    yy,xx = np.mgrid[0:H,0:W]; sp=((xx-cx)**2+(yy-cy)**2)<(a*0.9)**2
    spmed=np.median(ref[sp])
    stk=[]
    for f in fs:
        img=load(f); dy,dx=reg_shift(img,ref,cx,cy,a)
        roi=img[y0:y1,x0:x1]
        roi=ndimage.shift(roi,(-dy,-dx),order=1,mode='nearest')
        roi+=spmed-np.median(roi[(sp[y0:y1,x0:x1])])
        stk.append(roi[::2,::2])
    S=np.stack(stk); base=np.median(S[:6],axis=0); R=S-base
    zsign=np.where(base<100,1.0,-1.0); Z=R*zsign
    pre=Z[:6]; post=Z[-6:]
    amp=post.mean(0)-pre.mean(0)
    sharp=amp/(pre.std(0)+post.std(0)+1.0)
    cum=np.cumsum(Z,axis=0); k=np.arange(1,N+1)[:,None,None]
    cusum=cum[-1][None]*k/N - cum
    tstar=np.argmax(cusum,axis=0)
    h,w=amp.shape; Yh,Xh=np.mgrid[0:h,0:w]
    rr=np.hypot(x0+Xh*2-cx, y0+Yh*2-cy)/a
    inzone=rr<1.9; edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8); bgl=ndimage.median_filter(amps,21); det=amps-bgl
    mxf = ndimage.maximum_filter(det, size=3)
    cands=[]
    lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        mask = lbl==kk
        ar = int(mask.sum())
        if ar<2: continue                      # Rausch-Schutz wie v1
        # Maxima innerhalb der Komponente, NMS r=3
        pys,pxs = np.where(mask & (det==mxf))
        order = np.argsort(-det[pys,pxs])
        kept=[]
        for oi in order:
            py,px = pys[oi],pxs[oi]
            if any((py-qy)**2+(px-qx)**2 < 9 for qy,qx in kept): continue
            kept.append((py,px))
        for py,px in kept:
            # Sub-Flaeche dieses Peaks: Komponenten-Pixel, die diesem Peak am naechsten sind
            ys,xs = np.where(mask)
            dthis = (ys-py)**2+(xs-px)**2
            sub = ar if len(kept)==1 else int(sum(
                1 for j in range(len(ys))
                if dthis[j] <= min(((ys[j]-qy)**2+(xs[j]-qx)**2) for qy,qx in kept)))
            if sub<2: continue
            s=float(sharp[max(0,py-1):py+2, max(0,px-1):px+2].max())
            cands.append(dict(
                x=float(x0+px*2), y=float(y0+py*2),
                det=float(det[py,px]), amp=float(amp[py,px]), sharp=s, area=sub*4,
                t=int(tstar[py,px]), ra=float(rr[py,px]), edge=bool(edge[py,px]),
            ))
    return dict(N=N, cands=cands, GT=GT)

def score(res, thr, pen=2.0):
    GT=res['GT']
    sel=[c for c in res['cands'] if c['sharp']>=thr*(pen if c['edge'] else 1.0)]
    used=[False]*len(sel); rec=0
    for gx,gy in GT:
        best=-1; bd=TOL+1
        for i,c in enumerate(sel):
            if used[i]: continue
            d=math.hypot(c['x']-gx,c['y']-gy)
            if d<=TOL and d<bd: bd=d; best=i
        if best>=0: used[best]=True; rec+=1
    return rec,len(GT),used.count(False)

if __name__=='__main__':
    RES={}
    for nm in SESS:
        print(f'... {nm}',flush=True)
        RES[nm]=analyze3(nm)
        print(f'    {len(RES[nm]["cands"])} Kandidaten',flush=True)
    json.dump(RES, open('sessions/2026-06-11_reviews/v4_core3_cands.json','w'))
    THS=[round(x*0.5,1) for x in range(2,40)]
    print()
    print('=== v4-Kern v3 (Hybrid: Komponenten + Maxima-Splitting) LOSO ===')
    ar=an=af=0; per={}
    for held in RES:
        tr=[x for x in RES if x!=held]
        bt=max(THS,key=lambda t:sum(score(RES[n],t)[0] for n in tr)-0.5*sum(score(RES[n],t)[2] for n in tr))
        r,n,f=score(RES[held],bt); ar+=r;an+=n;af+=f; per[held]=(bt,r,n,f)
    print(f'  Recall {ar}/{an} ({100*ar//an}%), Phantome {af} ({af/6:.2f}/Serie)')
    for nm,(bt,r,n,f) in per.items():
        print(f'   {nm:5s} T={bt:4.1f}: {r}/{n} +{f}P')
    print()
    print('Vergleich: v1=20/28+4P | v2(NMS pur)=21/28+28P | vor v4: 15/28+16P')
