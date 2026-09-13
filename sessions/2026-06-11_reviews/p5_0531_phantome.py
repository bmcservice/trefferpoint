# Phase 1: Was sind die hellen Phantome in 0531?
# Galerie: Basis/Schluss/Residuum-Patch + Zeitverlauf je Kandidat; numerische Klassifikation.
import sys, math, glob, re
sys.stdout.reconfigure(encoding='utf-8')
import numpy as np
from PIL import Image
from scipy import ndimage
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

D = 'sessions/corpus_gt/1780216449975_kk25'
CX, CY, A = 1048, 498, 310
GT = [(613,477),(820,669),(845,547),(927,552),(846,419)]
T, TOL = 20, 12

def load(f): return np.asarray(Image.open(f).convert('L'), dtype=np.float32)
def med(fs): return np.median(np.stack([load(f) for f in fs]), axis=0)

def reg_shift(mov, ref):
    r = int(A*1.1)
    m = mov[CY-r:CY+r, CX-r:CX+r]; f = ref[CY-r:CY+r, CX-r:CX+r]
    H, W = m.shape; win = np.outer(np.hanning(H), np.hanning(W))
    Af = np.fft.fft2((m-m.mean())*win); Bf = np.fft.fft2((f-f.mean())*win)
    R = Af*np.conj(Bf); R /= np.abs(R)+1e-6
    c = np.fft.ifft2(R).real; pk = np.unravel_index(np.argmax(c), c.shape)
    def par(v,i,n):
        a0=v[(i-1)%n]; a1=v[i]; a2=v[(i+1)%n]; return (a0-a2)/(2*(a0-2*a1+a2)+1e-9)
    dy = pk[0]+par(c[:,pk[1]],pk[0],H); dx = pk[1]+par(c[pk[0],:],pk[1],W)
    if dy>H/2: dy-=H
    if dx>W/2: dx-=W
    return dy, dx

fs = sorted(glob.glob(D+'/df_*.jpg'), key=lambda f: int(re.search(r'df_(\d+)',f).group(1)))
print(f'0531: {len(fs)} Frames')
base = med(fs[:6]); final = med(fs[-6:])
dy, dx = reg_shift(base, final)
breg = ndimage.shift(base, (-dy,-dx), order=3, mode='nearest')
H, W = base.shape
yy, xx = np.mgrid[0:H, 0:W]
sp = ((xx-CX)**2+(yy-CY)**2) < (A*0.9)**2
off = np.median(final[sp]) - np.median(breg[sp])
breg += off
diff = final - breg                      # signiert: Loch im Spiegel = positiv

# Detektion wie im Polaritaets-Test (pos, T=20)
d = np.clip(diff, 0, None)
res = ndimage.gaussian_filter(d, 0.8); bg = ndimage.median_filter(res, 25); th = res - bg
spg = ((xx-CX)**2+(yy-CY)**2) < (A*1.0)**2
lbl, n = ndimage.label((th>T) & spg)
cands = []
for k in range(1, n+1):
    ys, xs = np.where(lbl==k); ar = len(xs)
    if ar<6 or ar>400: continue
    xr=xs-xs.mean(); yr=ys-ys.mean(); cov=np.cov(np.stack([xr,yr])); ev=np.linalg.eigvalsh(cov)
    rnd = ev[0]/ev[1] if ev[1]>1e-6 else 0
    if rnd<0.25: continue
    cands.append(dict(x=float(xs.mean()), y=float(ys.mean()), area=ar, round=rnd))

# GT-Match
used=set()
for g in GT:
    best=None; bd=TOL+1
    for i,c in enumerate(cands):
        if i in used: continue
        dd=math.hypot(c['x']-g[0], c['y']-g[1])
        if dd<=TOL and dd<bd: bd=dd; best=i
    if best is not None: used.add(best); cands[best]['typ']='LOCH'
for c in cands:
    c.setdefault('typ','PHANTOM')
# verpasste GT-Loecher trotzdem in die Galerie
det_pos={(round(c['x']),round(c['y'])) for c in cands if c['typ']=='LOCH'}
for g in GT:
    if not any(math.hypot(px-g[0],py-g[1])<=TOL for px,py in det_pos):
        cands.append(dict(x=float(g[0]), y=float(g[1]), area=0, round=0, typ='LOCH(verpasst)'))

# Frames global registrieren + Offset-korrigieren fuer Zeitverlauf
frames=[]
for f in fs:
    img = load(f)
    fdy, fdx = reg_shift(img, final)
    img = ndimage.shift(img, (-fdy,-fdx), order=1, mode='nearest')
    img += np.median(final[sp]) - np.median(img[sp])
    frames.append(img)

P = 22  # Patch-Halbgroesse
print()
print(f"{'#':>2} {'Typ':16s} {'x':>5} {'y':>5} {'r/a':>5} {'Peak':>5} {'Fl.':>4} {'Rund':>5} {'BasisHell':>9} {'aufDruck':>8}  Zeitprofil(14 Frames, lokWert)")
rows=[]
for i,c in enumerate(sorted(cands, key=lambda c:(c['typ']!='PHANTOM', -c.get('area',0)))):
    x,y = int(round(c['x'])), int(round(c['y']))
    pb = breg[y-P:y+P, x-P:x+P]; pf = final[y-P:y+P, x-P:x+P]; pd = diff[y-P:y+P, x-P:x+P]
    peak = float(pd[P-6:P+7, P-6:P+7].max())
    ra = math.hypot(x-CX, y-CY)/A
    basehell = float(np.median(breg[y-4:y+5, x-4:x+5]))
    # aufDruck: lokale Basis deutlich heller als Spiegel-Median (weisser Druck)
    spiegel_med = float(np.median(breg[sp]))
    aufdruck = basehell > spiegel_med + 30
    traj = [float(np.mean(np.clip(fr[y-3:y+4, x-3:x+4] - breg[y-3:y+4, x-3:x+4], -50, 250))) for fr in frames]
    rows.append((c, pb, pf, pd, traj))
    tstr = ' '.join(f'{v:4.0f}' for v in traj)
    print(f"{i:>2} {c['typ']:16s} {x:>5} {y:>5} {ra:5.2f} {peak:5.0f} {c['area']:>4} {c['round']:5.2f} {basehell:9.0f} {str(aufdruck):>8}  {tstr}")

# Galerie-PNG
N=len(rows)
fig, axs = plt.subplots(N, 4, figsize=(11, 2.0*N))
if N==1: axs=axs[None,:]
for i,(c,pb,pf,pd,traj) in enumerate(rows):
    for j,(img,t) in enumerate([(pb,'Basis'),(pf,'Schluss'),(pd,'Residuum (signiert)')]):
        ax=axs[i,j]
        if j<2: ax.imshow(img, cmap='gray', vmin=0, vmax=255)
        else:   ax.imshow(img, cmap='coolwarm', vmin=-120, vmax=120)
        ax.set_xticks([]); ax.set_yticks([])
        if i==0: ax.set_title(t, fontsize=9)
        if j==0: ax.set_ylabel(f"#{i} {c['typ']}", fontsize=8, rotation=0, ha='right', va='center')
    ax=axs[i,3]
    ax.plot(traj, 'o-', ms=3)
    ax.axhline(0, color='gray', lw=0.5); ax.set_ylim(-40, max(120, max(traj)+10))
    ax.tick_params(labelsize=6)
    if i==0: ax.set_title('lok. Residuum je Frame (Zeit)', fontsize=9)
fig.suptitle('0531 — Kandidaten-Galerie: echte Loecher vs Phantome (pos, T=20)', fontsize=11)
fig.tight_layout(rect=[0.04,0,1,0.99])
fig.savefig('reports/P5_0531_galerie.png', dpi=110)
print('\nGalerie: reports/P5_0531_galerie.png')
