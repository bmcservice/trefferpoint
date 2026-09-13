# Adaptiver Schaerfe-Schwellwert: erst Datenlage (Trenn-Struktur) ueber ALLE 8 Sessions
# (6 Korpus + 2 Stand), dann Normierung waehlen die Korpus UND Stand traegt.
import sys, glob, re, math, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage

# (folder_or_path, (cx,cy,a), nmax, GT)
SESS = {
 'A':   ('sessions/corpus/1780821347685_kk25/1780821347685_kk25',(1214,506,231),None,[(1048,762),(1018,594),(1019,404),(1320,501),(1330,506)]),
 'B':   ('sessions/corpus/1780825379534_kk25/1780825379534_kk25',(1072,496,231),None,[(1007,520),(1070,439),(1158,466),(1065,383),(1262,264)]),
 '0603':('sessions/2026-06-03_etf150_stand/seq_1546/raw',(986,469,232),None,[(630,738),(960,426),(1060,408),(1108,506),(936,592)]),
 '0606':('sessions/2026-06-06_stand/1780763553002_kk25',(1121,496,230),None,[(1020,704),(1095,556),(1005,415)]),
 '1528':('sessions/corpus_gt/1780500498560_kk25',(984,499,232),None,[(1130,716),(798,659),(948,512),(1011,540),(1100,546)]),
 '0531':('sessions/corpus_gt/1780216449975_kk25',(1048,498,310),None,[(613,477),(820,669),(845,547),(927,552),(846,419)]),
 'S0900':('sessions/2026-06-14_stand/1781427643399_kk25',(1052,463,231),None,[(956,583),(884,529),(947,410),(986,419),(1035,317)]),
 'S0906':('sessions/2026-06-14_stand/1781428002738_kk25',(1055,442,231),262,[(1061,436),(1049,415),(1101,408),(967,370),(1060,263)]),
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
def extract(path,cal,nmax,gt):
    fs=sorted(glob.glob(path+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    if nmax:fs=fs[:nmax]
    ref=np.median(np.stack([load(f) for f in fs[-6:]]),axis=0);H,W=ref.shape
    cx,cy,a=cal;cx,cy=refine_center(ref,cx,cy,a);cx,cy=int(cx),int(cy)
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
    # Session-Rauschstatistik der SCHAERFE im Inzone-Hintergrund (robust)
    sz=sharp[inzone]
    noise_med=float(np.median(sz)); noise_mad=float(np.median(np.abs(sz-noise_med))+1e-6)
    noise_p90=float(np.percentile(sz,90))
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
            cands.append(dict(x=float(x0+px*2),y=float(y0+py*2),sharp=s,
                              det=float(det[py,px]),amp=float(amp[py,px]),edge=bool(edge[py,px])))
    # GT-Label (greedy)
    used=set()
    for gx,gy in gt:
        best=None;bd=TOL+1
        for i,c in enumerate(cands):
            if i in used:continue
            d=math.hypot(c['x']-gx,c['y']-gy)
            if d<=TOL and d<bd:bd=d;best=i
        if best is not None:used.add(best)
    for i,c in enumerate(cands):c['hit']=i in used
    return dict(cands=cands,noise_med=noise_med,noise_mad=noise_mad,noise_p90=noise_p90,ngt=len(gt))

DATA={}
for tag,(p,cal,nm,gt) in SESS.items():
    print(f'... {tag}',flush=True); DATA[tag]=extract(p,cal,nm,gt)
json.dump(DATA,open('sessions/2026-06-11_reviews/v4_adaptive_data.json','w'))
print('\n=== Trenn-Struktur je Session: Schaerfe der GT-Treffer vs Rausch-Boden ===')
print(f'{"Sess":6} {"noise_med":>9} {"noise_p90":>9} | {"Treffer-Schaerfe (sortiert)":40} | {"Phantom-Top3":>16}')
for tag,d in DATA.items():
    hits=sorted([c['sharp'] for c in d['cands'] if c['hit']],reverse=True)
    ph=sorted([c['sharp'] for c in d['cands'] if not c['hit']],reverse=True)[:3]
    print(f'{tag:6} {d["noise_med"]:9.2f} {d["noise_p90"]:9.2f} | '+
          ' '.join(f'{h:.0f}' for h in hits).ljust(40)+f' | '+' '.join(f'{p:.0f}' for p in ph))
print('\n=== dieselben Treffer, aber NORMIERT (sharp/noise_p90) ===')
for tag,d in DATA.items():
    hits=sorted([c['sharp']/d['noise_p90'] for c in d['cands'] if c['hit']],reverse=True)
    ph=sorted([c['sharp']/d['noise_p90'] for c in d['cands'] if not c['hit']],reverse=True)[:3]
    print(f'{tag:6} | Treffer '+' '.join(f'{h:.2f}' for h in hits).ljust(34)+' | Phantom '+' '.join(f'{p:.2f}' for p in ph))
