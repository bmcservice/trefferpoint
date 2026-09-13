# Klassifizierer auf erweitertem Korpus (12 GT-Serien). Reiche Features, robuste Zentren.
# Vergleich fester Schwellwert vs LogReg/RF, LOSO. Der "Zahltag" fuer die Datensammlung.
import sys, glob, re, math, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage

G='sessions/2026-06-14_gtbatch/'
SESS = {
 # Korpus (6)
 'A':   ('sessions/corpus/1780821347685_kk25/1780821347685_kk25',(1214,506,231),None,[(1048,762),(1018,594),(1019,404),(1320,501),(1330,506)]),
 'B':   ('sessions/corpus/1780825379534_kk25/1780825379534_kk25',(1072,496,231),None,[(1007,520),(1070,439),(1158,466),(1065,383),(1262,264)]),
 '0603':('sessions/2026-06-03_etf150_stand/seq_1546/raw',(986,469,232),None,[(630,738),(960,426),(1060,408),(1108,506),(936,592)]),
 '0606':('sessions/2026-06-06_stand/1780763553002_kk25',(1121,496,230),None,[(1020,704),(1095,556),(1005,415)]),
 '1528':('sessions/corpus_gt/1780500498560_kk25',(984,499,232),None,[(1130,716),(798,659),(948,512),(1011,540),(1100,546)]),
 '0531':('sessions/corpus_gt/1780216449975_kk25',(1048,498,310),None,[(613,477),(820,669),(845,547),(927,552),(846,419)]),
 # Stand (2)
 'S0900':('sessions/2026-06-14_stand/1781427643399_kk25',(1052,463,231),None,[(956,583),(884,529),(947,410),(986,419),(1035,317)]),
 'S0906':('sessions/2026-06-14_stand/1781428002738_kk25',(1055,442,231),262,[(1061,436),(1049,415),(1101,408),(967,370),(1060,263)]),
 # Batch (4)
 '0529': (G+'full_1780070313706/1780070313706_kk25',(947,423,305),None,[(561,620),(703,564),(625,516),(631,411),(883,868)]),
 '0603b':(G+'full_1780501584393/1780501584393_kk25',(986,469,232),None,[(930,592),(632,738),(1106,502),(1056,406),(956,424)]),
 '0606b':(G+'full_1780764995131/1780764995131_kk25',(751,406,136),None,[(1087,701),(1103,651),(1063,614),(1144,570),(1132,401)]),
 '0607': (G+'full_1780826808538/1780826808538_kk25',(1072,496,231),None,[(1202,410),(952,549),(1407,843)]),
 # GK25 (9mm) — Zone aus GT-Streuung (cal-a unzuverlaessig)
 'GK0605':(G+'gk_1780675149258/1780675149258_gk25',(684,336,141),None,[(536,671),(873,358),(992,243)]),
 'GK0606':(G+'gk_1780765823267/1780765823267_gk25',(751,406,136),None,[(1012,810),(981,707),(912,611),(1065,422)]),
}
GK={'GK0605','GK0606'}
TOL=15
FEATS=['det','amp','sharp','area','round','ra','edge','tfrac','pre_std','post_std','base_b','print_near']
def load(f): return np.asarray(Image.open(f).convert('L'),dtype=np.float32)
def pick_center(fin,cal,gt):
    H,W=fin.shape;gx=int(np.mean([p[0] for p in gt]));gy=int(np.mean([p[1] for p in gt]))
    a=cal[2] if 150<=cal[2]<=330 else 231
    # Zone muss GT abdecken (v.a. GK25 mit weiter Streuung): a >= 1.15*max GT-Radius
    maxr=max(math.hypot(p[0]-gx,p[1]-gy) for p in gt) if len(gt)>1 else 0
    a=int(min(360,max(a,1.15*maxr)))
    def dark(cx,cy):cx=max(20,min(W-20,cx));cy=max(20,min(H-20,cy));return float(np.median(fin[cy-15:cy+15,cx-15:cx+15]))
    cx,cy=min([(cal[0],cal[1]),(gx,gy)],key=lambda c:dark(*c))
    yy,xx=np.mgrid[0:H,0:W];near=((xx-cx)**2+(yy-cy)**2)<(a*1.2)**2
    d=(fin<np.percentile(fin[near],30))&near;d=ndimage.binary_opening(d,iterations=2)
    lbl,n=ndimage.label(d)
    if n:
        b=np.argmax(ndimage.sum(np.ones_like(lbl),lbl,range(1,n+1)))+1
        ys,xs=np.where(lbl==b);cx,cy=int(xs.mean()),int(ys.mean())
    return cx,cy,a
def reg_shift(mov,ref,cx,cy,a):
    r=int(a*1.1);y0,y1=max(0,cy-r),min(ref.shape[0],cy+r);x0,x1=max(0,cx-r),min(ref.shape[1],cx+r)
    m=mov[y0:y1,x0:x1];f=ref[y0:y1,x0:x1];H,W=m.shape
    if H<8 or W<8:return 0,0
    win=np.outer(np.hanning(H),np.hanning(W))
    A=np.fft.fft2((m-m.mean())*win);Bf=np.fft.fft2((f-f.mean())*win);R=A*np.conj(Bf);R/=np.abs(R)+1e-6
    c=np.fft.ifft2(R).real;pk=np.unravel_index(np.argmax(c),c.shape)
    def par(v,i,n):a0=v[(i-1)%n];a1=v[i];a2=v[(i+1)%n];return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy=pk[0]+par(c[:,pk[1]],pk[0],H);dx=pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2:dy-=H
    if dx>W/2:dx-=W
    return dy,dx
def extract(path,cal,nmax,gt):
    fs=sorted(glob.glob(path+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    if nmax:fs=fs[:nmax]
    if len(fs)>200:fs=fs[::max(1,len(fs)//200)]
    ref=np.median(np.stack([load(f) for f in fs[-6:]]),axis=0);H,W=ref.shape
    cx,cy,a=pick_center(ref,cal,gt)
    M=30;x0,x1=max(0,cx-int(2*a)-M),min(W,cx+int(2*a)+M);y0,y1=max(0,cy-int(2*a)-M),min(H,cy+int(2*a)+M)
    yy,xx=np.mgrid[0:H,0:W];sp=((xx-cx)**2+(yy-cy)**2)<(a*0.9)**2;spmed=np.median(ref[sp])
    stk=[]
    for f in fs:
        img=load(f);dy,dx=reg_shift(img,ref,cx,cy,a);roi=img[y0:y1,x0:x1]
        roi=ndimage.shift(roi,(-dy,-dx),order=1,mode='nearest');roi+=spmed-np.median(roi[sp[y0:y1,x0:x1]]);stk.append(roi[::2,::2])
    S=np.stack(stk);N=len(fs);base=np.median(S[:6],axis=0);R=S-base;zsign=np.where(base<100,1.0,-1.0);Z=R*zsign
    amp=Z[-6:].mean(0)-Z[:6].mean(0);prestd=Z[:6].std(0);poststd=Z[-6:].std(0);sharp=amp/(prestd+poststd+1.0)
    cum=np.cumsum(Z,axis=0);k=np.arange(1,N+1)[:,None,None];cusum=cum[-1][None]*k/N-cum;tstar=np.argmax(cusum,axis=0)
    h,w=amp.shape;Yh,Xh=np.mgrid[0:h,0:w];rr=np.hypot(x0+Xh*2-cx,y0+Yh*2-cy)/a;inzone=rr<1.9;edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8);bgl=ndimage.median_filter(amps,21);det=amps-bgl;mxf=ndimage.maximum_filter(det,size=3)
    basemax=ndimage.maximum_filter(base,size=7)
    cands=[];lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        mask=lbl==kk;ar=int(mask.sum())
        if ar<2:continue
        pys,pxs=np.where(mask&(det==mxf));order=np.argsort(-det[pys,pxs]);kept=[]
        for oi in order:
            py,px=pys[oi],pxs[oi]
            if any((py-qy)**2+(px-qx)**2<9 for qy,qx in kept):continue
            kept.append((py,px))
        for py,px in kept:
            s=float(sharp[max(0,py-1):py+2,max(0,px-1):px+2].max())
            cands.append(dict(x=float(x0+px*2),y=float(y0+py*2),det=float(det[py,px]),amp=float(amp[py,px]),
                sharp=s,area=float(ar),round=0.0,ra=float(rr[py,px]),edge=1.0 if edge[py,px] else 0.0,
                tfrac=float(tstar[py,px])/N,pre_std=float(prestd[py,px]),post_std=float(poststd[py,px]),
                base_b=float(base[py,px]),print_near=float(basemax[py,px])))
    used=set()
    for gx,gy in gt:
        best=None;bd=TOL+1
        for i,c in enumerate(cands):
            if i in used:continue
            d=math.hypot(c['x']-gx,c['y']-gy)
            if d<=TOL and d<bd:bd=d;best=i
        if best is not None:used.add(best)
    for i,c in enumerate(cands):c['hit']=1 if i in used else 0
    return cands,len(gt)

DATA={}
for tag,(p,cal,nm,gt) in SESS.items():
    print('...',tag,flush=True);DATA[tag]=dict(cands=extract(p,cal,nm,gt)[0],ngt=len(gt))
json.dump(DATA,open(G+'clf_expanded_data.json','w'))
ceil=sum(sum(c['hit'] for c in DATA[t]['cands']) for t in DATA);tot=sum(DATA[t]['ngt'] for t in DATA)
print(f'\\nExtraktions-Decke: {ceil}/{tot} Loecher erreichbar')
def evals(tag,selidx):
    gtn=DATA[tag]['ngt'];cc=DATA[tag]['cands']
    sel=[cc[i] for i in selidx];used=[False]*len(sel);rec=0
    # nur hit-Kandidaten zaehlen als recall (1:1 via Label)
    rec=sum(c['hit'] for c in sel);fp=sum(1-c['hit'] for c in sel)
    return min(rec,gtn),gtn,fp
def score_fixed(tag,T):
    idx=[i for i,c in enumerate(DATA[tag]['cands']) if c['sharp']>=T*(2.0 if c['edge'] else 1.0)]
    return evals(tag,idx)
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
def XY(tags):
    X=[];Y=[]
    for t in tags:
        for c in DATA[t]['cands']:X.append([c[f] for f in FEATS]);Y.append(c['hit'])
    return np.array(X),np.array(Y)
def run_loso(S,titel):
    print(f'\\n======== {titel} ({len(S)} Serien) ========')
    ar=an=af=0
    for held in S:
        tr=[x for x in S if x!=held]
        bt=max([round(x*0.5,1) for x in range(16,50)],key=lambda T:sum(score_fixed(s,T)[0] for s in tr)-sum(score_fixed(s,T)[2] for s in tr))
        r,n,f=score_fixed(held,bt);ar+=r;an+=n;af+=f
    print(f'  FEST (Schaerfe):  Recall {ar}/{an} ({100*ar//an}%), Phantome {af} ({af/len(S):.2f}/Serie)')
    for mk,tag in [(lambda:LogisticRegression(max_iter=3000,class_weight='balanced'),'LogReg'),
                   (lambda:RandomForestClassifier(n_estimators=500,max_depth=4,class_weight='balanced',random_state=7),'RF')]:
        ar=an=af=0
        for held in S:
            tr=[x for x in S if x!=held];Xtr,Ytr=XY(tr);sc=StandardScaler().fit(Xtr);mdl=mk().fit(sc.transform(Xtr),Ytr)
            bt=0.5;bu=-1e9
            for t in np.arange(0.3,0.95,0.05):
                u=0
                for s in tr:
                    Xs=np.array([[c[f] for f in FEATS] for c in DATA[s]['cands']])
                    if len(Xs)==0:continue
                    p=mdl.predict_proba(sc.transform(Xs))[:,1];idx=[i for i in range(len(p)) if p[i]>=t]
                    rr_,_,ff_=evals(s,idx);u+=rr_-ff_
                if u>bu:bu=u;bt=t
            Xs=np.array([[c[f] for f in FEATS] for c in DATA[held]['cands']])
            p=mdl.predict_proba(sc.transform(Xs))[:,1] if len(Xs) else np.array([])
            idx=[i for i in range(len(p)) if p[i]>=bt];r,n,f=evals(held,idx);ar+=r;an+=n;af+=f
        print(f'  {tag:7}:         Recall {ar}/{an} ({100*ar//an}%), Phantome {af} ({af/len(S):.2f}/Serie)')
KK=[s for s in DATA if s not in GK]
run_loso(KK,'NUR KK25 (.22, Benchmark)')
run_loso(list(DATA),'KK25 + GK25 gemischt')
