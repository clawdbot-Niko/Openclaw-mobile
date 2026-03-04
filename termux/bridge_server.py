#!/usr/bin/env python3
"""Termux bridge server for OpenClaw Mobile.

Runs on-device (Termux Android app) and exposes a tiny local HTTP API so the
Android UI can:
- check /health
- start install flow (/install/all/start)
- read progress (/progress)
- stream logs incrementally (/logs?offset=...)
- download full logs (/logs/download)

Design goals:
- works on low-end devices
- never loses logs (persist to file)
- avoids huge JSON payloads (offset-based log streaming)
"""

from __future__ import annotations

import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs
from http.server import HTTPServer

HOST = "127.0.0.1"
PORT = 8765

BASE_DIR = os.path.expanduser("~/openclaw-mobile/termux")
LOG_PATH = os.path.join(BASE_DIR, "install.log")
PID_PATH = os.path.join(BASE_DIR, "bridge.pid")

os.makedirs(BASE_DIR, exist_ok=True)

_state = {
    "running": False,
    "phase": "idle",
    "detail": "",
    "bridge": {"percent": 100},
    "ubuntu": {"percent": 0},
    "openclaw": {"percent": 0},
    # Tail in memory for quick UI refresh; full log is persisted in LOG_PATH.
    "tail": [],
    "tailMax": 400,
    "startedAt": None,
    "endedAt": None,
}
_state_lock = threading.Lock()


def _now_ms() -> int:
    return int(time.time() * 1000)


def _append_log(line: str) -> None:
    line = str(line).rstrip("\n")
    ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
    out = f"[{ts}] {line}"
    try:
        with open(LOG_PATH, "a", encoding="utf-8", errors="replace") as f:
            f.write(out + "\n")
    except Exception:
        # last resort: ignore file errors
        pass

    with _state_lock:
        _state["tail"].append(out)
        maxn = int(_state.get("tailMax", 400) or 400)
        if len(_state["tail"]) > maxn:
            _state["tail"] = _state["tail"][-maxn:]


def _set_phase(phase: str, detail: str = "") -> None:
    with _state_lock:
        _state["phase"] = phase
        _state["detail"] = detail


def _setp(key: str, pct: int) -> None:
    pct = max(0, min(100, int(pct)))
    with _state_lock:
        if key not in _state:
            _state[key] = {}
        if not isinstance(_state[key], dict):
            _state[key] = {}
        _state[key]["percent"] = pct


def _run_stream(cmd: str, timeout_sec: int = 3600) -> int:
    """Run cmd and stream combined stdout/stderr to log."""
    _append_log(f"$ {cmd}")
    start = time.time()
    p = subprocess.Popen(
        cmd,
        shell=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
    )

    try:
        assert p.stdout is not None
        for line in p.stdout:
            _append_log(line.rstrip("\n"))
            if time.time() - start > timeout_sec:
                _append_log("TIMEOUT: killing process")
                p.kill()
                return 124
        return int(p.wait(timeout=10) or 0)
    except subprocess.TimeoutExpired:
        _append_log("TIMEOUT(wait): killing process")
        p.kill()
        return 124


def _reset_logs() -> None:
    try:
        if os.path.exists(LOG_PATH):
            os.remove(LOG_PATH)
    except Exception:
        pass
    with _state_lock:
        _state["tail"] = []


def _install_worker() -> None:
    with _state_lock:
        if _state["running"]:
            return
        _state["running"] = True
        _state["startedAt"] = _now_ms()
        _state["endedAt"] = None

    _reset_logs()
    _append_log("OpenClaw Mobile: starting install")

    try:
        # Phase 1: Ubuntu
        _set_phase("ubuntu", "Instalando Ubuntu (proot-distro)")
        _setp("ubuntu", 5)
        code = _run_stream("pkg update -y", timeout_sec=1800)
        if code != 0:
            raise RuntimeError(f"pkg update failed (code {code})")

        _setp("ubuntu", 15)
        code = _run_stream("pkg install -y proot-distro", timeout_sec=1800)
        if code != 0:
            raise RuntimeError(f"pkg install proot-distro failed (code {code})")

        _setp("ubuntu", 35)
        code = _run_stream("proot-distro install ubuntu", timeout_sec=7200)
        if code != 0:
            raise RuntimeError(f"proot-distro install ubuntu failed (code {code})")

        _setp("ubuntu", 100)

        # Phase 2: OpenClaw in Ubuntu
        _set_phase("openclaw", "Instalando OpenClaw dentro de Ubuntu")
        _setp("openclaw", 10)

        # Update base packages in Ubuntu (keep minimal; avoid full-upgrade)
        code = _run_stream(
            'proot-distro login ubuntu -- bash -lc "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y curl ca-certificates git"',
            timeout_sec=3600,
        )
        if code != 0:
            raise RuntimeError(f"ubuntu apt install prereqs failed (code {code})")

        _setp("openclaw", 40)
        # Install OpenClaw (official script)
        code = _run_stream(
            'proot-distro login ubuntu -- bash -lc "curl -fsSL https://openclaw.ai/install.sh | bash -s -- --no-onboard"',
            timeout_sec=7200,
        )
        if code != 0:
            raise RuntimeError(f"openclaw install script failed (code {code})")

        _setp("openclaw", 75)
        # Basic local configure (idempotent)
        code = _run_stream(
            'proot-distro login ubuntu -- bash -lc "openclaw configure --mode local || true"',
            timeout_sec=1800,
        )
        _setp("openclaw", 100)

        _set_phase("done", "Ubuntu + OpenClaw listos")
        _append_log("DONE")

    except Exception as e:
        _set_phase("error", str(e))
        _append_log(f"ERROR: {e}")

    finally:
        with _state_lock:
            _state["running"] = False
            _state["endedAt"] = _now_ms()


class _ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code: int, payload: dict) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_text(self, code: int, text: str, content_type: str = "text/plain; charset=utf-8") -> None:
        data = (text or "").encode("utf-8", errors="replace")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # keep stdout clean
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path == "/health":
            return self._send_json(200, {"ok": True, "service": "termux-bridge", "ts": _now_ms()})

        if path == "/progress":
            with _state_lock:
                payload = dict(_state)
                # expose a single string for UI quick display
                payload["tailText"] = "\n".join(payload.get("tail", []))
            return self._send_json(200, payload)

        if path == "/logs":
            # offset-based incremental fetch (bytes)
            try:
                offset = int((qs.get("offset") or ["0"])[0])
            except Exception:
                offset = 0
            if offset < 0:
                offset = 0

            try:
                size = os.path.getsize(LOG_PATH) if os.path.exists(LOG_PATH) else 0
                if not os.path.exists(LOG_PATH):
                    return self._send_json(200, {"ok": True, "offset": 0, "nextOffset": 0, "size": 0, "text": ""})

                # cap chunk to avoid giant responses
                max_bytes = 200_000
                with open(LOG_PATH, "rb") as f:
                    f.seek(offset)
                    chunk = f.read(max_bytes)
                    next_off = offset + len(chunk)
                text = chunk.decode("utf-8", errors="replace")
                return self._send_json(200, {"ok": True, "offset": offset, "nextOffset": next_off, "size": size, "text": text})
            except Exception as e:
                return self._send_json(500, {"ok": False, "error": str(e)})

        if path == "/logs/download":
            # stream full log file
            if not os.path.exists(LOG_PATH):
                return self._send_text(404, "log_not_found")

            try:
                st = os.stat(LOG_PATH)
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(st.st_size))
                self.send_header("Content-Disposition", "attachment; filename=install.log")
                self.end_headers()
                with open(LOG_PATH, "rb") as f:
                    while True:
                        b = f.read(64 * 1024)
                        if not b:
                            break
                        self.wfile.write(b)
                return
            except Exception as e:
                return self._send_json(500, {"ok": False, "error": str(e)})

        return self._send_json(404, {"error": "not_found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/install/all/start":
            with _state_lock:
                already = _state["running"]
            if already:
                return self._send_json(200, {"ok": True, "message": "already_running"})

            t = threading.Thread(target=_install_worker, daemon=True)
            t.start()
            return self._send_json(200, {"ok": True, "message": "started"})

        return self._send_json(404, {"error": "not_found"})


def main() -> None:
    # write pid
    try:
        with open(PID_PATH, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
    except Exception:
        pass

    _append_log(f"Bridge server starting on http://{HOST}:{PORT}")
    srv = _ThreadingHTTPServer((HOST, PORT), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    main()
