# QS-3/QS-4: 0606b-Diagnose (warum 0/5?) + Baseline-Kontaminations-Check aller 4 Batch-Serien
import sys, glob, re, math
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage

G='sessions/2026-06-14_gtbatch/'
SESS = {
 '0529': (G+'full_1780070313706/1780070313706_kk25',(947,423,305),[(561,620),(703,564),(625,516),(631,411),(883,868)]),
 '0603b':(G+'full_1780501584393/1780501584393_kk25',(986,469,232),[(930,592),(632,738),(1106,502),(1056,406),(956,424)]),
 '0606b':(G+'full_1780764995131/1780764995131_kk25',(751,406,136),[(1087,701),(1103,651),(1063,614),(1144,570),(1132,401)]),
 '0607': (G+'full_1780826808538/1780826808538_kk25',(1072,496,231),[(1202,410),(952,549),(1407,843)]),
}
def load(f): return np.asarray(Image.open(f).convert('L'),dtype=np.float32)

for tag,(D,cal,GT) in SESS.items():
    fs=sorted(glob.glob(D+'/df_*.jpg'),key=lambda f:int(re.search(r'df_(\d+)',f).group(1)))
    N=len(fs)
    f0=load(fs[0]); f5=load(fs[5]); fL=load(fs[-1])
    base=np.median(np.stack([load(f) for f in fs[:6]]),axis=0)
    print(f'=== {tag} ({N} Frames) ===')
    # Globale Stabilitaet: Helligkeit + Struktur-Korrelation Anfang vs Ende
    means=[float(load(fs[i]).mean()) for i in range(0,N,max(1,N//25))]
    print(f'  Helligkeit ueber Session: min {min(means):.0f} max {max(means):.0f}')
    # Szenen-Wechsel-Detektor: Korrelation Frame0 vs FrameLast (grob, zentral)
    c0=f0[200:880,400:1520]; cL=fL[200:880,400:1520]
    cc=float(np.corrcoef(c0.ravel()[::7],cL.ravel()[::7])[0,1])
    print(f'  Struktur-Korrelation Frame0<->FrameEnde: {cc:.3f} (nahe 1 = gleiche Szene)')
    # Pro GT-Loch: schon in Baseline? Sprung im Verlauf? Signal am Ende?
    print(f'  {"GT":>14} {"|f5-f0|":>8} {"|Ende-Basis|":>12}  Zeitverlauf (12 Stuetzstellen, lokales Mittel Ende-Basis)')
    for gx,gy in GT:
        d05=float(np.abs(f5-f0)[gy-6:gy+7,gx-6:gx+7].max())
        dEB=float(np.abs(fL-base)[gy-6:gy+7,gx-6:gx+7].max())
        traj=[]
        for i in range(0,N,max(1,N//12))[:12]:
            fi=load(fs[i])
            traj.append(float(np.mean(fi[gy-3:gy+4,gx-3:gx+4]-base[gy-3:gy+4,gx-3:gx+4])))
        tstr=' '.join(f'{v:5.0f}' for v in traj)
        flag=' <- SCHON IN BASELINE?' if (d05>40) else (' <- KEIN SIGNAL AM ENDE!' if dEB<25 else '')
        print(f'  ({gx:4},{gy:4}) {d05:8.0f} {dEB:12.0f}  {tstr}{flag}')
    print()
