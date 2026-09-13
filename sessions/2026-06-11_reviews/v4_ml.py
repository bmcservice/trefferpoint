# Baustein 2: Patch-Klassifizierer statt Hand-Gates (Empfehlung aller 3 Reviews)
# Extraktion: Hybrid (Komponenten>=2px + Maxima-Splitting), KEINE weiteren Gates
# -> maximaler Kandidaten-Recall; die Grenze zieht ein LOSO-trainiertes Modell.
import sys, math, glob, re, json
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
sys.path.insert(0, 'sessions/2026-06-11_reviews')
from v4_core import SESS, TOL, load, reg_shift

FEATS = ['det','amp','sharp_pk','sharp_seg','area','round','ra','edge',
         'tfrac','pre_std','post_std','base_b','print_near','bgl']

def analyze5(nm):
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
    prestd=pre.std(0); poststd=post.std(0)
    sharp=amp/(prestd+poststd+1.0)
    cum=np.cumsum(Z,axis=0); k=np.arange(1,N+1)[:,None,None]
    cusum=cum[-1][None]*k/N - cum
    tstar=np.argmax(cusum,axis=0)
    h,w=amp.shape; Yh,Xh=np.mgrid[0:h,0:w]
    rr=np.hypot(x0+Xh*2-cx, y0+Yh*2-cy)/a
    inzone=rr<1.9; edge=(rr>0.85)&(rr<1.08)
    amps=ndimage.gaussian_filter(amp,0.8); bgl=ndimage.median_filter(amps,21); det=amps-bgl
    basemax=ndimage.maximum_filter(base,size=7)   # heller Druck in der Naehe?
    mxf = ndimage.maximum_filter(det, size=3)
    cands=[]
    lbl,n=ndimage.label((det>8)&inzone)
    for kk in range(1,n+1):
        mask = lbl==kk
        if int(mask.sum())<2: continue
        pys,pxs = np.where(mask & (det==mxf))
        order = np.argsort(-det[pys,pxs])
        kept=[]
        for oi in order:
            py,px = pys[oi],pxs[oi]
            if any((py-qy)**2+(px-qx)**2 < 9 for qy,qx in kept): continue
            kept.append((py,px))
        for py,px in kept:
            ys0,ys1 = max(0,py-7),py+8; xs0,xs1 = max(0,px-7),px+8
            seg = mask[ys0:ys1,xs0:xs1] & (det[ys0:ys1,xs0:xs1] > 0.5*det[py,px])
            sa = int(seg.sum())
            if sa<1: continue
            ys,xs = np.where(seg)
            if sa>=3:
                xr=xs-xs.mean(); yr=ys-ys.mean()
                cov=np.cov(np.stack([xr,yr])); ev=np.linalg.eigvalsh(cov)
                rnd = float(ev[0]/ev[1]) if ev[1]>1e-6 else 1.0
            else:
                rnd = 1.0
            cands.append(dict(
                x=float(x0+px*2), y=float(y0+py*2),
                det=float(det[py,px]), amp=float(amp[py,px]),
                sharp_pk=float(sharp[py,px]),
                sharp_seg=float(sharp[ys0:ys1,xs0:xs1][seg].max()),
                area=float(sa), round=rnd, ra=float(rr[py,px]),
                edge=1.0 if edge[py,px] else 0.0,
                tfrac=float(tstar[py,px])/N,
                pre_std=float(prestd[py,px]), post_std=float(poststd[py,px]),
                base_b=float(base[py,px]), print_near=float(basemax[py,px]),
                bgl=float(bgl[py,px]),
            ))
    # Labels: greedy 1:1 GT-Match
    used=set()
    for gx,gy in GT:
        best=None; bd=TOL+1
        for i,c in enumerate(cands):
            if i in used: continue
            d=math.hypot(c['x']-gx,c['y']-gy)
            if d<=TOL and d<bd: bd=d; best=i
        if best is not None: used.add(best)
    for i,c in enumerate(cands): c['label']=1 if i in used else 0
    return dict(N=N, cands=cands, GT=GT)

def eval_sel(res, selidx):
    GT=res['GT']; sel=[res['cands'][i] for i in selidx]
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
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.preprocessing import StandardScaler
    RES={}
    for nm in SESS:
        print(f'... {nm}',flush=True)
        RES[nm]=analyze5(nm)
        nc=len(RES[nm]['cands']); npos=sum(c['label'] for c in RES[nm]['cands'])
        print(f'    {nc} Kandidaten, davon {npos} echte (Decke {npos}/{len(RES[nm]["GT"])})',flush=True)
    json.dump(RES, open('sessions/2026-06-11_reviews/v4_ml_cands.json','w'))
    ceil=sum(sum(c['label'] for c in RES[nm]['cands']) for nm in RES)
    print(f'\nExtraktions-Decke: {ceil}/28 erreichbar')
    def XY(names):
        X=[]; Y=[]; IDX=[]
        for nm in names:
            for i,c in enumerate(RES[nm]['cands']):
                X.append([c[f] for f in FEATS]); Y.append(c['label']); IDX.append((nm,i))
        return np.array(X), np.array(Y), IDX
    for mklass,tag in [(lambda: LogisticRegression(max_iter=2000,class_weight='balanced'),'LogReg'),
                       (lambda: RandomForestClassifier(n_estimators=400,max_depth=4,class_weight='balanced',random_state=7),'RF d4')]:
        ar=an=af=0; per={}
        for held in RES:
            tr=[x for x in RES if x!=held]
            Xtr,Ytr,_=XY(tr)
            sc=StandardScaler().fit(Xtr)
            mdl=mklass().fit(sc.transform(Xtr),Ytr)
            # Entscheidungs-Schwelle auf Train waehlen (Utility r-0.5f via Train-Sessions)
            best_t=0.5; best_u=-1e9
            for t in np.arange(0.2,0.95,0.05):
                u=0
                for nm in tr:
                    Xs=np.array([[c[f] for f in FEATS] for c in RES[nm]['cands']])
                    p=mdl.predict_proba(sc.transform(Xs))[:,1]
                    sel=[i for i in range(len(p)) if p[i]>=t]
                    r,n,f=eval_sel(RES[nm],sel); u+=r-0.5*f
                if u>best_u: best_u=u; best_t=t
            Xs=np.array([[c[f] for f in FEATS] for c in RES[held]['cands']])
            p=mdl.predict_proba(sc.transform(Xs))[:,1]
            sel=[i for i in range(len(p)) if p[i]>=best_t]
            r,n,f=eval_sel(RES[held],sel)
            ar+=r;an+=n;af+=f; per[held]=(best_t,r,n,f)
        print(f'\n=== {tag} LOSO: Recall {ar}/{an} ({100*ar//an}%), Phantome {af} ({af/6:.2f}/Serie) ===')
        for nm,(bt,r,n,f) in per.items():
            print(f'   {nm:5s} p>={bt:.2f}: {r}/{n} +{f}P')
    # Feature-Bedeutung (RF auf allen Daten, nur zur Interpretation)
    Xa,Ya,_=XY(list(RES))
    rf=RandomForestClassifier(n_estimators=400,max_depth=4,class_weight='balanced',random_state=7).fit(
        StandardScaler().fit_transform(Xa),Ya)
    imp=sorted(zip(FEATS,rf.feature_importances_),key=lambda t:-t[1])
    print('\nFeature-Wichtigkeit (RF, alle Daten, nur Interpretation):')
    for f,v in imp: print(f'   {f:10s} {v:.3f}')
    print('\nVergleich: v1-Gates=20/28+4P | vor v4: 15/28+16P')
