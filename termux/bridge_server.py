#!/usr/bin/env python3
import json
import os
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = '127.0.0.1'
PORT = 8765


def run(cmd: str, timeout: int = 120):
    p = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
    return p.returncode, (p.stdout or '').strip(), (p.stderr or '').strip()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, payload: dict):
        data = json.dumps(payload).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self._send(200, {'ok': True, 'service': 'termux-bridge'})
            return
        if self.path == '/models':
            code, out, err = run('openclaw models list', timeout=60)
            self._send(200 if code == 0 else 500, {'ok': code == 0, 'output': out, 'error': err})
            return
        self._send(404, {'error': 'not_found'})

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        raw = self.rfile.read(size).decode('utf-8') if size else '{}'
        body = json.loads(raw or '{}')

        if self.path == '/install/openclaw':
            script = os.path.expanduser('~/openclaw-mobile/termux/install_openclaw.sh')
            code, out, err = run(f'bash {script}', timeout=900)
            self._send(200 if code == 0 else 500, {'ok': code == 0, 'output': out[-4000:], 'error': err[-2000:]})
            return

        if self.path == '/auth':
            provider = body.get('provider', '').strip()
            token = body.get('token', '').strip()
            if not provider or not token:
                self._send(400, {'error': 'provider_and_token_required'})
                return
            safe = token.replace('"', '\\"')
            code, out, err = run(f'openclaw models auth paste-token --provider {provider} --token "{safe}"', timeout=120)
            self._send(200 if code == 0 else 500, {'ok': code == 0, 'output': out, 'error': err})
            return

        self._send(404, {'error': 'not_found'})


if __name__ == '__main__':
    HTTPServer((HOST, PORT), Handler).serve_forever()
