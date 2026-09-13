# v4-Kern (eingefroren) auf den frischen Stand-Serien 2026-06-14
# Cal NATIV in Detframe-Koordinaten (1920x1080), Zentrum per Dunkelblob verfeinert.
# KEIN GT-Scoring hier (Scheiben-Fotos fehlen noch) -> nur Detektionen + Overlay.
import sys, math, glob, re, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

SESS = {
 '0906_34h': ('1781428002738_kk25', (1055,442,231)),
 '0900_10h': ('1781427643399_kk25', (1052,463,231)),
}
def load(f): return np.asarray(Image.open(f).convert('L'), dtype=np.float32)

def refine_center(fin, cx, cy, a):
    # Dunkelblob nahe (cx,cy) als echtes Spiegelzentrum
    H,W=fin.shape; yy,xx=np.mgrid[0:H,0:W]
    near=((xx-cx)**2+(yy-cy)**2)<(a*1.3)**2
    dark=(fin<np.percentile(fin[near],35))&near
    dark=ndimage.binary_opening(dark,iterations=2)
    lbl,n=ndimage.label(dark)
    if n==0: return cx,cy
    best=np.argmax(ndimage.sum(np.ones_like(lbl),lbl,range(1,n+1)))+1
    ys,xs=np.where(lbl==best)
    return float(xs.mean()), float(ys.mean())

def reg_shift(mov, ref, cx, cy, a):
    r=int(a*1.1)
    y0,y1=max(0,cy-r),min(ref.shape[0],cy+r); x0,x1=max(0,cx-r),min(ref.shape[1],cx+r)
    m=mov[y0:y1,x0:x1]; f=ref[y0:y1,x0:x1]
    H,W=m.shape; win=np.outer(np.hanning(H),np.hanning(W))
    A=np.fft.fft2((m-m.mean())*win); B=np.fft.fft2((f-f.mean())*win)
    R=A*np.conj(B); R/=np.abs(R)+1e-6
    c=np.fft.ifft2(R).real; pk=np.unravel_index(np.argmax(c),c.shape)
    def par(v,i,n):
        a0=v[(i-1)%n];a1=v[i];a2=v[(i+1)%n];return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy=pk[0]+par(c[:,pk[1]],pk[0],H); dx=pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2:dy-=H
    if dx>W/2:dx-=W
    return dy,dx

def analyze(folder, cal):
    D='sessions/2026-06-14_stand/'+folder
    fs=sorted(glob.glob(D+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    N=len(fs)
    ref=np.median(np.stack([load(f) for f in fs[-6:]]),axis=0)
    H,W=ref.shape
    cx,cy,a=cal; cx,cy=refine_center(ref,cx,cy,a); cx,cy=int(cx),int(cy)
    M=30
    x0,x1=max(0,cx-int(2.0*a)-M),min(W,cx+int(2.0*a)+M)
    y0,y1=max(0,cy-int(2.0*a)-M),min(H,cy+int(2.0*a)+M)
    yy,xx=np.mgrid[0:H,0:W]; sp=((xx-cx)**2+(yy-cy)**2)<(a*0.9)**2
    spmed=np.median(ref[sp])
    stk=[]
    for f in fs:
        img=load(f); dy,dx=reg_shift(img,ref,cx,cy,a)
        roi=img[y0:y1,x0:x1]; roi=ndimage.shift(roi,(-dy,-dx),order=1,mode='nearest')
        roi+=spmed-np.median(roi[sp[y0:y1,x0:x1]])
        stk.append(roi[::2,::2])
    S=np.stack(stk); base=np.median(S[:6],axis=0); R=S-base
    zsign=np.where(base<100,1.0,-1.0); Z=R*zsign
    amp=Z[-6:].mean(0)-Z[:6].mean(0)
    sharp=amp/(Z[:6].std(0)+Z[-6:].std(0)+1.0)
    h,w=amp.shape; Yh,Xh=np.mgrid[0:h,0:w]
    rr=np.hypot(x0+Xh*2-cx,y0+Yh*2-cy)/a
    inzone=rr<1.9; edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8); bgl=ndimage.median_filter(amps,21); det=amps-bgl
    mxf=ndimage.maximum_filter(det,size=3)
    THR,PEN=19.5,2.0
    cands=[]
    lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        mask=lbl==kk; ar=int(mask.sum())
        if ar<2 or ar>120: continue
        pys,pxs=np.where(mask&(det==mxf)); order=np.argsort(-det[pys,pxs]); kept=[]
        for oi in order:
            py,px=pys[oi],pxs[oi]
            if any((py-qy)**2+(px-qx)**2<9 for qy,qx in kept): continue
            kept.append((py,px))
        for py,px in kept:
            s=float(sharp[max(0,py-1):py+2,max(0,px-1):px+2].max())
            if s>=THR*(PEN if edge[py,px] else 1.0):
                cands.append((float(x0+px*2),float(y0+py*2),s))
    return dict(N=N,W=W,H=H,cx=cx,cy=cy,a=a,ref=ref,cands=cands,amp=amp,x0=x0,y0=y0)

RES={}
fig,axs=plt.subplots(1,2,figsize=(18,6))
for ax,(tag,(folder,cal)) in zip(axs,SESS.items()):
    r=analyze(folder,cal); RES[tag]=r
    print(f'{tag} ({folder}): {r["N"]} Frames, Spiegel verfeinert ({r["cx"]},{r["cy"]}) a={r["a"]}, v4-Detektionen: {len(r["cands"])}',flush=True)
    for (x,y,s) in sorted(r['cands'],key=lambda c:-c[2]):
        rmm=math.hypot(x-r['cx'],y-r['cy'])/2.289
        print(f'    det ({x:.0f},{y:.0f}) Schaerfe {s:.0f}  r={rmm:.0f}mm',flush=True)
    ax.imshow(r['ref'],cmap='gray',vmin=0,vmax=255)
    ax.add_patch(plt.Circle((r['cx'],r['cy']),r['a'],fill=False,color='#48f',lw=1,ls=':'))
    for (x,y,s) in r['cands']:
        ax.add_patch(plt.Circle((x,y),16,fill=False,color='#2f2',lw=2))
    ax.set_title(f'{tag}: {len(r["cands"])} v4-Detektionen (gruen)'); ax.axis('off')
fig.suptitle('v4-Kern auf frischen Stand-Serien 2026-06-14 (OHNE GT — Scheibenfotos fehlen noch)',fontsize=13)
fig.tight_layout(); fig.savefig('reports/P9_stand_v4.png',dpi=95)
print('\nPNG: reports/P9_stand_v4.png')
