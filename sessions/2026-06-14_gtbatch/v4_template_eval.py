# Loch-Appearance-Feature: max. NCC des Kandidaten-Patches mit echten Loch-Patches
# ANDERER Sessions (LOSO-sicher). Vergleich RF ohne/mit Feature, Fusion aktiv,
# nur valide Serien.
import sys, json, math
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler

D=json.load(open('sessions/2026-06-14_gtbatch/v4_patches_data.json'))
KK=list(D.keys())   # enthaelt nur die 10 validen
BASE=['det','amp','sharp','area','ra','edge','tfrac','pre_std','post_std','base_b','print_near']

def fuse(cands,R=8):
    out=[]
    for c in sorted(cands,key=lambda c:-c['det']):
        hitm=None
        for o in out:
            if math.hypot(c['x']-o['x'],c['y']-o['y'])<R: hitm=o; break
        if hitm is None: out.append(c)
        else: hitm['hit']=max(hitm['hit'],c['hit'])
    return out
DD={s:dict(cands=fuse(D[s]['cands']),ngt=D[s]['ngt']) for s in KK}

# Template-Bank: Loch-Patches pro Session
bank={s:np.array([c['patch'] for c in DD[s]['cands'] if c['hit']],dtype=np.float32) for s in KK}
# NCC-Feature je Kandidat (nur fremde Sessions)
for s in KK:
    other=np.vstack([bank[t] for t in KK if t!=s and len(bank[t])])
    for c in DD[s]['cands']:
        p=np.array(c['patch'],dtype=np.float32)
        c['ncc']=float(np.max(other@p)) if len(other) else 0.0

# Wie gut trennt NCC allein?
h=[c['ncc'] for s in KK for c in DD[s]['cands'] if c['hit']]
p=[c['ncc'] for s in KK for c in DD[s]['cands'] if not c['hit']]
print(f'NCC allein: Loecher median {np.median(h):.3f} (q25 {np.percentile(h,25):.3f}) | Phantome median {np.median(p):.3f} (q75 {np.percentile(p,75):.3f})')

def run(FEATS,tag):
    def XY(tags):
        X=[];Y=[]
        for t in tags:
            for c in DD[t]['cands']:X.append([c[f] for f in FEATS]);Y.append(c['hit'])
        return np.array(X),np.array(Y)
    P={}
    for held in KK:
        tr=[x for x in KK if x!=held];Xtr,Ytr=XY(tr);sc=StandardScaler().fit(Xtr)
        m=RandomForestClassifier(n_estimators=500,max_depth=4,class_weight='balanced',random_state=7).fit(sc.transform(Xtr),Ytr)
        Xs=np.array([[c[f] for f in FEATS] for c in DD[held]['cands']])
        P[held]=m.predict_proba(sc.transform(Xs))[:,1] if len(Xs) else np.array([])
    print(f'--- {tag} ---')
    for t in [0.6,0.7,0.8,0.85,0.9]:
        r=g=f=0
        for s in KK:
            sel=[i for i,pp in enumerate(P[s]) if pp>=t]
            r+=min(sum(DD[s]['cands'][i]['hit'] for i in sel),DD[s]['ngt'])
            f+=sum(1-DD[s]['cands'][i]['hit'] for i in sel);g+=DD[s]['ngt']
        mark=' <= AUDIO-ZIEL' if f/len(KK)<0.5 else ''
        print(f'   p>={t:.2f}: Recall {r}/{g} ({100*r//g}%), Phantome {f} ({f/len(KK):.2f}/Serie){mark}')

ngt=sum(DD[s]['ngt'] for s in KK)
print(f'\\nKorpus: {len(KK)} valide Serien, {ngt} Loecher, Fusion aktiv')
run(BASE,'RF OHNE Template-Feature')
run(BASE+['ncc'],'RF MIT Template-Feature (ncc)')
