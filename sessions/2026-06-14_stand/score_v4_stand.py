# Finaler Score + Miss-Diagnose der Stand-Serien gegen GT (5 Schuss/Serie)
import sys, glob, re, math, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

CFG = {
 '0900': dict(folder='1781427643399_kk25', cal=(1052,463,231), nmax=None,
              gt=[(956,583),(884,529),(947,410),(986,419),(1035,317)]),
 '0906': dict(folder='1781428002738_kk25', cal=(1055,442,231), nmax=262,   # nur sauberes Fenster
              gt=[(1061,436),(1049,415),(1101,408),(967,370),(1060,263)]),
}
TOL=15
def load(f): return np.asarray(Image.open(f).convert('L'),dtype=np.float32)
def refine_center(fin,cx,cy,a):
    H,W=fin.shape;yy,xx=np.mgrid[0:H,0:W];near=((xx-cx)**2+(yy-cy)**2)<(a*1.3)**2
    dark=(fin<np.percentile(fin[near],35))&near;dark=ndimage.binary_opening(dark,iterations=2)
    lbl,n=ndimage.label(dark)
    if n==0:return cx,cy
    best=np.argmax(ndimage.sum(np.ones_like(lbl),lbl,range(1,n+1)))+1
    ys,xs=np.where(lbl==best);return float(xs.mean()),float(ys.mean())
def reg_shift(mov,ref,cx,cy,a):
    r=int(a*1.1);y0,y1=max(0,cy-r),min(ref.shape[0],cy+r);x0,x1=max(0,cx-r),min(ref.shape[1],cx+r)
    m=mov[y0:y1,x0:x1];f=ref[y0:y1,x0:x1];H,W=m.shape;win=np.outer(np.hanning(H),np.hanning(W))
    A=np.fft.fft2((m-m.mean())*win);B=np.fft.fft2((f-f.mean())*win);R=A*np.conj(B);R/=np.abs(R)+1e-6
    c=np.fft.ifft2(R).real;pk=np.unravel_index(np.argmax(c),c.shape)
    def par(v,i,n):a0=v[(i-1)%n];a1=v[i];a2=v[(i+1)%n];return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy=pk[0]+par(c[:,pk[1]],pk[0],H);dx=pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2:dy-=H
    if dx>W/2:dx-=W
    return dy,dx
def analyze(folder,cal,nmax,use_refine):
    D='sessions/2026-06-14_stand/'+folder
    fs=sorted(glob.glob(D+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    if nmax:fs=fs[:nmax]
    ref=np.median(np.stack([load(f) for f in fs[-6:]]),axis=0);H,W=ref.shape
    cx,cy,a=cal
    if use_refine:cx,cy=refine_center(ref,cx,cy,a)
    cx,cy=int(cx),int(cy)
    M=30;x0,x1=max(0,cx-int(2*a)-M),min(W,cx+int(2*a)+M);y0,y1=max(0,cy-int(2*a)-M),min(H,cy+int(2*a)+M)
    yy,xx=np.mgrid[0:H,0:W];sp=((xx-cx)**2+(yy-cy)**2)<(a*0.9)**2;spmed=np.median(ref[sp])
    stk=[]
    for f in fs:
        img=load(f);dy,dx=reg_shift(img,ref,cx,cy,a);roi=img[y0:y1,x0:x1]
        roi=ndimage.shift(roi,(-dy,-dx),order=1,mode='nearest');roi+=spmed-np.median(roi[sp[y0:y1,x0:x1]]);stk.append(roi[::2,::2])
    S=np.stack(stk);base=np.median(S[:6],axis=0);R=S-base;zsign=np.where(base<100,1.0,-1.0);Z=R*zsign
    amp=Z[-6:].mean(0)-Z[:6].mean(0);sharp=amp/(Z[:6].std(0)+Z[-6:].std(0)+1.0)
    h,w=amp.shape;Yh,Xh=np.mgrid[0:h,0:w];rr=np.hypot(x0+Xh*2-cx,y0+Yh*2-cy)/a;inzone=rr<1.9;edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8);bgl=ndimage.median_filter(amps,21);det=amps-bgl;mxf=ndimage.maximum_filter(det,size=3)
    cands=[];lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        mask=lbl==kk;ar=int(mask.sum())
        if ar<2 or ar>120:continue
        pys,pxs=np.where(mask&(det==mxf));order=np.argsort(-det[pys,pxs]);kept=[]
        for oi in order:
            py,px=pys[oi],pxs[oi]
            if any((py-qy)**2+(px-qx)**2<9 for qy,qx in kept):continue
            kept.append((py,px))
        for py,px in kept:
            s=float(sharp[max(0,py-1):py+2,max(0,px-1):px+2].max())
            if s>=19.5*(2.0 if edge[py,px] else 1.0):cands.append((float(x0+px*2),float(y0+py*2),round(s)))
    return dict(ref=ref,cx=cx,cy=cy,a=a,cands=cands,amp=amp,sharp=sharp,det=det,x0=x0,y0=y0,edge=edge,rr=rr)
def score(cands,gt,TOL):
    cc=[(x,y) for x,y,s in cands];used=[False]*len(cc);rec=[];miss=[]
    for gx,gy in gt:
        best=-1;bd=TOL+1
        for i,(x,y) in enumerate(cc):
            if used[i]:continue
            d=math.hypot(x-gx,y-gy)
            if d<=TOL and d<bd:bd=d;best=i
        if best>=0:used[best]=True;rec.append(((gx,gy),cc[best]))
        else:miss.append((gx,gy))
    fp=[cc[i] for i in range(len(cc)) if not used[i]]
    return rec,miss,fp

fig,axs=plt.subplots(2,2,figsize=(16,14))
TR=0; TN=0; TP=0
for col,(tag,c) in enumerate(CFG.items()):
    # Vergleich refine an/aus
    for use_ref in [True,False]:
        r=analyze(c['folder'],c['cal'],c['nmax'],use_ref)
        rec,miss,fp=score(r['cands'],c['gt'],TOL)
        print(f"{tag} refine={use_ref}: Recall {len(rec)}/5, Phantome {len(fp)}  Spiegel({r['cx']},{r['cy']})")
    # Endwertung mit refine=False (cal-direkt, robuster)
    r=analyze(c['folder'],c['cal'],c['nmax'],False)
    rec,miss,fp=score(r['cands'],c['gt'],TOL); TR+=len(rec);TN+=5;TP+=len(fp)
    # Miss-Diagnose: Signal an verpasster GT-Position?
    print(f'  -- {tag} Miss-Diagnose (cal-direkt) --')
    for gx,gy in miss:
        ix=int((gx-r['x0'])/2);iy=int((gy-r['y0'])/2)
        sl=(slice(max(0,iy-3),iy+4),slice(max(0,ix-3),ix+4))
        a_=float(r['amp'][sl].max());s_=float(r['sharp'][sl].max());d_=float(r['det'][sl].max())
        ra_=float(r['rr'][iy,ix])
        why='SIGNAL DA, Schaerfe<19.5' if (a_>8 and s_<19.5) else ('kein Signal (amp<8)' if a_<8 else 'Extraktion')
        print(f'     GT({gx},{gy}) r/a={ra_:.2f}: amp {a_:.0f} schaerfe {s_:.1f} det {d_:.0f} -> {why}')
    # Plot oben: Schlussbild + GT/v4 ; unten: amp-Karte
    ax=axs[0,col];ax.imshow(r['ref'],cmap='gray',vmin=0,vmax=255)
    ax.add_patch(plt.Circle((r['cx'],r['cy']),r['a'],fill=False,color='#48f',lw=1,ls=':'))
    for (gx,gy),(vx,vy) in rec: ax.add_patch(plt.Circle((gx,gy),16,fill=False,color='#2f2',lw=2.5))
    for gx,gy in miss: ax.add_patch(plt.Circle((gx,gy),16,fill=False,color='#f70',lw=2.5))
    for vx,vy in fp: ax.add_patch(plt.Circle((vx,vy),11,fill=False,color='cyan',lw=2,ls='--'))
    ax.set_title(f'{tag}: Recall {len(rec)}/5, {len(fp)} Phantom  (gruen=Treffer, orange=verpasst, cyan=Phantom)',fontsize=11)
    ax.set_xlim(r['x0'],r['x0']+ (r['amp'].shape[1]*2)); ax.set_ylim(r['y0']+(r['amp'].shape[0]*2),r['y0']); ax.axis('off')
    ax=axs[1,col];ax.imshow(r['amp'],cmap='coolwarm',vmin=-40,vmax=40)
    ax.set_title(f'{tag} zonen-signierte Sprungkarte (rot=Treffer-Signal)',fontsize=11);ax.axis('off')
print(f'\\n=== GESAMT (cal-direkt, TOL={TOL}): Recall {TR}/{TN} ({100*TR//TN}%), Phantome {TP} ({TP/2:.1f}/Serie) ===')
fig.suptitle(f'v4-Kern auf frischen Stand-Daten 2026-06-14 (GT=5 Schuss/Serie) — Recall {TR}/{TN}, {TP} Phantome',fontsize=13)
fig.tight_layout();fig.savefig('reports/P10_stand_final.png',dpi=95)
print('PNG: reports/P10_stand_final.png')
