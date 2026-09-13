# Beleg-Bilder fuer den v4-Kern: zonen-signierte Sprungkarte + Detektionen vs GT je Session
# (QS-Regel 1+3: jede Stufe ansehen, keine Skalar-Metrik ohne gepaartes Bild)
import sys, math, glob, re
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
sys.path.insert(0, 'sessions/2026-06-11_reviews')
from v4_core import SESS, TOL, load, reg_shift

THR = 19.5   # LOSO-Schwelle aus v4_core-Lauf
PEN = 2.0    # Randband-Malus

fig, axs = plt.subplots(6, 3, figsize=(15, 26))
summary=[]
for row, nm in enumerate(SESS):
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
    cands=[]
    lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        ys,xs=np.where(lbl==kk); ar=len(xs)
        if ar<2 or ar>120: continue
        wgt=det[ys,xs]; cyh=np.average(ys,weights=wgt); cxh=np.average(xs,weights=wgt)
        iy,ix=int(round(cyh)),int(round(cxh))
        s=float(sharp[max(0,iy-1):iy+2,max(0,ix-1):ix+2].max())
        e=bool(edge[iy,ix])
        if s >= THR*(PEN if e else 1.0):
            cands.append((x0+cxh*2, y0+cyh*2))
    used=[False]*len(cands); rec=0; matched=set()
    for gi,(gx,gy) in enumerate(GT):
        best=-1; bd=TOL+1
        for i,c in enumerate(cands):
            if used[i]: continue
            d=math.hypot(c[0]-gx,c[1]-gy)
            if d<=TOL and d<bd: bd=d; best=i
        if best>=0: used[best]=True; rec+=1; matched.add(gi)
    fp=used.count(False)
    summary.append(f'{nm}: {rec}/{len(GT)} +{fp}P')
    # Panels: Schlussbild | Sprungkarte | Detektion vs GT
    ax=axs[row,0]; ax.imshow(ref[y0:y1,x0:x1],cmap='gray',vmin=0,vmax=255)
    ax.set_ylabel(nm,fontsize=13,weight='bold'); ax.set_xticks([]); ax.set_yticks([])
    if row==0: ax.set_title('Schlussbild (ROI)',fontsize=11)
    ax=axs[row,1]; ax.imshow(amp,cmap='coolwarm',vmin=-40,vmax=40)
    ax.set_xticks([]); ax.set_yticks([])
    if row==0: ax.set_title('zonen-signierte Sprungkarte (vorher/nachher)',fontsize=11)
    ax=axs[row,2]; ax.imshow(ref[y0:y1,x0:x1],cmap='gray',vmin=0,vmax=255)
    for gi,(gx,gy) in enumerate(GT):
        col='#2f2' if gi in matched else '#f60'
        ax.add_patch(plt.Circle((gx-x0,gy-y0),14,fill=False,color=col,lw=2.2))
    for i,c in enumerate(cands):
        if not used[i]:
            ax.add_patch(plt.Circle((c[0]-x0,c[1]-y0),10,fill=False,color='cyan',lw=1.8,ls='--'))
    ax.set_xticks([]); ax.set_yticks([])
    if row==0: ax.set_title('gruen=getroffen, orange=verpasst, cyan=Phantom',fontsize=11)
    print(f'{nm}: {rec}/{len(GT)} +{fp}P', flush=True)
fig.suptitle(f'v4-Kern Beleg — Schwelle {THR}, Randband-Malus x{PEN}  |  '+('  '.join(summary)),fontsize=13)
fig.tight_layout(rect=[0,0,1,0.985])
fig.savefig('reports/P6_v4_beleg.png',dpi=100)
print('PNG: reports/P6_v4_beleg.png')
