#!/usr/bin/env python3
"""Local HTTPS GitHub release fixture; never publishes or contacts GitHub."""
import argparse
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import threading
import time

class Fixture:
    def __init__(self, directory):
        self.directory = directory
        self.lock = threading.Lock()
        self.mode = 'normal'
        self.counts = {}
        self.active_streams = 0
    def data(self, name, mode):
        data = (self.directory / name).read_bytes()
        if name == 'jellybeam-tv-update.json' and mode == 'current':
            value = json.loads(data); value['version_code'] = 700001
            data = json.dumps(value).encode()
        if name.endswith('.apk') and mode == 'corrupt-signature':
            data = bytearray(data); data[len(data) // 2] ^= 1; data = bytes(data)
        return data
    def release(self):
        with self.lock: mode = self.mode
        assets = []
        names = json.loads((self.directory / 'jellybeam-tv-update.json').read_text())
        files = ['jellybeam-tv-update.json', names['apk_asset']] + [p['asset'] for p in names['profiles']]
        for index, name in enumerate(files):
            data = self.data(name, mode)
            assets.append(dict(id=index + 10, name=name, state='uploaded', size=len(data),
                digest='sha256:' + hashlib.sha256(data).hexdigest(),
                browser_download_url='https://localhost:18443/assets/' + name))
        with self.lock: mode = self.mode
        if mode == 'bad-hash': assets[1]['digest'] = 'sha256:' + '0' * 64
        if mode == 'missing-digest': assets[1].pop('digest')
        return dict(id=123, tag_name='v0.1.7', draft=False, prerelease=mode == 'withdrawn', immutable=mode != 'mutable',
                    published_at='2026-10-03T00:00:00Z', body=(self.directory / 'release-notes.md').read_text(), assets=assets)

def handler(fixture):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def send(self, code, body=b'', **headers):
            self.send_response(code)
            self.send_header('Content-Length', str(len(body)))
            for key, value in headers.items(): self.send_header(key.replace('_', '-'), value)
            self.end_headers()
            try: self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
        def do_POST(self):
            if self.path != '/__control': return self.send(404)
            length = int(self.headers.get('Content-Length', 0))
            if not 0 <= length <= 1024: return self.send(400)
            data = json.loads(self.rfile.read(length))
            if data.get('mode') not in {'normal', 'slow', 'stall', 'bad-hash', 'missing-digest', 'mutable', 'withdrawn', 'rate-limit', 'truncated', 'current', 'checking', 'network-error', 'corrupt-signature', 'corrupt-transfer'}: return self.send(400)
            with fixture.lock: fixture.mode = data['mode']
            self.send(200, b'{}')
        def do_GET(self):
            with fixture.lock:
                fixture.counts[self.path] = fixture.counts.get(self.path, 0) + 1
                mode = fixture.mode
            if self.path == '/__control':
                with fixture.lock: body = json.dumps({'counts': fixture.counts, 'active_streams': fixture.active_streams}).encode()
                return self.send(200, body)
            if mode == 'rate-limit': return self.send(429, b'{}', Retry_After='2')
            if self.path.startswith('/repos/'):
                if mode == 'checking': time.sleep(8)
                if mode == 'network-error': return self.send(503, b'{}')
                body = json.dumps(fixture.release()).encode()
                tag = '"fixture-' + hashlib.sha256(body).hexdigest() + '"'
                if self.headers.get('If-None-Match') == tag: return self.send(304, ETag=tag)
                return self.send(200, body, ETag=tag)
            if self.path.startswith('/assets/'):
                name = self.path.removeprefix('/assets/')
                if '/' in name or name.startswith('.') or not (fixture.directory / name).is_file(): return self.send(404)
                data = fixture.data(name, mode)
                if mode == 'corrupt-transfer' and name.endswith('.apk'):
                    altered = bytearray(data); altered[len(altered) // 2] ^= 1; data = bytes(altered)
                self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers()
                with fixture.lock: fixture.active_streams += 1
                try:
                    import io
                    with io.BytesIO(data) as file:
                        sent = 0
                        while block := file.read(65536):
                            self.wfile.write(block); self.wfile.flush(); sent += len(block)
                            if name.endswith('.apk'):
                                if mode == 'truncated' and sent >= 262144: self.close_connection = True; return
                                if mode == 'slow': time.sleep(.05)
                                if mode == 'stall': time.sleep(35)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
                finally:
                    with fixture.lock: fixture.active_streams -= 1
                return
            self.send(404)
    return Handler

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory', type=Path, required=True)
    p.add_argument('--cert', type=Path, required=True)
    p.add_argument('--key', type=Path, required=True)
    a = p.parse_args()
    fixture = Fixture(a.directory)
    server = ThreadingHTTPServer(('127.0.0.1', 18443), handler(fixture))
    server.daemon_threads = True
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(a.cert, a.key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    print('Local update fixture listening.', flush=True)
    server.serve_forever()
if __name__ == '__main__': main()
