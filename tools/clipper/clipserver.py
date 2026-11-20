"""Hands the live bot's clips to the phone app.

The bot records a manifest of finished clips (outbox/live/clips.json); this small HTTP server lets the app list and
download them. Every request needs the code the user chose (APP_CODE in .env), sent as the X-Code header. Clips are
read-only: nothing on the server can be changed through it.

  GET /api/status   -> {"channels": [...], "clips": n}
  GET /api/clips    -> [{"id", "channel", "title", "caption", "created", "bytes", "score"?, "why"?}, ...] (newest last)
  GET /api/clip/ID  -> the MP4
  GET /api/raw/ID   -> the raw cut around it (deleted here once sent: the app keeps it)
  GET /api/keys     -> {"groq", "coarena"}: only when the server listens on 127.0.0.1 (bot and app on the same phone),
                       so the app can fill in the keys typed into the bot once instead of asking for them again
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


def add_clip(path, channel, title, caption, score=None, why="", raw=None, raw_start=0.0, raw_end=0.0):
    """Called by the bot when a clip is ready. raw: the uncut video around it (original quality), with the clip at
    raw_start..raw_end seconds; the app downloads it once to let the user move the start and the end."""
    path = Path(path)
    _drop_old_raws()
    with _lock:
        clips = _read()
        cid = datetime.now().strftime("%Y%m%d%H%M%S") + f"-{len(clips) % 1000:03d}"
        extra = {"raw": str(Path(raw).relative_to(ROOT)), "raw_start": round(float(raw_start), 2),
                 "raw_end": round(float(raw_end), 2)} if raw and Path(raw).exists() else {}
        clips.append({**extra, "id": cid, "channel": channel, "title": title, "caption": caption, "score": score, "why": why,
                      "created": datetime.now().isoformat(timespec="seconds"),
                      "bytes": path.stat().st_size, "path": str(path.relative_to(ROOT))})
        clips = clips[-500:]  # the newest 500 are enough for the app to catch up
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        tmp = MANIFEST.with_suffix(".tmp")
        tmp.write_text(json.dumps(clips, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(MANIFEST)
    return cid


def _drop_old_raws(hours=24):
    """Raw cuts are big (~1 MB per second): the ones the app never fetched go after a day."""
    import time
    for f in (ROOT / "outbox" / "live").glob("*/*/*-bruto.mp4"):
        try:
            if time.time() - f.stat().st_mtime > hours * 3600:
                f.unlink()
        except OSError:
            pass


def _public(c):
    out = {k: c[k] for k in ("id", "channel", "title", "caption", "created", "bytes")}
    out.update({k: c[k] for k in ("score", "why") if c.get(k) not in (None, "")})
    if c.get("raw") and (ROOT / c["raw"]).is_file():
        out.update(raw=True, raw_start=c["raw_start"], raw_end=c["raw_end"])
    return out


def serve(code, port=8080, status=lambda: [], host="0.0.0.0"):
    """Run in a background thread. Refuses to start without a code of at least 8 characters."""
    if code in ("TROQUE_AQUI", "invente_um_codigo_secreto"):
        print("[servidor] APP_CODE ainda é o exemplo do script: troque por uma senha sua. Servidor de clipes desligado.",
              flush=True)
        return None
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
            if self.path == "/api/keys":
                if host != "127.0.0.1" or self.client_address[0] != "127.0.0.1":
                    return self._send(403, {"error": "só no mesmo celular"})
                return self._send(200, {"groq": os.environ.get("GROQ_API_KEY", "").strip(),
                                        "coarena": os.environ.get("COARENA_API_KEY", "").strip()})
            if self.path == "/api/clips":
                return self._send(200, [_public(c) for c in _read()])
            m = re.fullmatch(r"/api/(clip|raw)/([\w-]{1,40})", self.path)
            if m:
                kind = m.group(1)
                c = next((c for c in _read() if c["id"] == m.group(2)), None)
                rel = (c or {}).get("path" if kind == "clip" else "raw")
                f = (ROOT / rel).resolve() if rel else None
                if not f or not f.is_file() or ROOT.resolve() not in f.parents:
                    return self._send(404, {"error": "clipe não encontrado"})
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(f.stat().st_size))
                self.end_headers()
                with open(f, "rb") as fh:
                    while chunk := fh.read(1 << 20):
                        self.wfile.write(chunk)
                if kind == "raw":  # the app has its copy now: free the phone's space
                    f.unlink(missing_ok=True)
                return None
            return self._send(404, {"error": "não existe"})

    srv = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[servidor] entregando clipes para o app em {host}:{port}", flush=True)
    return srv
