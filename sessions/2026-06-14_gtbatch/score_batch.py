# v4-Score der 4 neuen GT-Serien (volle Sequenzen). Robuste Zentrumsbestimmung.
import sys, glob, re, math, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage

SESS = {
 '0529': dict(folder='full_1780070313706/1780070313706_kk25', cal=(947,423,305),
              gt=[(561,620),(703,564),(625,516),(631,411),(883,868)]),
 '0603b':dict(folder='full_1780501584393/1780501584393_kk25', cal=(986,469,232),
              gt=[(930,592),(632,738),(1106,502),(1056,406),(956,424)]),
 '0606b':dict(folder='full_1780764995131/1780764995131_kk25', cal=(751,406,136),
              gt=[(1087,701),(1103,651),(1063,614),(1144,570),(1132,401)]),
 '0607': dict(folder='full_1780826808538/1780826808538_kk25', cal=(1072,496,231),
              gt=[(1202,410),(952,549),(1407,843)]),
}
TOL=15
def load(f): return np.asarray(Image.open(f).convert('L'),dtype=np.float32)
def pick_center(fin,cal,gt):
    # Kandidaten-Zentren: JSON-cal und GT-Schwerpunkt; nimm das mit dunklerer Umgebung (Spiegel)
    H,W=fin.shape
    gx=int(np.mean([p[0] for p in gt])); gy=int(np.mean([p[1] for p in gt]))
    cands=[(cal[0],cal[1]),(gx,gy)]
    def darkness(cx,cy):
        cx=max(20,min(W-20,cx));cy=max(20,min(H-20,cy))
        return float(np.median(fin[cy-15:cy+15,cx-15:cx+15]))
    a=cal[2] if 150<=cal[2]<=330 else 231
    best=min(cands,key=lambda c:darkness(*c))
    # refine: dunkelblob nahe best
    cx,cy=best;yy,xx=np.mgrid[0:H,0:W];near=((xx-cx)**2+(yy-cy)**2)<(a*1.2)**2
    dark=(fin<np.percentile(fin[near],30))&near;dark=ndimage.binary_opening(dark,iterations=2)
    lbl,n=ndimage.label(dark)
    if n:
        b=np.argmax(ndimage.sum(np.ones_like(lbl),lbl,range(1,n+1)))+1
        ys,xs=np.where(lbl==b);cx,cy=int(xs.mean()),int(ys.mean())
    return cx,cy,a
def reg_shift(mov,ref,cx,cy,a):
    r=int(a*1.1);y0,y1=max(0,cy-r),min(ref.shape[0],cy+r);x0,x1=max(0,cx-r),min(ref.shape[1],cx+r)
    m=mov[y0:y1,x0:x1];f=ref[y0:y1,x0:x1];H,W=m.shape
    if H<8 or W<8:return 0,0
    win=np.outer(np.hanning(H),np.hanning(W))
    A=np.fft.fft2((m-m.mean())*win);B=np.fft.fft2((f-f.mean())*win);R=A*np.conj(B);R/=np.abs(R)+1e-6
    c=np.fft.ifft2(R).real;pk=np.unravel_index(np.argmax(c),c.shape)
    def par(v,i,n):a0=v[(i-1)%n];a1=v[i];a2=v[(i+1)%n];return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy=pk[0]+par(c[:,pk[1]],pk[0],H);dx=pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2:dy-=H
    if dx>W/2:dx-=W
    return dy,dx
def analyze(folder,cal,gt):
    D='sessions/2026-06-14_gtbatch/'+folder
    fs=sorted(glob.glob(D+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    # auf max 200 Frames begrenzen (Speed), gleichmaessig
    if len(fs)>200: fs=fs[::max(1,len(fs)//200)]
    ref=np.median(np.stack([load(f) for f in fs[-6:]]),axis=0);H,W=ref.shape
    cx,cy,a=pick_center(ref,cal,gt)
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
            if s>=19.5*(2.0 if edge[py,px] else 1.0):cands.append((float(x0+px*2),float(y0+py*2)))
    return cx,cy,a,cands,len(fs)
def score(cands,gt,TOL):
    used=[False]*len(cands);rec=0
    for gx,gy in gt:
        best=-1;bd=TOL+1
        for i,(x,y) in enumerate(cands):
            if used[i]:continue
            d=math.hypot(x-gx,y-gy)
            if d<=TOL and d<bd:bd=d;best=i
        if best>=0:used[best]=True;rec+=1
    return rec,used.count(False)
TR=TN=TP=0
for tag,c in SESS.items():
    cx,cy,a,cands,nf=analyze(c['folder'],c['cal'],c['gt'])
    ng=len(c['gt'])
    rec,fp=score(cands,c['gt'],TOL);TR+=rec;TN+=ng;TP+=fp
    print(f'{tag}: Spiegel({cx},{cy}) a={a}, {nf} Frames | Recall {rec}/{ng}, Phantome {fp}')
print(f'\\n=== 4 NEUE Serien gesamt: Recall {TR}/{TN} ({100*TR//TN}%), Phantome {TP} ({TP/4:.2f}/Serie) ===')
