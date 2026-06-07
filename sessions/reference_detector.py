#!/usr/bin/env python3
"""
TrefferPoint REFERENZ-DETEKTOR (Golden Baseline) — Stand 2026-06-06
Validiert gegen 2 Ground-Truth-Sessions (Pappe-geprüft).

Architektur (was über 2 Sessions belegt funktioniert):
  - EMA-Baseline pro Ort der Center-Surround-Antwort (absorbiert langsame Ziffern-Drift)
  - Zweizonen-Polarität (Loch hell im schwarzen Spiegel, dunkel im weißen Bereich)
  - Stör-Gate (Frame mit globalem Sprung = Bump/Glitch -> übersprungen)
  - Form-Filter (klein + rund = .22-Loch, nicht Glitch/Kante)
  - Persistenz K Frames

BELEGTE GRENZE: schwache Spiegel-Löcher liegen im selben Merkmalsraum wie transiente
Phantome (Amplitude/Persistenz/Form überlappen). Frame-Rate (0,7-2 fps getestet) ändert
das nicht. => sauber detektierbar sind KLARE Treffer (weiße Zone + tiefe/starke Treffer)
bei 0 Phantomen; schwache Treffer sind akquisitions-SNR-limitiert.

FESTER PARAMETERSATZ (nicht an GT getunt; Schwellen aus Ziffern-Negativkontrolle):
"""
import sys; sys.stdout.reconfigure(encoding='utf-8')
from PIL import Image
import numpy as np, glob, re
from scipy import ndimage

P = dict(SC=2, ALPHA=0.05, KPERS=4, TOL=24, T=10.0, GATE=3.5, AREA_MIN=3, AREA_MAX=60, ROUND_MIN=0.40)

def detect(D, CAL, params=P):
    fs=sorted(glob.glob(D+'/df_*.jpg'), key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    SC=params['SC']
    def load(f):
        im=Image.open(f).convert('L'); return np.asarray(im.resize((im.width//SC,im.height//SC)),dtype=np.float32)
    def cs(im): return ndimage.uniform_filter(im,3)-ndimage.uniform_filter(im,11)
    cx,cy,a=CAL['cx']//SC,CAL['cy']//SC,CAL['a']//SC
    H,W=load(fs[0]).shape; yy,xx=np.mgrid[0:H,0:W]; r=np.sqrt((xx-cx)**2+(yy-cy)**2)
    insp=r<a*0.93; outp=(r>=a*0.93)&(r<a*2.4); spg=r<a*1.05
    def tz(d):
        s=np.zeros((H,W),np.float32); s[insp]=np.clip(d[insp],0,None); s[outp]=np.clip(-d[outp],0,None); return s
    def rnd(m):
        ys,xs=np.where(m)
        if len(xs)<4: return 0
        xs=xs-xs.mean();ys=ys-ys.mean();cov=np.cov(np.stack([xs,ys]));ev=np.linalg.eigvalsh(cov)
        return float(ev[0]/ev[1]) if ev[1]>1e-6 else 0
    base=None; prev=None; pend={}; comm={}; gated=0
    for f in fs:
        im=load(f); c=cs(im)
        if base is None: base=c.copy(); prev=im; continue
        if float(np.mean(np.abs(im[spg]-prev[spg])))>params['GATE']:
            gated+=1; prev=im; base=(1-params['ALPHA'])*base+params['ALPHA']*c; continue
        prev=im
        sm=ndimage.gaussian_filter(tz(c-base),1.0); seen=set()
        if sm.max()>=params['T']:
            lbl,n=ndimage.label(sm>params['T'])
            for k in range(1,n+1):
                m=lbl==k; ar=int(m.sum())
                if ar<params['AREA_MIN'] or ar>params['AREA_MAX']: continue
                if rnd(m)<params['ROUND_MIN']: continue
                ys,xs=np.where(m); xc,yc=int(xs.mean()),int(ys.mean()); key=(xc//4,yc//4); seen.add(key)
                pend[key]=pend.get(key,0)+1
                if pend[key]>=params['KPERS'] and key not in comm: comm[key]=(xc,yc)
        for key in list(pend):
            if key not in seen: pend[key]=max(0,pend[key]-1)
        base=(1-params['ALPHA'])*base+params['ALPHA']*c
    det=[(x*SC,y*SC) for x,y in comm.values()]; ded=[]
    for x,y in det:
        if all(np.hypot(x-px,y-py)>params['TOL'] for px,py in ded): ded.append((x,y))
    return ded, gated

def score(det, GT, tol=P['TOL']):
    hit=[min((np.hypot(x-gx,y-gy) for x,y in det),default=999)<=tol for gx,gy,_ in GT]
    fp=[(x,y) for x,y in det if all(np.hypot(x-gx,y-gy)>tol for gx,gy,_ in GT)]
    return sum(hit),len(fp),hit

SESSIONS=[
 ('06-03','sessions/2026-06-03_etf150_stand/seq_1546/raw',dict(cx=986,cy=469,a=232),
  [(630,738,'Ring3-weiß'),(960,426,'Spiegel'),(1060,408,'Spiegel'),(1108,506,'Spiegel'),(936,592,'Ring8@7U')]),
 ('06-06','sessions/2026-06-06_stand/1780763553002_kk25',dict(cx=1121,cy=496,a=230),
  [(1020,704,'6@7U-weiß'),(1095,556,'9@7U'),(1005,415,'8@10U')]),
]
if __name__=='__main__':
    print('REFERENZ-DETEKTOR Scorecard (FESTER Parametersatz, 0-Phantom-Betriebspunkt):')
    print(f'  Params: {P}')
    for nm,D,CAL,GT in SESSIONS:
        det,gated=detect(D,CAL); rec,fp,hit=score(det,GT)
        marks=' '.join(('%s:%s'%(n,'OK' if h else '-')) for (_,_,n),h in zip(GT,hit))
        print('  %s: Recall %d/%d | Phantome %d | Störframes %d | %s'%(nm,rec,len(GT),fp,gated,marks))
