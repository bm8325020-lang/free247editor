"""Hands the live bot's clips to the phone app.

The bot records a manifest of finished clips (outbox/live/clips.json); this small HTTP server lets the app list and
download them. Every request needs the code the user chose (APP_CODE in .env), sent as the X-Code header. Clips are
read-only: nothing on the server can be changed through it.

  GET /api/status   -> {"channels": [...], "clips": n}
  GET /api/clips    -> [{"id", "channel", "title", "caption", "created", "bytes"}, ...] (newest last)
  GET /api/clip/ID  -> the MP4
"""
import hmac
import json
import os
import re
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .config import ROOT

MANIFEST = ROOT / "outbox" / "live" / "clips.json"
_lock = threading.Lock()


def _read():
    try:
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def add_clip(path, channel, title, caption):
    """Called by the bot when a clip is ready."""
    path = Path(path)
    with _lock:
        clips = _read()
        cid = datetime.now().strftime("%Y%m%d%H%M%S") + f"-{len(clips) % 1000:03d}"
        clips.append({"id": cid, "channel": channel, "title": title, "caption": caption,
                      "created": datetime.now().isoformat(timespec="seconds"),
                      "bytes": path.stat().st_size, "path": str(path.relative_to(ROOT))})
        clips = clips[-500:]  # the newest 500 are enough for the app to catch up
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        tmp = MANIFEST.with_suffix(".tmp")
        tmp.write_text(json.dumps(clips, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(MANIFEST)
    return cid


def _public(c):
    return {k: c[k] for k in ("id", "channel", "title", "caption", "created", "bytes")}


def serve(code, port=8080, status=lambda: []):
    """Run in a background thread. Refuses to start without a code of at least 8 characters."""
    if not code or len(code) < 8:
        print("[servidor] APP_CODE ausente ou curto (mínimo 8 caracteres): o app não vai conseguir buscar os clipes.",
              flush=True)
        return None

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):  # no request logs (they would only add noise)
            pass

        def _send(self, status, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if not hmac.compare_digest(self.headers.get("X-Code", ""), code):
                return self._send(401, {"error": "código errado"})
            if self.path == "/api/status":
                return self._send(200, {"channels": status(), "clips": len(_read())})
            if self.path == "/api/clips":
                return self._send(200, [_public(c) for c in _read()])
            m = re.fullmatch(r"/api/clip/([\w-]{1,40})", self.path)
            if m:
                c = next((c for c in _read() if c["id"] == m.group(1)), None)
                f = (ROOT / c["path"]).resolve() if c else None
                if not f or not f.is_file() or ROOT.resolve() not in f.parents:
                    return self._send(404, {"error": "clipe não encontrado"})
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(f.stat().st_size))
                self.end_headers()
                with open(f, "rb") as fh:
                    while chunk := fh.read(1 << 20):
                        self.wfile.write(chunk)
                return None
            return self._send(404, {"error": "não existe"})

    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[servidor] entregando clipes para o app na porta {port}", flush=True)
    return srv
