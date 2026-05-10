"""
TrefferPoint Session-Extractor
==============================
Liest ein Session-Log (JSON aus der App), holt das ETF150-Video von der Kamera
(via adb shell + adb pull) und schneidet pro detektiertem Treffer ein
Frame-Fenster heraus. Ergebnis: Trainings-Set für Detektionsentwicklung.

Verwendung:
  python extract_session.py tp_session_20260510T103045_kk25.json
  python extract_session.py session.json --window 0.5 --fps 10

Voraussetzungen:
  - Tablet via USB verbunden, ETF150-WiFi aktiv (Kamera erreichbar via adb shell curl)
  - ffmpeg im PATH

Erzeugt unter runs/<session_name>/:
  video.mp4              ← gepulltes Original
  hit_NN_t<ms>_R<ring>.jpg (5 Frames pro Hit ± Window)
  session.json           ← Kopie des Logs
  README.txt             ← Anleitung wie das Set zu lesen ist
"""
import argparse, json, os, subprocess, sys, shutil
from pathlib import Path

def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)

def adb_pull_video(video_url, dest):
    """Hole Video via adb shell curl + adb pull (Tablet als Relay)."""
    tablet_tmp = '/sdcard/.tp_session.mp4'
    print(f'[1/3] Kamera → Tablet: curl {video_url}')
    r = run(['adb', 'shell', f'curl -s --max-time 60 -o {tablet_tmp} "{video_url}"'])
    if r.returncode != 0:
        print(f'  Fehler: {r.stderr}'); return False
    print(f'[2/3] Tablet → PC: adb pull {tablet_tmp}')
    r = run(['adb', 'pull', tablet_tmp, str(dest)])
    if r.returncode != 0:
        print(f'  Fehler: {r.stderr}'); return False
    run(['adb', 'shell', f'rm -f {tablet_tmp}'])
    sz = dest.stat().st_size
    print(f'  OK: {sz//1024} KB')
    return True

def extract_frames_at_hit(video_path, t_seconds, window_s, fps, out_dir, hit_num, ring):
    """ffmpeg: bei t_seconds ± window_s Frames mit fps extrahieren."""
    start = max(0, t_seconds - window_s)
    duration = window_s * 2
    pattern = out_dir / f'hit_{hit_num:02d}_t{int(t_seconds*1000)}_R{ring}_%02d.jpg'
    cmd = ['ffmpeg', '-y', '-loglevel', 'error',
           '-ss', f'{start:.3f}',
           '-i', str(video_path),
           '-t', f'{duration:.3f}',
           '-vf', f'fps={fps}',
           '-q:v', '2',
           str(pattern)]
    r = run(cmd)
    if r.returncode != 0:
        print(f'  ffmpeg-Fehler bei Hit {hit_num}: {r.stderr[:200]}')
        return 0
    # Frames zählen
    return len(list(out_dir.glob(f'hit_{hit_num:02d}_t*_R{ring}_*.jpg')))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('session_json', help='Session-Log JSON aus der App')
    ap.add_argument('--window', type=float, default=0.4,
                    help='Sekunden vor/nach Hit-Timestamp (Default 0.4 = ±400ms)')
    ap.add_argument('--fps',    type=int,   default=15,
                    help='Frame-Rate für Extraktion (Default 15 = ~12 Frames pro Hit)')
    ap.add_argument('--out',    type=str,   default=None,
                    help='Output-Ordner (Default runs/<session_name>/)')
    args = ap.parse_args()

    # ── Session-Log laden ────────────────────────────────────────────────
    sess_path = Path(args.session_json).resolve()
    if not sess_path.exists():
        print(f'Datei nicht gefunden: {sess_path}'); sys.exit(1)
    with open(sess_path, encoding='utf-8') as f:
        sess = json.load(f)
    if not sess.get('rec', {}).get('video_url'):
        print('FEHLER: Kein video_url im Session-Log (Aufnahme war nicht erfolgreich?)')
        sys.exit(1)

    # ── Output-Ordner ─────────────────────────────────────────────────────
    name = sess_path.stem
    if args.out:
        out_dir = Path(args.out).resolve()
    else:
        out_dir = Path(__file__).parent / 'runs' / name
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f'Output: {out_dir}')

    # ── Video holen ───────────────────────────────────────────────────────
    video_path = out_dir / 'video.mp4'
    if not video_path.exists():
        if not adb_pull_video(sess['rec']['video_url'], video_path):
            print('Video-Download fehlgeschlagen'); sys.exit(2)
    else:
        print(f'[1/3] Video bereits vorhanden ({video_path.stat().st_size//1024} KB)')

    # ── Pro Hit Frames extrahieren ───────────────────────────────────────
    hits = sess.get('hits', [])
    print(f'[3/3] Extrahiere Frames für {len(hits)} Hits (window=±{args.window}s, fps={args.fps})…')
    total_frames = 0
    for h in hits:
        t_ms = h['t_ms']
        t_s  = t_ms / 1000.0
        ring = str(h['ring']).replace('.', '_')
        n_frames = extract_frames_at_hit(video_path, t_s, args.window, args.fps,
                                          out_dir, h['n'], ring)
        if n_frames:
            total_frames += n_frames
            print(f'  Hit {h["n"]:02d} @ {t_s:6.2f}s  Ring {h["ring"]} → {n_frames} Frames')
        else:
            print(f'  Hit {h["n"]:02d} @ {t_s:6.2f}s  Ring {h["ring"]} → keine Frames!')

    # ── Session-Log kopieren + README schreiben ──────────────────────────
    shutil.copy2(sess_path, out_dir / 'session.json')
    readme = out_dir / 'README.txt'
    with open(readme, 'w', encoding='utf-8') as f:
        f.write(f'TrefferPoint Session: {name}\n')
        f.write(f'==={"=" * len(name)}\n\n')
        f.write(f'Disziplin:    {sess.get("discipline")}\n')
        f.write(f'Start:        {sess["session"].get("start_iso")}\n')
        f.write(f'Dauer:        {sess["session"].get("duration_s"):.1f}s\n')
        f.write(f'Hits gesamt:  {len(hits)}\n')
        f.write(f'Frames:       {total_frames} ({args.fps} fps × ±{args.window}s je Hit)\n\n')
        f.write(f'Datei-Schema:\n')
        f.write(f'  hit_NN_t<ms>_R<ring>_FF.jpg\n')
        f.write(f'    NN  = Hit-Nummer (01..{len(hits):02d})\n')
        f.write(f'    ms  = Hit-Timestamp relativ zu Aufnahme-Start\n')
        f.write(f'    ring= erkannter Ring (10_3 = 10.3, 7_5 = 7.5)\n')
        f.write(f'    FF  = Frame-Index im Hit-Window (00..{int(2*args.window*args.fps):02d})\n')
    print(f'\n✓ Fertig: {total_frames} Frames in {out_dir}')

if __name__ == '__main__':
    main()
