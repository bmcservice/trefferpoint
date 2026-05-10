"""
TrefferPoint Trainings-Server
==============================
Ersetzt: python -m http.server 8765 --directory training_data

Verwendung:
  python server.py                      # Port 8765
  python server.py --port 8766

Endpoints:
  /                          → statische Dateien aus training_data/
  /proxy?url=<URL>           → CORS-freier HTTP-Proxy (fetch any cam URL)
  /cam/snap?host=<IP>        → Viidure SGK: 3-Schritt-Snapshot → JPEG
                               Apexel ETF150: ADB-Relay (host=192.168.10.1)
  /cam/mjpeg?host=<IP>       → MJPEG-Stream-Frame (einzelnes JPEG)
"""
import sys; sys.stdout.reconfigure(encoding='utf-8')
import http.server, urllib.request, urllib.parse, urllib.error
import os, json, time, argparse, socketserver, threading, subprocess, tempfile

BASE = os.path.dirname(os.path.abspath(__file__))

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BASE, **kwargs)

    def add_cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')

    def do_OPTIONS(self):
        self.send_response(200)
        self.add_cors()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/proxy':
            self.handle_proxy(parsed)
        elif parsed.path == '/cam/snap':
            params = urllib.parse.parse_qs(parsed.query)
            host = params.get('host', ['192.168.0.1'])[0]
            cam_type = params.get('type', [''])[0]
            if cam_type == 'etf150' or host.startswith('192.168.10.'):
                self.handle_etf150_snap(host)
            else:
                self.handle_cam_snap(parsed)
        elif parsed.path == '/cam/mjpeg':
            self.handle_cam_mjpeg(parsed)
        else:
            super().do_GET()

    def log_message(self, fmt, *args):
        # Nur Proxy/Cam-Requests loggen, statische Files unterdrücken
        if any(x in (args[0] if args else '') for x in ['/proxy', '/cam/']):
            super().log_message(fmt, *args)

    # ── Generic HTTP Proxy ──────────────────────────────────────────────────
    def handle_proxy(self, parsed):
        params = urllib.parse.parse_qs(parsed.query)
        url = params.get('url', [None])[0]
        if not url:
            self.send_error(400, 'Missing ?url= parameter'); return
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'TrefferPoint-Proxy/1.0'})
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = resp.read()
                ct = resp.headers.get('Content-Type', 'application/octet-stream')
            self.send_response(200)
            self.send_header('Content-Type', ct)
            self.send_header('Content-Length', str(len(data)))
            self.add_cors()
            self.end_headers()
            self.wfile.write(data)
        except urllib.error.URLError as e:
            self.send_error(502, f'Proxy-Fehler: {e.reason}')
        except Exception as e:
            self.send_error(502, str(e))

    # ── Apexel ETF150: Snapshot via ADB-Relay ──────────────────────────────
    def handle_etf150_snap(self, host='192.168.10.1'):
        """
        ETF150 ist nur über Tablet-WiFi erreichbar → ADB als Relay.
        Mit eingesetzter SD-Karte:
        1. POST /roc/photo/capture → JSON mit "origin":"/roc/file/<datum>/IMGxxxxx.jpg"
        2. GET /roc/file/<path> → JPEG-Bytes
        Beide via adb shell curl, Datei auf Tablet, dann adb pull.
        """
        TABLET_TMP = '/sdcard/.tp_etf150_snap.jpg'
        try:
            # Schritt 1: Capture auslösen, JSON-Antwort lesen
            r = subprocess.run(
                ['adb', 'shell', f'curl -s --max-time 5 -X POST http://{host}/roc/photo/capture'],
                capture_output=True, text=True, timeout=8
            )
            try:
                resp = json.loads(r.stdout.strip())
            except json.JSONDecodeError:
                self.send_error(502, f'ETF150-Antwort kein JSON: {r.stdout[:200]}'); return

            files = resp.get('filelist') or []
            if not files:
                self.send_error(502, 'ETF150: Capture ohne Datei (SD-Karte?)'); return
            origin = files[0].get('origin') or files[0].get('name')
            if not origin:
                self.send_error(502, f'ETF150: Kein file-path: {resp}'); return
            if not origin.startswith('/'):
                origin = '/roc/file/' + origin.lstrip('/')

            # Schritt 2: Datei vom Kamera-HTTP via adb-curl holen, auf Tablet zwischenlagern
            subprocess.run(
                ['adb', 'shell',
                 f'curl -s --max-time 10 -o {TABLET_TMP} http://{host}{origin}'],
                capture_output=True, timeout=15, check=True
            )

            # Schritt 3: adb pull
            with tempfile.NamedTemporaryFile(suffix='.jpg', delete=False) as f:
                tmpfile = f.name
            try:
                subprocess.run(
                    ['adb', 'pull', TABLET_TMP, tmpfile],
                    capture_output=True, timeout=10, check=True
                )
                with open(tmpfile, 'rb') as f:
                    data = f.read()
            finally:
                try: os.unlink(tmpfile)
                except: pass

            if len(data) < 1000 or data[:2] != b'\xff\xd8':
                self.send_error(502, f'ETF150: Datei korrupt ({len(data)}B, magic={data[:4].hex()})'); return

            self.send_response(200)
            self.send_header('Content-Type', 'image/jpeg')
            self.send_header('Content-Length', str(len(data)))
            self.add_cors()
            self.end_headers()
            self.wfile.write(data)
            print(f'[cam/snap/etf150] {host}{origin} → {len(data)//1024}KB')

        except subprocess.TimeoutExpired:
            self.send_error(504, 'ADB-Timeout — Tablet verbunden?')
        except subprocess.CalledProcessError as e:
            self.send_error(502, f'ADB-Befehl fehlgeschlagen: {e}')
        except Exception as e:
            self.send_error(502, str(e))

    # ── Viidure SGK: 3-Schritt Snapshot ────────────────────────────────────
    def handle_cam_snap(self, parsed):
        params = urllib.parse.parse_qs(parsed.query)
        host = params.get('host', ['192.168.0.1'])[0]  # SGK GK720X default
        base_url = f'http://{host}'
        try:
            # Schritt 1: Snapshot auslösen (SGK speichert auf EMMC)
            urllib.request.urlopen(f'{base_url}/app/snapshot', timeout=5).read()
            time.sleep(0.8)

            # Schritt 2: Dateiliste holen (SGK: /app/getfilelist?path=/EMMC/)
            resp = urllib.request.urlopen(f'{base_url}/app/getfilelist?path=/EMMC/&count=99', timeout=5)
            raw = resp.read()
            files = json.loads(raw)
            if not files:
                self.send_error(404, 'Keine Dateien auf EMMC'); return
            # Neueste Datei (SGK liefert Liste, letzte = neueste)
            latest = files[-1]
            path = latest.get('path') or latest.get('name', '')
            if not path.startswith('/EMMC/'):
                path = '/EMMC/' + path.lstrip('/')

            # Schritt 3: Herunterladen (SGK: /EMMC/<path>)
            img_resp = urllib.request.urlopen(f'{base_url}{path}', timeout=10)
            data = img_resp.read()
            ct = img_resp.headers.get('Content-Type', 'image/jpeg')

            self.send_response(200)
            self.send_header('Content-Type', ct)
            self.send_header('Content-Length', str(len(data)))
            self.add_cors()
            self.end_headers()
            self.wfile.write(data)
            print(f'[cam/snap] {host} → {len(data)//1024}KB ({path})')

        except json.JSONDecodeError:
            self.send_error(502, f'Viidure API-Antwort kein JSON: {raw[:200]}')
        except urllib.error.URLError as e:
            self.send_error(502, f'Kamera nicht erreichbar ({host}): {e.reason}')
        except Exception as e:
            self.send_error(502, str(e))

    # ── MJPEG: einzelnen Frame holen (für generische WiFi-Cams) ────────────
    def handle_cam_mjpeg(self, parsed):
        params = urllib.parse.parse_qs(parsed.query)
        host = params.get('host', [None])[0]
        url  = params.get('url',  [None])[0]
        if not url and host:
            url = f'http://{host}/stream'
        if not url:
            self.send_error(400, 'Missing ?url= oder ?host= Parameter'); return
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'TrefferPoint-Proxy/1.0'})
            with urllib.request.urlopen(req, timeout=8) as resp:
                ct = resp.headers.get('Content-Type', '')
                if 'multipart' in ct:
                    # MJPEG: ersten JPEG-Frame extrahieren
                    buf = b''
                    while len(buf) < 200_000:
                        chunk = resp.read(4096)
                        if not chunk: break
                        buf += chunk
                        start = buf.find(b'\xff\xd8')
                        end   = buf.find(b'\xff\xd9', start+2) if start >= 0 else -1
                        if start >= 0 and end >= 0:
                            data = buf[start:end+2]; break
                    else:
                        self.send_error(502, 'Kein JPEG-Frame in MJPEG-Stream gefunden'); return
                else:
                    data = resp.read()
            self.send_response(200)
            self.send_header('Content-Type', 'image/jpeg')
            self.send_header('Content-Length', str(len(data)))
            self.add_cors()
            self.end_headers()
            self.wfile.write(data)
        except urllib.error.URLError as e:
            self.send_error(502, f'Stream nicht erreichbar: {e.reason}')
        except Exception as e:
            self.send_error(502, str(e))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()

    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(('', args.port), Handler) as httpd:
        print(f'TrefferPoint Trainings-Server läuft auf http://localhost:{args.port}')
        print(f'  Dateien:   training_data/')
        print(f'  Proxy:     /proxy?url=<URL>')
        print(f'  Viidure:   /cam/snap?host=192.168.0.1')
        print(f'  ETF150:    /cam/snap?host=192.168.10.1  (ADB-Relay)')
        print(f'  MJPEG:     /cam/mjpeg?url=<Stream-URL>')
        httpd.serve_forever()
