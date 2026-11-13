"""Live clipper: watch Twitch / YouTube lives and cut the hype moments as they happen.

Per channel:
  Recorder  streamlink -> ffmpeg writes 10 s segments; only the last few minutes are kept (ring buffer).
  Chat      read-only chat (Twitch over WebSocket, YouTube via chat-downloader): messages per second and
            reaction emotes/words, compared with that channel's own recent baseline.
  Audio     loudness of every new segment, compared with the live's own recent baseline.
  Detector  when chat and audio spike together (or one spikes very hard), mark the moment, wait for the
            reaction to finish, cut ~20 s before / ~10 s after, and render it with the normal engine
            (webcam layout, captions, hook). Optional: also create the official Twitch clip.

Only channels with a written authorization in live-canais.json are watched.
"""
import collections
import csv
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

import numpy as np

from .config import ROOT, load_config, load_env
from .gaming import cap_length, hook_from, snap
from .layout import choose_layout, facecam_from_samples, facecam_sample
from .media import probe, slugify
from .render import render_clip
from .report import write_report
from .transcribe import transcribe_ranges

SEG = 10  # seconds per recorded segment
HOP = 0.1
REACTION = 3.0  # chat reacts ~3 s after viewers see the moment
EMOTES = {  # extra weight per message; matched case-insensitively as whole tokens
    "kekw": 1.5, "lul": 1.0, "lol": 1.0, "omegalul": 2.0, "lmao": 1.5, "icant": 1.5, "pog": 1.0, "pogchamp": 1.0,
    "pogu": 1.0, "poggers": 1.0, "monkas": 1.0, "monkaw": 1.5, "w": 0.5, "wwww": 1.0, "clip": 3.0, "clipa": 3.0,
    "clipped": 2.0, "kkkk": 1.5, "kkkkk": 1.5, "kkkkkk": 2.0, "omg": 1.0, "wtf": 1.0, "nooo": 1.0, "????": 1.0,
    "aaaa": 1.0, "sadge": 0.5, "pepelaugh": 1.5, "kappa": 0.3,
}
TOKEN = re.compile(r"[\wÀ-ÿ?!]+", re.UNICODE)

DEFAULTS = {
    "buffer_segundos": 180,
    "antes": 20,
    "depois": 10,
    "intervalo_minimo": 90,
    "limiar": 3.0,
    "qualidade": "best",
    "clip_oficial_twitch": False,
}


def log(ch, msg):
    print(f"{datetime.now():%H:%M:%S} [{ch}] {msg}", file=sys.stderr, flush=True)


def proxy():
    p = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    return urllib.parse.urlparse(p) if p else None


def message_weight(text):
    toks = [t.lower() for t in TOKEN.findall(text)]
    w = 1.0
    for t in toks:
        if t in EMOTES:
            w += EMOTES[t]
        elif re.fullmatch(r"k{4,}|(ha){3,}|\?{3,}|w{4,}", t):
            w += 1.5
    return min(w, 6.0)


class TwitchChat(threading.Thread):
    """Anonymous, read-only Twitch chat over WebSocket (works where the IRC port is blocked)."""

    def __init__(self, login, sink):
        super().__init__(daemon=True)
        self.login, self.sink, self.stop = login.lower(), sink, threading.Event()

    def run(self):
        import websocket
        while not self.stop.is_set():
            try:
                kw = {"timeout": 5}
                p = proxy()
                if p:
                    kw.update(http_proxy_host=p.hostname, http_proxy_port=p.port, proxy_type="http")
                ca = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
                if ca:
                    kw["sslopt"] = {"ca_certs": ca}
                ws = websocket.create_connection("wss://irc-ws.chat.twitch.tv:443", **kw)
                ws.send("PASS SCHMOOPIIE")
                ws.send(f"NICK justinfan{int(time.time()) % 90000 + 10000}")
                ws.send(f"JOIN #{self.login}")
                while not self.stop.is_set():
                    try:
                        data = ws.recv()
                    except websocket.WebSocketTimeoutException:
                        continue
                    if not data:
                        break
                    for line in data.split("\r\n"):
                        if line.startswith("PING"):
                            ws.send("PONG :tmi.twitch.tv")
                        elif " PRIVMSG #" in line:
                            rest = line.split(" PRIVMSG #", 1)[1]
                            text = rest.split(" :", 1)[1] if " :" in rest else ""
                            self.sink(time.time(), message_weight(text), text)
                ws.close()
            except Exception as e:
                log(self.login, f"chat reconnecting ({type(e).__name__})")
                time.sleep(5)


class YouTubeChat(threading.Thread):
    def __init__(self, url, sink):
        super().__init__(daemon=True)
        self.url, self.sink, self.stop = url, sink, threading.Event()

    def run(self):
        from chat_downloader import ChatDownloader
        while not self.stop.is_set():
            try:
                for m in ChatDownloader().get_chat(self.url):
                    if self.stop.is_set():
                        return
                    text = m.get("message") or ""
                    self.sink(time.time(), message_weight(text), text)
            except Exception as e:
                log("youtube", f"chat reconnecting ({type(e).__name__}: {str(e)[:80]})")
                time.sleep(10)


def _smooth3(x):
    return np.convolve(np.pad(x, 1, mode="edge"), np.ones(3) / 3, mode="valid")


class SimulatedChat(threading.Thread):
    """Plays scripted chat for a simulated live: [[seconds, messages per second, "text"], ...] ranges."""

    def __init__(self, script, sink):
        super().__init__(daemon=True)
        self.script, self.sink, self.stop = script, sink, threading.Event()

    def run(self):
        t0 = time.time()
        while not self.stop.is_set():
            t = time.time() - t0
            rate, text = 1.0, "chat normal"
            for a, b, r, msg in self.script:
                if a <= t < b:
                    rate, text = r, msg
            for _ in range(int(rate) + (1 if (rate % 1) > np.random.rand() else 0)):
                self.sink(time.time(), message_weight(text), text)
            time.sleep(1)


def robust_z(series, window, recent=3):
    """How far the last `recent` values sit above the preceding window (median / MAD, Poisson-floored)."""
    x = np.asarray(series, dtype=float)
    if len(x) < 30 + recent:
        return 0.0
    base = x[-window - recent:-recent] if len(x) > window + recent else x[:-recent]
    med = float(np.median(base))
    mad = float(np.median(np.abs(base - med))) * 1.4826
    # counts are noisy when chat is slow: never trust a spread smaller than Poisson noise
    scale = max(mad, math.sqrt(max(med, 0.0)), 1.0)
    return (float(np.mean(x[-recent:])) - med) / scale


class Channel(threading.Thread):
    def __init__(self, spec, opts, cfg, render_lock):
        super().__init__(daemon=True)
        self.spec, self.opts, self.cfg, self.render_lock = spec, opts, cfg, render_lock
        self.platform = spec["plataforma"].lower()
        self.name = spec["canal"]
        self.url = (f"https://www.twitch.tv/{self.name}" if self.platform == "twitch"
                    else spec.get("url") or f"https://www.youtube.com/{self.name}/live")
        self.simulated = self.platform == "simulado"  # a local file played in real time, for testing the bot
        self.key = slugify(f"{self.platform}-{self.name}")
        self.work = ROOT / "outbox" / "live" / self.key / ".buffer"
        self.stopped = threading.Event()
        self.chat = collections.deque(maxlen=200000)  # (wall time, weight, text)
        self.segments = []  # dicts: path, start, end, wall
        self.loud = []  # (stream time, dB) every HOP
        self.proc = None
        self.layout = None
        self.layout_at = 0
        self.last_clip = -1e9
        self.last_audio_checked = None
        self.pending = []
        self.made = []
        self.cam_samples = collections.deque(maxlen=60)  # one frame every ~30 s of live, for webcam detection

    # ---------- recording ----------
    def live_now(self):
        if self.simulated:
            return not getattr(self, "sim_done", False)
        r = subprocess.run(["streamlink", "--stream-url", self.url, self.opts["qualidade"]],
                           capture_output=True, text=True, timeout=60)
        return r.returncode == 0 and r.stdout.strip().startswith("http")

    def start_recorder(self):
        if self.work.exists():
            shutil.rmtree(self.work)
        self.work.mkdir(parents=True)
        sl = ["streamlink", "--stdout", "--twitch-disable-ads", "--hls-live-restart", self.url, self.opts["qualidade"]]
        if self.platform != "twitch":
            sl.remove("--twitch-disable-ads")
        ff = ["ffmpeg", "-v", "error", "-i", "pipe:0", "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-f", "segment",
              "-segment_time", str(SEG), "-segment_format", "mpegts", "-reset_timestamps", "0",
              "-segment_list", str(self.work / "list.csv"), "-segment_list_type", "csv", str(self.work / "seg_%06d.ts")]
        if self.simulated:
            sl = ["ffmpeg", "-v", "error", "-re", "-i", self.spec["url"], "-c", "copy", "-f", "mpegts", "-"]
            self.sim_done = True
            self.sim_t0 = time.time()
        self.sl = subprocess.Popen(sl, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.proc = subprocess.Popen(ff, stdin=self.sl.stdout, stderr=subprocess.DEVNULL)
        self.sl.stdout.close()
        self.segments, self.loud = [], []
        self.seg_count = 0

    def stop_recorder(self):
        for p in (getattr(self, "sl", None), self.proc):
            if p and p.poll() is None:
                p.terminate()
        self.proc = None

    def poll_segments(self):
        lst = self.work / "list.csv"
        if not lst.exists():
            return
        known = {s["path"].name for s in self.segments}
        with open(lst, newline="") as f:
            rows = list(csv.reader(f))
        for row in rows:
            if len(row) < 3 or row[0] in known:
                continue
            seg = {"path": self.work / row[0], "start": float(row[1]), "end": float(row[2]), "wall": time.time()}
            self.segments.append(seg)
            self.add_loudness(seg)
            self.seg_count += 1
            if self.seg_count % 3 == 1:  # every ~30 s
                try:
                    info = probe(seg["path"])
                    self.cam_samples.append(facecam_sample(seg["path"], info["duration"] / 2, info))
                    self.seg_info = info
                except Exception:
                    pass
        # ring buffer: drop old segments unless a pending clip still needs them
        if self.segments:
            now = self.segments[-1]["end"]
            keep_from = now - self.opts["buffer_segundos"]
            if self.pending:
                keep_from = min(keep_from, min(p["peak"] for p in self.pending) - self.opts["antes"] - SEG)
            while len(self.segments) > 2 and self.segments[0]["end"] < keep_from:
                old = self.segments.pop(0)
                old["path"].unlink(missing_ok=True)
            self.loud = [x for x in self.loud if x[0] >= now - 600]

    def add_loudness(self, seg):
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(seg["path"]), "-vn", "-ac", "1", "-ar", "8000",
                              "-f", "s16le", "-"], capture_output=True).stdout
        pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        n = int(8000 * HOP)
        k = len(pcm) // n
        if k == 0:
            return
        rms = np.sqrt(np.mean(pcm[: k * n].reshape(k, n) ** 2, axis=1) + 1e-10)
        for i, v in enumerate(20 * np.log10(rms)):
            self.loud.append((seg["start"] + i * HOP, float(v)))

    def stream_now(self):
        """Stream time of what was recorded most recently (the newest finished segment's end, aged by wall time)."""
        if not self.segments:
            return None
        s = self.segments[-1]
        return s["end"] + (time.time() - s["wall"])

    # ---------- detection ----------
    def chat_sink(self, wall, weight, text):
        self.chat.append((wall, weight, text))

    def scores(self):
        """(chat z, audio z, stream time of the audio peak). Needs 2 minutes of history before it fires."""
        now = time.time()
        sec = collections.Counter()
        for w, wt, _ in self.chat:
            if w >= now - 300:
                sec[int(now - w)] += wt
        history = now - self.chat[0][0] if self.chat else 0
        chat_series = [sec.get(i, 0.0) for i in range(300, -1, -1)][-int(min(history, 300)) - 1:]
        cz = robust_z(chat_series, 240) if history >= 120 else 0.0
        az, at = 0.0, None
        per = int(1 / HOP)
        if len(self.loud) >= 120 * per and self.segments:
            vals = np.array([v for _, v in self.loud])
            times = np.array([t for t, _ in self.loud])
            k = len(vals) // per
            secs = vals[-k * per:].reshape(k, per).mean(1)
            stimes = times[-k * per:].reshape(k, per)[:, 0]
            # audio arrives one 10 s segment at a time: look at every second of the newest segment
            n_new = min(SEG, k - 30)
            base = secs[:-n_new][-120:]
            med = float(np.median(base))
            scale = max(float(np.median(np.abs(base - med))) * 1.4826, 1.0)
            z = (_smooth3(secs)[-n_new:] - med) / scale
            i = int(np.argmax(z))
            az, at = float(z[i]), float(stimes[-n_new + i])
        return cz, az, at

    def detect(self):
        sn = self.stream_now()
        if sn is None:
            return
        cz, az, at = self.scores()
        if at is not None and self.last_audio_checked is not None and at <= self.last_audio_checked:
            az = 0.0  # this segment's audio was already judged
        if at is not None:
            self.last_audio_checked = self.segments[-1]["end"]
        score = 0.6 * max(cz, 0) + 0.4 * max(az, 0)
        strong = cz >= 6 or az >= 5
        cooled = time.time() - self.last_clip >= self.opts["intervalo_minimo"]
        if (score >= self.opts["limiar"] or strong) and cooled:
            # chat reacts a few seconds after the moment; audio says exactly when it was loud
            peak = at if (az >= cz and at is not None) else sn - REACTION
            self.last_clip = time.time()
            self.pending.append({"peak": peak, "chat_z": cz, "audio_z": az, "wall": time.time()})
            log(self.name, f"MOMENTO detectado (chat {cz:.1f}σ, áudio {az:.1f}σ) — cortando quando a reação terminar")
            if self.opts.get("clip_oficial_twitch") and self.platform == "twitch":
                threading.Thread(target=self.official_clip, daemon=True).start()

    # ---------- official Twitch clip ----------
    def official_clip(self):
        cid, tok = os.environ.get("TWITCH_CLIENT_ID"), os.environ.get("TWITCH_USER_TOKEN")
        if not cid or not tok:
            log(self.name, "clipe oficial: faltam TWITCH_CLIENT_ID e TWITCH_USER_TOKEN no .env")
            return
        h = {"Client-Id": cid, "Authorization": f"Bearer {tok}"}
        try:
            req = urllib.request.Request(f"https://api.twitch.tv/helix/users?login={self.name}", headers=h)
            uid = json.loads(urllib.request.urlopen(req, timeout=20).read())["data"][0]["id"]
            req = urllib.request.Request(f"https://api.twitch.tv/helix/clips?broadcaster_id={uid}", headers=h,
                                         method="POST")
            d = json.loads(urllib.request.urlopen(req, timeout=20).read())["data"][0]
            log(self.name, f"clipe oficial criado na Twitch: https://clips.twitch.tv/{d['id']}")
            self.official = getattr(self, "official", []) + [f"https://clips.twitch.tv/{d['id']}"]
        except Exception as e:
            log(self.name, f"clipe oficial falhou: {str(e)[:150]}")

    # ---------- cutting ----------
    def ready_pending(self):
        sn = self.stream_now()
        if sn is None:
            return
        for p in list(self.pending):
            if sn >= p["peak"] + self.opts["depois"] + SEG + 2:
                self.pending.remove(p)
                threading.Thread(target=self.cut, args=(p,), daemon=True).start()

    def cut(self, p):
        a = p["peak"] - self.opts["antes"] - 4  # room for snapping to a pause
        b = p["peak"] + self.opts["depois"] + 4
        segs = [s for s in list(self.segments) if s["end"] > a and s["start"] < b and s["path"].exists()]
        if not segs:
            log(self.name, "corte perdido: os segmentos já tinham saído do buffer")
            return
        day = datetime.now().strftime("%Y-%m-%d")
        folder = ROOT / "outbox" / "live" / self.key / day
        work = folder / ".work"
        work.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%H%M%S")
        lst = work / f"concat-{stamp}.txt"
        lst.write_text("".join(f"file '{s['path'].as_posix()}'\n" for s in segs))
        src = work / f"src-{stamp}.mp4"
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy",
                        "-fflags", "+genpts", str(src)], check=False)
        lst.unlink(missing_ok=True)
        if not src.exists():
            log(self.name, "corte falhou ao juntar os segmentos")
            return
        with self.render_lock:  # one render at a time keeps the recorders smooth
            try:
                self.render(src, segs[0]["start"], p, folder, work, stamp)
            except Exception as e:
                log(self.name, f"render falhou: {str(e)[:200]}")
            finally:
                if not self.cfg.get("keep_work"):
                    src.unlink(missing_ok=True)

    def render(self, src, offset, p, folder, work, stamp):
        info = probe(src)
        cfg = dict(self.cfg)
        peak = p["peak"] - offset
        a0, b0 = max(0.0, peak - self.opts["antes"]), min(info["duration"], peak + self.opts["depois"])
        words = transcribe_ranges(src, [(0.0, info["duration"])], cfg, work / f"words-{stamp}.json")["words"]
        a, b = cap_length(*snap(words, a0, b0, info["duration"], cfg), peak, self.opts["antes"] + self.opts["depois"] + 5)
        # webcam: judged from frames spread over the live so far (a 35 s clip alone is too little evidence);
        # re-judged every 10 minutes as more samples arrive
        if self.layout is None or time.time() - self.layout_at > 600:
            samples = [x for x in self.cam_samples if x is not None]
            if len(samples) >= 6:
                cam = facecam_from_samples(samples)
            else:
                cam = facecam_from_samples(samples + [facecam_sample(src, info["duration"] * (i + 0.5) / 20, info)
                                                      for i in range(20)])
            self.layout = choose_layout(src, info, cfg, cam=cam)
            self.layout_at = time.time()
            log(self.name, f"layout: {self.layout['kind']} (de {len(samples)} amostras da live)"
                + (", borda da webcam achada" if isinstance(cam, dict) and cam.get("rect") else ""))
        hook = hook_from(words, peak)
        clock = datetime.fromtimestamp(p["wall"]).strftime("%H:%M")
        title = hook or f"{self.name} às {clock}"
        clip = {"start": a, "end": b, "hook": hook, "title": title}
        n = len(self.made) + 1
        name = f"{stamp}-{slugify(title)[:40]}.mp4"
        meta = render_clip(src, info, words, clip, cfg, folder / name, work, self.layout)
        text = " ".join(w["text"] for w in words if a <= w["start"] < b)
        self.made.append({"rank": n, "file": name, "thumb": meta["thumb"], "title": title,
                          "score": round(min(99, 50 + 8 * max(p["chat_z"], p["audio_z"]))),
                          "reason": f"Chat {p['chat_z']:.1f}σ, áudio {p['audio_z']:.1f}σ acima do normal da live, às {clock}.",
                          "picker": "live", "duration": meta["duration"], "cuts": 0, "layout": meta["layout"],
                          "source_start": 0, "source_end": meta["duration"], "text": text})
        write_report(folder, f"{self.name} ao vivo ({datetime.now():%d/%m})", self.made,
                     {"explanation": f"{len(self.made)} momentos cortados automaticamente nesta live."})
        log(self.name, f"CLIPE PRONTO: {folder / name}")

    # ---------- main loop ----------
    def run(self):
        chat = None
        while not self.stopped.is_set():
            try:
                if not self.live_now():
                    log(self.name, "offline; checando de novo em 60 s")
                    self.stopped.wait(60)
                    continue
                log(self.name, "AO VIVO: gravando buffer e lendo o chat")
                self.start_recorder()
                if self.simulated:
                    chat = SimulatedChat(self.spec.get("chat", []), self.chat_sink)
                elif self.platform == "twitch":
                    chat = TwitchChat(self.name, self.chat_sink)
                else:
                    chat = YouTubeChat(self.url, self.chat_sink)
                chat.start()
                last_status = 0
                while not self.stopped.is_set() and self.proc.poll() is None:
                    self.poll_segments()
                    self.detect()
                    self.ready_pending()
                    if time.time() - last_status > 60:
                        cz, az, _ = self.scores()
                        msgs = sum(1 for w, _, _ in self.chat if w >= time.time() - 60)
                        log(self.name, f"ok — {len(self.segments)} segmentos no buffer, {msgs} msgs/min, "
                                       f"chat {cz:+.1f}σ, áudio {az:+.1f}σ, {len(self.made)} clipes")
                        last_status = time.time()
                    self.stopped.wait(1)
                log(self.name, "a gravação parou (live acabou ou caiu); reconectando")
            except Exception as e:
                log(self.name, f"erro: {str(e)[:200]}")
                self.stopped.wait(15)
            finally:
                if chat:
                    chat.stop.set()
                self.stop_recorder()


def load_channels():
    path = ROOT / "live-canais.json"
    if not path.exists():
        example = ROOT / "live-canais.example.json"
        raise SystemExit(f"Crie {path.name} (copie {example.name}) com os canais que você tem autorização para clipar.")
    data = json.loads(path.read_text(encoding="utf-8"))
    opts = {**DEFAULTS, **{k: v for k, v in data.items() if k != "canais"}}
    chans = []
    for c in data.get("canais", []):
        if not c.get("ativo", True):
            continue
        if not str(c.get("autorizacao", "")).strip():
            print(f"IGNORADO {c.get('canal')}: sem 'autorizacao'. Diga de onde vem a permissão (campanha, regra "
                  f"publicada pelo streamer, acordo). Clipar sem permissão gera strike.", file=sys.stderr)
            continue
        if c.get("plataforma", "").lower() not in ("twitch", "youtube", "simulado"):
            print(f"IGNORADO {c.get('canal')}: plataforma deve ser twitch ou youtube", file=sys.stderr)
            continue
        chans.append(c)
    return chans, opts


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="Clipa automaticamente os melhores momentos de lives autorizadas.")
    ap.add_argument("--limiar", type=float, help="sensibilidade (menor = mais clipes); padrão 3.0")
    ap.add_argument("--minutos", type=float, help="parar depois de N minutos (para testes)")
    a = ap.parse_args(argv)
    load_env()
    for tool in ("streamlink", "ffmpeg"):
        if not shutil.which(tool):
            raise SystemExit(f"Falta {tool}. Rode: python clip.py --setup")
    chans, opts = load_channels()
    if a.limiar is not None:
        opts["limiar"] = a.limiar
    if not chans:
        raise SystemExit("Nenhum canal autorizado em live-canais.json.")
    cfg = load_config({"mode": "gaming"})
    lock = threading.Lock()
    workers = [Channel(c, opts, cfg, lock) for c in chans]
    for w in workers:
        w.start()
    print(f"Vigiando {len(workers)} canal(is). Clipes em outbox/live/. Ctrl+C para parar.", file=sys.stderr)
    t0 = time.time()
    try:
        while any(w.is_alive() for w in workers):
            time.sleep(1)
            if a.minutos and time.time() - t0 > a.minutos * 60:
                break
    except KeyboardInterrupt:
        pass
    for w in workers:
        w.stopped.set()
    time.sleep(3)
    for w in workers:
        w.stop_recorder()
