import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Lock, Thread

instances = {}  # key -> instance dict
lock = Lock()

HTTP_BIND = os.environ.get('CAMOUFOX_HTTP_BIND', '127.0.0.1')
WS_HOST = os.environ.get('CAMOUFOX_WS_HOST', '127.0.0.1')
PORT_START = int(os.environ.get('CAMOUFOX_PORT_START', '18080'))
PORT_SCAN_LIMIT = int(os.environ.get('CAMOUFOX_PORT_SCAN_LIMIT', '200'))
WS_PATH = os.environ.get('CAMOUFOX_WS_PATH', 'camoufox')
SERVER_BIND = os.environ.get(
    'CAMOUFOX_SERVER_BIND',
    '127.0.0.1' if WS_HOST in ('127.0.0.1', 'localhost') else '0.0.0.0',
)
PROBE_HOST = '127.0.0.1'

# Считаем инстанс «в работе», пока Java шлёт heartbeat или не истёк grace после старта.
HEARTBEAT_STALE_SEC = int(os.environ.get('CAMOUFOX_HEARTBEAT_STALE_SEC', '600'))
LAUNCH_GRACE_SEC = int(os.environ.get('CAMOUFOX_LAUNCH_GRACE_SEC', '300'))
REAPER_INTERVAL_SEC = int(os.environ.get('CAMOUFOX_REAPER_INTERVAL_SEC', '60'))
REAPER_ENABLED = os.environ.get('CAMOUFOX_REAPER_ENABLED', 'true').lower() in ('1', 'true', 'yes')

NEXT_PORT = PORT_START


def _now():
    return time.time()


def _port_open(port):
    try:
        with socket.create_connection((PROBE_HOST, port), timeout=0.5):
            return True
    except (ConnectionRefusedError, OSError):
        return False


def _port_bindable(port):
    """True if we can bind — port is not taken by another process (e.g. Java on 8083)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((PROBE_HOST, port))
            return True
    except OSError:
        return False


def _allocate_port():
    global NEXT_PORT
    for _ in range(PORT_SCAN_LIMIT):
        port = NEXT_PORT
        NEXT_PORT += 1
        if _port_bindable(port):
            return port
        print(f'[launcher] skip occupied port {port}', flush=True)
    raise RuntimeError(f'no free port in range starting at {PORT_START}')


def _ws_endpoint_ready(port, path=WS_PATH):
    """
    TCP open is not enough: another HTTP server on the same port returns 404 for /camoufox.
    Playwright BrowserServer answers non-404 (often 400/426) to a plain HTTP GET.
    """
    if not _port_open(port):
        return False
    url = f'http://{PROBE_HOST}:{port}/{path}'
    try:
        with urllib.request.urlopen(url, timeout=2) as resp:
            body = resp.read(256)
            return b'File not found' not in body
    except urllib.error.HTTPError as e:
        body = e.read(256) if e.fp else b''
        if e.code == 404 and (b'File not found' in body or b'Not Found' in body):
            return False
        return e.code in (400, 403, 405, 426)
    except Exception:
        return False


def _kill_process_tree(proc):
    if proc is None:
        return
    pid = proc.pid
    if sys.platform == 'win32':
        subprocess.run(
            ['taskkill', '/F', '/T', '/PID', str(pid)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return
    try:
        proc.terminate()
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def _touch(inst):
    inst['last_touch'] = _now()


def _classify_instance(inst):
    proc = inst['process']
    alive = proc.poll() is None
    port = inst.get('port')
    port_ok = _port_open(port) if port else False
    refs = inst.get('refs', 0)
    now = _now()
    started = inst.get('started_at', now)
    heartbeat = inst.get('last_heartbeat', started)

    if not alive:
        return 'dead', False

    if refs <= 0:
        return 'idle', False

    if now - heartbeat <= HEARTBEAT_STALE_SEC:
        return 'active', True
    if now - started <= LAUNCH_GRACE_SEC:
        return 'starting', True

    return 'stale', False


def _instance_public(key, inst):
    state, in_use = _classify_instance(inst)
    proc = inst['process']
    return {
        'key': key,
        'port': inst.get('port'),
        'endpoint': inst.get('endpoint'),
        'site': inst.get('site', 'unknown'),
        'country': inst.get('country', '?'),
        'user_email': inst.get('user_email', '?'),
        'refs': inst.get('refs', 0),
        'pid': proc.pid if proc else None,
        'process_alive': proc.poll() is None if proc else False,
        'port_open': _port_open(inst.get('port')) if inst.get('port') else False,
        'state': state,
        'in_use': in_use,
        'started_at': inst.get('started_at'),
        'last_touch': inst.get('last_touch'),
        'last_heartbeat': inst.get('last_heartbeat'),
    }


def _reap(dry_run=False):
    reaped = []
    kept = []
    with lock:
        keys = list(instances.keys())
    for key in keys:
        with lock:
            inst = instances.get(key)
            if inst is None:
                continue
            state, in_use = _classify_instance(inst)
            if in_use:
                kept.append(_instance_public(key, inst))
                continue
            info = _instance_public(key, inst)
            if dry_run:
                reaped.append({**info, 'would_reap': True})
                continue
            instances.pop(key, None)
            proc = inst.get('process')
            if proc and proc.poll() is None:
                site = inst.get('site', 'unknown')
                print(
                    f'[reaper] kill port {inst.get("port")} state={state} '
                    f'site={site} refs={inst.get("refs")}',
                    flush=True,
                )
                _kill_process_tree(proc)
            reaped.append(info)
    return {'reaped': reaped, 'active': kept}


def _reaper_loop():
    while True:
        time.sleep(REAPER_INTERVAL_SEC)
        if not REAPER_ENABLED:
            continue
        try:
            result = _reap(dry_run=False)
            if result['reaped']:
                print(f'[reaper] cleaned {len(result["reaped"])} instance(s)', flush=True)
        except Exception as e:
            print(f'[reaper] error: {e}', flush=True)


def _format_user_email(data):
    email = data.get('userEmail') or data.get('user_email')
    if email is None or str(email).strip() == '':
        return '?'
    return str(email).strip()


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == '/health':
            return self._json({'status': 'ok'})
        if self.path == '/instances':
            return self._instances()
        self.send_error(404)

    def do_POST(self):
        if self.path == '/launch':
            return self._launch()
        if self.path == '/release':
            return self._release()
        if self.path == '/heartbeat':
            return self._heartbeat()
        if self.path == '/cleanup':
            return self._cleanup()
        self.send_error(404)

    def _instances(self):
        with lock:
            rows = [_instance_public(k, inst) for k, inst in instances.items()]
        active = sum(1 for r in rows if r['in_use'])
        return self._json({
            'count': len(rows),
            'active': active,
            'idle': len(rows) - active,
            'instances': rows,
        })

    def _heartbeat(self):
        length = int(self.headers.get('Content-Length', 0))
        data = json.loads(self.rfile.read(length))
        key = data.get('key')
        with lock:
            inst = instances.get(key)
            if inst is None:
                return self._json({'status': 'not_found'}, status=404)
            inst['last_heartbeat'] = _now()
            _touch(inst)
            state, in_use = _classify_instance(inst)
            return self._json({'status': 'ok', 'state': state, 'in_use': in_use})

    def _cleanup(self):
        length = int(self.headers.get('Content-Length', 0))
        dry_run = False
        if length:
            data = json.loads(self.rfile.read(length))
            dry_run = bool(data.get('dry_run', False))
        return self._json(_reap(dry_run=dry_run))

    def _launch(self):
        global NEXT_PORT
        length = int(self.headers.get('Content-Length', 0))
        data = json.loads(self.rfile.read(length))
        proxy = data.get('proxy')
        site = data.get('site', 'unknown')
        country = data.get('country') or '?'
        user_email = _format_user_email(data)
        headless = bool(data.get('headless', True))
        proxy_part = json.dumps(proxy, sort_keys=True) if proxy else 'none'
        key = f'{headless}|{proxy_part}'

        with lock:
            inst = instances.get(key)
            if inst:
                proc = inst['process']
                alive = proc.poll() is None
                ws_ok = alive and _ws_endpoint_ready(inst.get('port'), WS_PATH)
                if alive and ws_ok:
                    inst['refs'] = inst.get('refs', 1) + 1
                    inst['last_heartbeat'] = _now()
                    _touch(inst)
                    inst['site'] = site
                    inst['country'] = country
                    inst['user_email'] = user_email
                    print(
                        f'[launcher] [{site}] reuse port {inst["port"]} country={country} '
                        f'user={user_email} refs={inst["refs"]}',
                        flush=True,
                    )
                    return self._json({'endpoint': inst['endpoint'], 'key': key})
                if inst:
                    print(
                        f'[launcher] [{site}] drop stale instance port {inst.get("port")} '
                        f'alive={alive} ws_ok={ws_ok}',
                        flush=True,
                    )
                    instances.pop(key, None)
                    if alive:
                        _kill_process_tree(proc)

            port = _allocate_port()

            script = (
                "import sys, json\n"
                "from camoufox.server import launch_server\n"
                "p = json.loads(sys.argv[1]) if sys.argv[1] != 'null' else None\n"
                f"launch_server(headless={headless}, port={port}, ws_path='{WS_PATH}', "
                f"host='{SERVER_BIND}', proxy=p, locale='ru-RU')\n"
            )
            proc = subprocess.Popen(
                [sys.executable, "-c", script, json.dumps(proxy)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            endpoint = f'ws://{WS_HOST}:{port}/{WS_PATH}'
            proxy_str = proxy.get('server') if proxy else 'no-proxy'

            print(
                f'[launcher] [{site}] start port {port} headless={headless} proxy={proxy_str} '
                f'country={country} user={user_email}',
                flush=True,
            )

        deadline = _now() + 60
        ready = False
        while _now() < deadline:
            if proc.poll() is not None:
                print(f'[launcher] [{site}] FAILED port {port} user={user_email} (process died)', flush=True)
                return self._json({'error': 'Camoufox failed to start'}, status=500)
            if _ws_endpoint_ready(port, WS_PATH):
                ready = True
                break
            time.sleep(0.5)

        if not ready:
            print(
                f'[launcher] [{site}] TIMEOUT port {port} user={user_email} '
                f'(ws /{WS_PATH} not ready)',
                flush=True,
            )
            _kill_process_tree(proc)
            return self._json({'error': 'Camoufox timeout'}, status=500)

        now = _now()
        with lock:
            instances[key] = {
                'process': proc,
                'endpoint': endpoint,
                'port': port,
                'site': site,
                'country': country,
                'user_email': user_email,
                'refs': 1,
                'started_at': now,
                'last_touch': now,
                'last_heartbeat': now,
            }
        print(f'[launcher] [{site}] ready port {port} country={country} user={user_email}', flush=True)
        return self._json({'endpoint': endpoint, 'key': key})

    def _release(self):
        length = int(self.headers.get('Content-Length', 0))
        data = json.loads(self.rfile.read(length))
        key = data.get('key')

        with lock:
            inst = instances.get(key)
            if inst is None:
                return self._json({'status': 'not_found'})

            proc = inst['process']
            site = inst.get('site', 'unknown')
            country = inst.get('country', '?')
            user_email = inst.get('user_email', '?')

            inst['refs'] = max(0, inst.get('refs', 1) - 1)
            _touch(inst)
            if inst['refs'] > 0 and proc.poll() is None:
                print(
                    f'[launcher] [{site}] keep port {inst["port"]} user={user_email} refs={inst["refs"]}',
                    flush=True,
                )
                return self._json({'status': 'in_use', 'refs': inst['refs']})

            instances.pop(key, None)
            if proc.poll() is None:
                print(
                    f'[launcher] [{site}] kill port {inst["port"]} country={country} user={user_email}',
                    flush=True,
                )
                _kill_process_tree(proc)

            self._json({'status': 'released'})

    def _json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print(f'[launcher] {fmt % args}', flush=True)


if __name__ == '__main__':
    if REAPER_ENABLED:
        Thread(target=_reaper_loop, daemon=True, name='camoufox-reaper').start()
    print(
        f'Camoufox launcher on http://{HTTP_BIND}:8888 ws_host={WS_HOST} '
        f'reaper={REAPER_ENABLED} heartbeat_stale={HEARTBEAT_STALE_SEC}s',
        flush=True,
    )
    HTTPServer((HTTP_BIND, 8888), Handler).serve_forever()
