# Baustein 1: Diagnose der 8 verpassten Loecher im v4-Kern
# Pro Miss: Basis/Schluss/Sprungkarten-Patch + Zeitverlauf + Pipeline-Werte -> Miss-Ursache
import sys, math, glob, re
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
sys.path.insert(0, 'sessions/2026-06-11_reviews')
from v4_core import SESS, TOL, load, reg_shift

THR, PEN = 19.5, 2.0
rows=[]
for nm in SESS:
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
    h,w=amp.shape; Yh,Xh=np.mgrid[0:h,0:w]
    rr=np.hypot(x0+Xh*2-cx, y0+Yh*2-cy)/a
    inzone=rr<1.9; edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8); bgl=ndimage.median_filter(amps,21); det=amps-bgl
    # Detektion wie gehabt
    cands=[]
    lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        ys,xs=np.where(lbl==kk); ar=len(xs)
        if ar<2 or ar>120: continue
        wgt=det[ys,xs]; cyh=np.average(ys,weights=wgt); cxh=np.average(xs,weights=wgt)
        iy,ix=int(round(cyh)),int(round(cxh))
        s=float(sharp[max(0,iy-1):iy+2,max(0,ix-1):ix+2].max())
        if s >= THR*(PEN if edge[iy,ix] else 1.0):
            cands.append((x0+cxh*2, y0+cyh*2))
    used=[False]*len(cands); missed=[]
    for gi,(gx,gy) in enumerate(GT):
        best=-1; bd=TOL+1
        for i,c in enumerate(cands):
            if used[i]: continue
            d=math.hypot(c[0]-gx,c[1]-gy)
            if d<=TOL and d<bd: bd=d; best=i
        if best>=0: used[best]=True
        else: missed.append((gx,gy))
    for gx,gy in missed:
        ix,iy = int((gx-x0)/2), int((gy-y0)/2)
        sl=(slice(max(0,iy-3),iy+4), slice(max(0,ix-3),ix+4))
        traj=[float(Z[t][sl].mean()) for t in range(N)]
        # Pipeline-Werte lokal (7x7-Maximum)
        lamp=float(amp[sl].max()); lsharp=float(sharp[sl].max()); ldet=float(det[sl].max())
        led=bool(edge[iy,ix]); lra=float(rr[iy,ix])
        lzone='dunkel(+)' if base[iy,ix]<100 else 'hell(-)'
        lbase=float(base[iy,ix])
        # Patches Vollaufloesung
        P=22
        pb_full = np.median(np.stack([load(f) for f in fs[:6]]),axis=0)[gy-P:gy+P, gx-P:gx+P]
        pf_full = ref[gy-P:gy+P, gx-P:gx+P]
        pa = amp[max(0,iy-11):iy+12, max(0,ix-11):ix+12]
        # Ursache klassifizieren
        if led and lsharp>=THR: cause='RANDBAND-Malus (Signal da!)'
        elif lsharp>=THR and ldet<=8: cause='Blob-Schwelle det<=8 (Hintergrund-Abzug frisst Signal)'
        elif lamp<8: cause='kein Sprung-Signal (zu schwach/Polaritaet?)'
        elif lsharp<THR: cause=f'Schaerfe {lsharp:.1f} < {THR} (verrauscht)'
        else: cause='Blob-Geometrie (Flaeche/Form)'
        rows.append(dict(nm=nm,gx=gx,gy=gy,amp=lamp,sharp=lsharp,det=ldet,edge=led,ra=lra,
                         zone=lzone,zb=lbase,traj=traj,pb=pb_full,pf=pf_full,pa=pa,cause=cause))
    print(f'{nm}: {len(missed)} Misses', flush=True)

print()
print(f"{'Sess':5s} {'x':>5} {'y':>5} {'r/a':>5} {'Zone':>9} {'Basis':>5} {'amp':>6} {'schaerfe':>8} {'det':>6} {'Rand':>5}  Ursache")
for r in rows:
    print(f"{r['nm']:5s} {r['gx']:>5} {r['gy']:>5} {r['ra']:5.2f} {r['zone']:>9} {r['zb']:5.0f} {r['amp']:6.1f} {r['sharp']:8.1f} {r['det']:6.1f} {str(r['edge']):>5}  {r['cause']}")

Nr=len(rows)
fig,axs=plt.subplots(Nr,4,figsize=(11,2.1*Nr))
if Nr==1: axs=axs[None,:]
for i,r in enumerate(rows):
    ax=axs[i,0]; ax.imshow(r['pb'],cmap='gray',vmin=0,vmax=255); ax.set_xticks([]);ax.set_yticks([])
    ax.set_ylabel(f"{r['nm']}\n({r['gx']},{r['gy']})",fontsize=8,rotation=0,ha='right',va='center')
    if i==0: ax.set_title('Basis',fontsize=9)
    ax=axs[i,1]; ax.imshow(r['pf'],cmap='gray',vmin=0,vmax=255); ax.set_xticks([]);ax.set_yticks([])
    if i==0: ax.set_title('Schluss',fontsize=9)
    ax=axs[i,2]; ax.imshow(r['pa'],cmap='coolwarm',vmin=-40,vmax=40); ax.set_xticks([]);ax.set_yticks([])
    if i==0: ax.set_title('Sprungkarte',fontsize=9)
    ax=axs[i,3]; ax.plot(r['traj'],'o-',ms=2.5)
    ax.axhline(0,color='gray',lw=0.5); ax.tick_params(labelsize=6)
    ax.set_title(r['cause'],fontsize=7)
fig.suptitle('v4 — die verpassten Loecher: warum?',fontsize=12)
fig.tight_layout(rect=[0.05,0,1,0.98])
fig.savefig('reports/P7_v4_misses.png',dpi=110)
print('\nPNG: reports/P7_v4_misses.png')
