"""Telegram delivery for the live bot: each finished clip goes to the user's phone, with the post text ready.

Setup: create a bot with @BotFather, put TELEGRAM_BOT_TOKEN=... in .env, then send /start to the bot.
The first chat that sends /start becomes the owner (saved in outbox/live/.telegram-chat); every other chat is
ignored. Commands: /status, /ajuda.

Only the Telegram Bot API is used (api.telegram.org). The token is never printed or logged.
"""
import json
import mimetypes
import os
import subprocess
import threading
import time
import urllib.request
import uuid
from pathlib import Path

from .config import ROOT

API = "https://api.telegram.org/bot{token}/{method}"
LIMIT = 49 * 1024 * 1024  # bots may upload up to 50 MB
CHAT_FILE = ROOT / "outbox" / "live" / ".telegram-chat"


def post_text(title, credit, hashtags):
    """Same shape as the phone app: title, credit to the streamer, hashtags."""
    tags = []
    for t in str(hashtags or "").replace(",", " ").split():
        t = "#" + t.strip().lstrip("#").lower()
        if len(t) > 1 and t not in tags:
            tags.append(t)
    who = str(credit or "").strip()
    if who and not who.startswith("@"):
        who = "@" + who
    parts = [p for p in (title.strip(), f"Créditos: {who}" if who else "") if p]
    text = "\n".join(parts)
    if tags:
        text = (text + "\n\n" if text else "") + " ".join(tags)
    return text


class Telegram(threading.Thread):
    def __init__(self, status=None):
        super().__init__(daemon=True)
        self.token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
        self.status = status or (lambda: "Sem informações.")
        self.chat = os.environ.get("TELEGRAM_CHAT_ID", "").strip() or self._saved_chat()
        self.offset = 0
        self.stop = threading.Event()
        self.last_error = {}

    @property
    def enabled(self):
        return bool(self.token)

    # ---------- API ----------
    def _call(self, method, data=None, files=None, timeout=60):
        url = API.format(token=self.token, method=method)
        if files:
            body, ctype = _multipart(data or {}, files)
        else:
            body, ctype = json.dumps(data or {}).encode(), "application/json"
        req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            out = json.loads(r.read())
        if not out.get("ok"):
            raise RuntimeError(out.get("description", "erro do Telegram"))
        return out["result"]

    def _saved_chat(self):
        try:
            return CHAT_FILE.read_text().strip()
        except OSError:
            return ""

    # ---------- sending ----------
    def send(self, text):
        if not (self.enabled and self.chat):
            return
        try:
            self._call("sendMessage", {"chat_id": self.chat, "text": text[:4000], "disable_web_page_preview": True})
        except Exception as e:
            print(f"[telegram] mensagem não enviada: {_safe(e, self.token)}", flush=True)

    def send_error(self, key, text, every=600):
        """Errors repeat while something stays broken: send each kind at most every 10 minutes."""
        if time.time() - self.last_error.get(key, 0) > every:
            self.last_error[key] = time.time()
            self.send("⚠️ " + text)

    def send_clip(self, path, caption):
        if not (self.enabled and self.chat):
            return
        path = Path(path)
        try:
            src = path if path.stat().st_size <= LIMIT else _shrink(path)
            self._call("sendVideo", {"chat_id": self.chat, "caption": caption[:1024], "supports_streaming": "true"},
                       {"video": src}, timeout=600)
            if src != path:
                src.unlink(missing_ok=True)
        except Exception as e:
            print(f"[telegram] clipe não enviado: {_safe(e, self.token)}", flush=True)
            self.send(f"Clipe pronto, mas não consegui enviar o vídeo ({_safe(e, self.token)[:150]}). "
                      f"Ele ficou salvo no servidor: {path.name}")

    # ---------- commands ----------
    def run(self):
        if not self.enabled:
            return
        while not self.stop.is_set():
            try:
                ups = self._call("getUpdates", {"offset": self.offset, "timeout": 50}, timeout=70)
            except Exception as e:
                print(f"[telegram] sem conexão: {_safe(e, self.token)}", flush=True)
                self.stop.wait(15)
                continue
            for u in ups:
                self.offset = u["update_id"] + 1
                msg = u.get("message") or {}
                chat = str((msg.get("chat") or {}).get("id", ""))
                text = (msg.get("text") or "").strip().split("@")[0].lower()
                if not chat:
                    continue
                if not self.chat and text == "/start":
                    self.chat = chat
                    CHAT_FILE.parent.mkdir(parents=True, exist_ok=True)
                    CHAT_FILE.write_text(chat)
                    self.send("✅ Pronto! Os clipes das lives vão chegar aqui.\n\n" + HELP)
                    continue
                if chat != self.chat:
                    continue  # someone else found the bot: ignore
                if text in ("/status", "status"):
                    self.send(self.status())
                elif text in ("/start", "/ajuda", "/help", "ajuda"):
                    self.send(HELP)


HELP = ("Comandos:\n/status – o que estou vigiando agora\n/ajuda – esta mensagem\n\n"
        "Quando a live bombar, o clipe chega aqui com a legenda pronta. Segure o vídeo → Salvar na galeria, "
        "copie o texto e poste.")


def _shrink(path):
    """Re-encode a clip that is over Telegram's 50 MB bot limit (720p, keeps the audio)."""
    out = path.with_name(path.stem + "-tg.mp4")
    for crf in (26, 30, 34):
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(path), "-vf", "scale=720:-2", "-c:v", "libx264",
                        "-preset", "veryfast", "-crf", str(crf), "-c:a", "aac", "-b:a", "128k",
                        "-movflags", "+faststart", str(out)], check=True)
        if out.stat().st_size <= LIMIT:
            return out
    return out


def _multipart(fields, files):
    boundary = uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    for k, p in files.items():
        p = Path(p)
        ctype = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{p.name}"\r\n'
                     f"Content-Type: {ctype}\r\n\r\n".encode() + p.read_bytes() + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def _safe(e, token):
    """Error text without the token (URLs in urllib errors can contain it)."""
    s = str(e)
    return s.replace(token, "***") if token else s
