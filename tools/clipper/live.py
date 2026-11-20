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
from .telegram import Telegram, post_text
from . import aigate, clipserver
from .transcribe import transcribe_ranges

SEG = 10  # seconds per recorded segment
HOP = 0.1
EMOTES = {  # extra weight per message; matched case-insensitively as whole tokens
    "kekw": 1.5, "lul": 1.0, "lol": 1.0, "omegalul": 2.0, "lmao": 1.5, "icant": 1.5, "pog": 1.0, "pogchamp": 1.0,
    "pogu": 1.0, "poggers": 1.0, "monkas": 1.0, "monkaw": 1.5, "w": 0.5, "wwww": 1.0, "clip": 3.0, "clipa": 3.0,
    "clipped": 2.0, "kkkk": 1.5, "kkkkk": 1.5, "kkkkkk": 2.0, "omg": 1.0, "wtf": 1.0, "nooo": 1.0, "????": 1.0,
    "aaaa": 1.0, "sadge": 0.5, "pepelaugh": 1.5, "kappa": 0.3, "wasted": 2.0, "busted": 2.0, "gg": 0.5,
}
TOKEN = re.compile(r"[\wÀ-ÿ?!]+", re.UNICODE)

DEFAULTS = {
    "buffer_segundos": 180,
    # the clip: from this many seconds before the moment to this many after it (the action often goes on well after
    # the scream: 30 s clips ended in the middle of it)
    "antes": 25,
    "depois": 35,
    # extra video kept around each clip (the raw cut), so the app can move the start and the end afterwards
    "folga": 30,
    "intervalo_minimo": 90,
    # when the chat fires, go back this many seconds from the moment the chat STARTED reacting,
    # so the whole scene is in the clip even though the chat types after it happens
    "volta_segundos": 3,
    "limiar": 2.5,
    # lives with no chat coming in (Kick; a YouTube chat that fails): audio spike that alone makes a moment
    "limiar_audio_sem_chat": 2.5,
    # ...and after this many minutes without a clip, the loudest moment of the last 3 minutes is clipped anyway
    "minutos_sem_clipe": 8,
    # lives without chat: every N seconds the AI hears the last stretch and says whether it holds a clip (0 = off).
    # Uses the free Groq plan: at most groq_por_hora / groq_por_dia seconds of audio
    "escuta_segundos": 120,
    "nota_escuta": 65,
    "groq_por_hora": 5400,
    "groq_por_dia": 24000,
    # wipe the Termux screen every N minutes (0 = never) and print a summary; the full log stays in bot.log
    "limpar_tela_minutos": 15,
    "qualidade": "best",
    "clip_oficial_twitch": False,
    # how many lives may record at the same time (0 = no limit); a phone handles one
    "max_simultaneos": 0,
    # captions on the clips (the title at the start comes from the speech either way)
    "legendas": True,
}

RECORDING = threading.BoundedSemaphore(1000)  # replaced in main() by max_simultaneos
# Channels that are live but not recording (the limit is full): name -> rank in the list (0 = top priority).
# A live higher on the list takes the recorder from a lower one (once that one has no clip waiting to finish).
WAITING = {}
_waiting_lock = threading.Lock()
# person -> channel recording them: a streamer live on Twitch and YouTube at once is recorded only once
RECORDING_PEOPLE = {}


def higher_waiting(rank, name):
    with _waiting_lock:
        return next((n for n, r in WAITING.items() if r < rank and n != name), None)


TG = None  # Telegram delivery, when TELEGRAM_BOT_TOKEN is set


def notify_error(ch, msg):
    if TG:
        TG.send_error(ch, f"{ch}: {msg}")


LOG_FILE = ROOT / "outbox" / "live" / "bot.log"
_log_lock = threading.Lock()


def log(ch, msg):
    line = f"{datetime.now():%H:%M:%S} [{ch}] {msg}"
    print(line, file=sys.stderr, flush=True)
    # also on disk: the Termux screen only keeps the last lines. Read it with:  tail -100 ~/clipador/outbox/live/bot.log
    try:
        with _log_lock:
            LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
            if LOG_FILE.exists() and LOG_FILE.stat().st_size > 5_000_000:  # keep it small: the last ~5 MB
                LOG_FILE.replace(LOG_FILE.with_suffix(".old.log"))
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(f"{datetime.now():%Y-%m-%d} {line}\n")
    except OSError:
        pass


def proxy():
    p = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    return urllib.parse.urlparse(p) if p else None


# chat bots and commands make bursts that are not moments (giveaways "!sorteio", sub trains, alerts)
BOTS = {"nightbot", "streamelements", "streamlabs", "moobot", "fossabot", "wizebot", "soundalerts", "botisimo",
        "sery_bot", "kofistreambot", "pokemoncommunitygame", "blerp", "own3d", "streamstickers", "tangiabot"}


def message_weight(text, nick=""):
    """How much a chat message says 'something just happened': reactions count, plain talk barely does."""
    if nick.lower() in BOTS or text.lstrip().startswith("!"):
        return 0.0
    toks = [t.lower() for t in TOKEN.findall(text)]
    # a plain message counts little: a big chat is always busy; the burst that matters is of reactions
    w = 0.25
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
                            nick = line[1:].split("!", 1)[0] if line.startswith(":") else ""
                            rest = line.split(" PRIVMSG #", 1)[1]
                            text = rest.split(" :", 1)[1] if " :" in rest else ""
                            self.sink(time.time(), message_weight(text, nick), text)
                ws.close()
            except Exception as e:
                log(self.login, f"chat reconnecting ({type(e).__name__})")
                time.sleep(5)


class YouTubeChat(threading.Thread):
    def __init__(self, url, sink):
        super().__init__(daemon=True)
        self.url, self.sink, self.stop = url, sink, threading.Event()

    def run(self):
        try:
            from chat_downloader import ChatDownloader
        except ImportError:
            log("youtube", "sem leitor do chat (pip install chat-downloader): clipando só pelo áudio")
            return
        while not self.stop.is_set():
            try:
                for m in ChatDownloader().get_chat(self.url):
                    if self.stop.is_set():
                        return
                    text = m.get("message") or ""
                    nick = (m.get("author") or {}).get("name", "")
                    self.sink(time.time(), message_weight(text, nick), text)
            except Exception as e:
                log("youtube", f"chat reconnecting ({type(e).__name__}: {str(e)[:80]})")
                time.sleep(10)


def chat_onset(series, window=240, max_back=20):
    """Seconds ago that the current chat burst began: walk back while the chat stays above its normal level.

    series: weighted messages per second, oldest -> newest. Short dips of one second don't end the burst.
    """
    x = np.asarray(series, dtype=float)
    if len(x) < 30:
        return 0
    base = x[-window - 15:-15] if len(x) > window + 15 else x[:-15]
    med = float(np.median(base))
    scale = max(float(np.median(np.abs(base - med))) * 1.4826, math.sqrt(max(med, 0.0)), 1.0)
    above = x > med + scale
    onset, dips = 0, 0
    for back in range(1, min(max_back, len(x)) + 1):
        if above[-back]:
            onset, dips = back - 1, 0
        else:
            dips += 1
            if dips > 1:
                break
    return onset


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
                    else f"https://kick.com/{self.name}" if self.platform == "kick"
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
        self.live = False
        self.live_since = None
        self.rank = 0  # set by main(): position in the list
        self.context = collections.deque(maxlen=8)  # one-line summaries of the live so far, for the AI
        # the same streamer on several platforms shares one "pessoa": only one of those lives records
        self.person = str(spec.get("pessoa") or f"{self.platform}:{self.name}").lower()

    # ---------- recording ----------
    def live_now(self):
        if self.simulated:
            return not getattr(self, "sim_done", False)
        # inside this process, not one "streamlink" program per channel per minute: Android 12+ kills apps that start
        # too many processes ("Process completed (signal 9)" in Termux), and 17 channels started one every few seconds
        try:
            from streamlink import Streamlink
        except ImportError:
            r = subprocess.run(["streamlink", "--stream-url", self.url, self.opts["qualidade"]],
                               capture_output=True, text=True, timeout=60)
            return r.returncode == 0 and r.stdout.strip().startswith("http")
        try:
            session = Streamlink()
            session.set_option("http-timeout", 30)
            streams = session.streams(self.url)
        except Exception:
            return False
        return bool(streams) and (self.opts["qualidade"] in streams or "best" in streams)

    def start_recorder(self):
        if self.work.exists():
            shutil.rmtree(self.work)
        self.work.mkdir(parents=True)
        sl = ["streamlink", "--stdout", "--twitch-disable-ads", "--hls-live-restart", self.url, self.opts["qualidade"]]
        if self.platform != "twitch":
            # YouTube keeps hours of DVR: "restart" would record from the start of the live instead of now
            sl.remove("--twitch-disable-ads")
            sl.remove("--hls-live-restart")
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
        self.seen_segments = set()  # every segment ever listed, also the ones already dropped from the buffer
        self.spans = []  # stream seconds of the clips made from this recording (no clip twice)
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
        # the list keeps every segment ffmpeg ever wrote: remember them all, or the ones dropped from the buffer came back
        # at the end of the list (out of order), which broke the clock of the live and ran ffmpeg on deleted files
        known = getattr(self, "seen_segments", None)
        if known is None:
            known = self.seen_segments = {s["path"].name for s in self.segments}
        with open(lst, newline="") as f:
            rows = list(csv.reader(f))
        for row in rows:
            if len(row) < 3 or row[0] in known:
                continue
            known.add(row[0])
            seg = {"path": self.work / row[0], "start": float(row[1]), "end": float(row[2]), "wall": time.time()}
            self.segments.append(seg)
            self.add_loudness(seg)
            self.seg_count += 1
            if self.seg_count % 3 == 1:  # every ~30 s
                try:
                    info = probe(seg["path"])
                    self.cam_samples.append(facecam_sample(seg["path"], info["duration"] / 2, info, exact=True))
                    self.seg_info = info
                except Exception:
                    pass
        # ring buffer: drop old segments unless a pending clip still needs them
        if self.segments:
            now = self.segments[-1]["end"]
            keep_from = now - self.opts["buffer_segundos"]
            if self.pending:
                keep_from = min(keep_from, min(p["peak"] for p in self.pending) - self.opts["antes"]
                                - self.opts.get("folga", 0) - SEG)
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

    def chat_series(self):
        """Weighted chat messages per second over the last 5 minutes (oldest -> newest) and seconds of history."""
        now = time.time()
        sec = collections.Counter()
        for w, wt, _ in self.chat.copy():  # snapshot: the chat thread keeps appending
            if w >= now - 300:
                sec[int(now - w)] += wt
        history = now - self.chat[0][0] if self.chat else 0
        return [sec.get(i, 0.0) for i in range(300, -1, -1)][-int(min(history, 300)) - 1:], history

    def scores(self):
        """(chat z, audio z, stream time of the audio peak). Needs 2 minutes of history before it fires."""
        chat_series, history = self.chat_series()
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

    def chat_silent(self):
        """True once the live has recorded 3 minutes without a single chat message."""
        if self.live_since is None or time.time() - self.live_since < 180:
            return False
        last = self.chat[-1][0] if self.chat else 0
        return time.time() - last > 180

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
        # no chat reaching us (Kick, or a YouTube chat that is not coming through): the audio alone has to decide,
        # with a lower bar, and the AI review throws out the loud moments that are not clips
        if self.chat_silent():
            if not getattr(self, "said_no_chat", False):
                log(self.name, "nenhuma mensagem do chat chegando: detectando só pelo áudio (a IA filtra os fracos)")
                self.said_no_chat = True
            strong = strong or az >= float(self.opts.get("limiar_audio_sem_chat", 3.0))
            # a steady pace: after N minutes without a clip, the loudest moment of the last 3 minutes becomes one
            # (the AI grades it; the user picks in the app)
            if at is not None:
                self.recent_peaks = [(t, z) for t, z in getattr(self, "recent_peaks", []) if t > time.time() - 180]
                self.recent_peaks.append((time.time(), az))
                quiet_for = time.time() - max(self.last_clip, self.live_since or 0)
                listening = time.time() - getattr(self, "last_listen", 0) < 600
                if (not listening and quiet_for >= 60 * float(self.opts.get("minutos_sem_clipe", 8)) and az >= 1.0
                        and az >= max(z for _, z in self.recent_peaks)):
                    strong = True
        elif getattr(self, "said_no_chat", False):
            log(self.name, "o chat voltou a chegar")
            self.said_no_chat = False
        cooled = time.time() - self.last_clip >= self.opts["intervalo_minimo"]
        if (score >= self.opts["limiar"] or strong) and cooled:
            if az >= cz and at is not None:
                # audio says exactly when it got loud
                scene, back = at, 0.0
            else:
                # the chat types AFTER the scene: find when the burst started, then go back further
                onset = chat_onset(self.chat_series()[0])
                back = onset + self.opts["volta_segundos"]
                scene = sn - back
            self.last_clip = time.time()
            if self.already_clipped(scene - self.opts["antes"], max(scene, sn) + self.opts["depois"]):
                return
            # the clip must still run past the reaction we just saw
            self.pending.append({"peak": scene, "end": max(scene, sn) + self.opts["depois"], "back": back,
                                 "chat_z": cz, "audio_z": az, "wall": time.time()})
            log(self.name, f"MOMENTO detectado (chat {cz:.1f}σ, áudio {az:.1f}σ); a cena começa {back:.0f} s antes "
                           f"da detecção; cortando quando a reação terminar")
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
    def already_clipped(self, a, b):
        """True when a..b (stream seconds) is mostly inside a clip already made or waiting: the same moment twice
        (a chat burst and the listener both catching it) would be two copies of one clip."""
        spans = list(getattr(self, "spans", [])) + [(p["peak"] - self.opts["antes"], p["end"]) for p in self.pending]
        for x, y in spans:
            inter = min(b, y) - max(a, x)
            if inter > 0.5 * min(b - a, y - x):
                return True
        return False

    def remember(self, verdict):
        """Keep the AI's one-line summary of the moment: the next judgement knows what happened before."""
        if verdict and verdict.get("summary"):
            self.context.append(f"{datetime.now():%H:%M} {verdict['summary']}")

    # ---------- listening: lives without chat ----------
    def maybe_listen(self):
        """Without chat, a loud moment is a poor sign of a clip (a story told calmly has no spike): every couple of
        minutes the last stretch of the live is transcribed and the AI is asked whether it holds a clip."""
        every = float(self.opts.get("escuta_segundos", 120))
        if not every or getattr(self, "listening", False) or not self.segments:
            return
        if not self.chat_silent():
            # with chat, bursts already point at moments; but a big chat bursts at everything and calm stories get
            # no burst: listen too while the free Groq hour has plenty of room (lives without chat come first)
            if groq_hour_used() > float(self.opts.get("groq_por_hora", 5400)) * 0.35:
                return
            every *= 1.5
        if time.time() - getattr(self, "last_listen", 0) < every:
            return
        now = self.segments[-1]["end"]
        start = max(getattr(self, "heard_until", 0) - 15, now - every - 15)
        if now - start < 60 or not groq_budget_ok(now - start, self.opts):
            return
        self.listening, self.last_listen = True, time.time()
        threading.Thread(target=self.listen, args=(start, now), daemon=True).start()

    def listen(self, start, end):
        try:
            segs = [s for s in list(self.segments) if s["end"] > start and s["start"] < end and s["path"].exists()]
            if not segs:
                return
            work = self.work.parent / ".escuta"
            work.mkdir(parents=True, exist_ok=True)
            src = work / f"escuta-{int(time.time())}.mp4"
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", "concat:" + "|".join(s["path"].as_posix() for s in segs),
                            "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-fflags", "+genpts", str(src)], check=False)
            if not src.exists():
                return
            try:
                info = probe(src)
                words = transcribe_ranges(src, [(0.0, info["duration"])], dict(self.cfg), work / "words.json")["words"]
                groq_spent(info["duration"])
                self.heard_until = end
                if len(words) < 8:  # nobody talking: nothing to judge from the speech
                    return
                # with chat, what people typed during this stretch (reactions only: no commands, no bots)
                since = time.time() - info["duration"] - 20
                heard_chat = [t for w, wt, t in self.chat.copy() if w >= since and wt > 0][-80:]
                v = aigate.review(self.name, "", heard_chat, timed=aigate.timed_lines(words), peak=info["duration"] / 2,
                                  duration=info["duration"], frames=grab_frames(src, info["duration"], work, n=4),
                                  context=list(self.context))
                self.remember(v)
            finally:
                src.unlink(missing_ok=True)
            if not v or "start" not in v:
                return
            bar = int(self.opts.get("nota_escuta", 65))
            if not v["clip"] or v["score"] < bar:
                log(self.name, f"ouvi os últimos {info['duration']:.0f} s: nada que vire clipe (nota {v['score']})")
                return
            if time.time() - self.last_clip < 45:
                return
            t0 = segs[0]["start"]
            self.last_clip = time.time()
            if self.already_clipped(t0 + v["start"], t0 + v["end"]):
                log(self.name, "a IA ouviu um momento que já virou clipe: não repito")
                return
            self.pending.append({"peak": t0 + v["start"] + self.opts["antes"], "end": t0 + v["end"], "back": 0,
                                 "chat_z": 0.0, "audio_z": 0.0, "wall": time.time()})
            log(self.name, f"a IA ouviu um momento (nota {v['score']}): {v['why'][:110]}")
        except Exception as e:
            log(self.name, f"escuta falhou: {str(e)[:150]}")
        finally:
            self.listening = False

    def ready_pending(self):
        if not self.segments:
            return
        # what is really on disk: when the download runs behind the live (slow connection), the estimate from the
        # clock runs ahead of the recording, and cutting by it found no segments ("corte perdido")
        recorded = self.segments[-1]["end"]
        for p in list(self.pending):
            if recorded >= p["end"] + self.opts.get("folga", 0) + 2:
                self.pending.remove(p)
                threading.Thread(target=self.cut, args=(p,), daemon=True).start()
            elif time.time() - p["wall"] > 600:
                self.pending.remove(p)
                log(self.name, "corte desistido: a gravação ficou mais de 10 min atrás da live (internet lenta?)")

    def cut(self, p):
        # the raw cut: the clip plus some room on both sides (the app can move the start and the end within it)
        a = p["peak"] - self.opts["antes"] - self.opts.get("folga", 0) - 4
        b = p["end"] + self.opts.get("folga", 0) + 4
        segs = [s for s in list(self.segments) if s["end"] > a and s["start"] < b and s["path"].exists()]
        if not segs:
            log(self.name, "corte perdido: os segmentos já tinham saído do buffer")
            notify_error(self.name, "perdi um corte (o vídeo já tinha saído do buffer)")
            return
        day = datetime.now().strftime("%Y-%m-%d")
        folder = ROOT / "outbox" / "live" / self.key / day
        work = folder / ".work"
        work.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%H%M%S")
        src = work / f"src-{stamp}.mp4"
        # MPEG-TS pieces join byte by byte (the concat protocol): the concat demuxer re-timed every piece and could
        # leave small gaps or repeats in the audio at the joins
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", "concat:" + "|".join(s["path"].as_posix() for s in segs),
                        "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-fflags", "+genpts",
                        "-movflags", "+faststart", str(src)], check=False)
        if not src.exists():
            log(self.name, "corte falhou ao juntar os segmentos")
            return
        with self.render_lock:  # one render at a time keeps the recorders smooth
            try:
                self.render(src, segs[0]["start"], p, folder, work, stamp)
            except Exception as e:
                log(self.name, f"render falhou: {str(e)[:200]}")
                notify_error(self.name, f"falhou ao montar um clipe: {str(e)[:150]}")
            finally:
                if not self.cfg.get("keep_work"):
                    src.unlink(missing_ok=True)

    def render(self, src, offset, p, folder, work, stamp):
        info = probe(src)
        cfg = dict(self.cfg)
        peak = p["peak"] - offset
        end = p["end"] - offset
        a0, b0 = max(0.0, peak - self.opts["antes"]), min(info["duration"], end)
        words = transcribe_ranges(src, [(0.0, info["duration"])], cfg, work / f"words-{stamp}.json")["words"]
        groq_spent(info["duration"])
        # snapping may extend to finish a sentence, but never drop the scene start or the reaction
        a, b = snap(words, a0, b0, info["duration"], cfg)
        a, b = cap_length(a, b, peak, (b0 - a0) + 6)
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
        # a clipper's second look: what was said and what the chat wrote right then. Random gameplay and chat
        # bursts about something else (giveaway, subs) are dropped before spending time on the render.
        said = " ".join(w["text"] for w in words if a <= w["start"] < b)
        chat_lines = [t for w, wt, t in self.chat.copy() if p["wall"] - 25 <= w <= p["wall"] + 12 and wt > 0]
        verdict = aigate.review(self.name, said, chat_lines[-80:], timed=aigate.timed_lines(words), peak=peak,
                                duration=info["duration"], frames=grab_frames(src, info["duration"], work),
                                context=list(self.context))
        self.remember(verdict)
        score, why = None, ""
        if verdict is not None and "start" in verdict:
            # the AI read the whole window and points at where the story really starts and ends
            a, b = snap(words, verdict["start"], verdict["end"], info["duration"], cfg)
            a, b = cap_length(a, b, (a + b) / 2, 95)
            log(self.name, f"a IA ajustou o corte: {a:.0f}s a {b:.0f}s da janela ({b - a:.0f} s)")
        if verdict is not None:
            score, why = (verdict["score"] if verdict["clip"] else min(verdict["score"], 40)), verdict["why"]
            # "descartar": true in live-canais.json lets the AI throw clips away; by default every clip is made and
            # the AI only gives it a grade and a title, and the user decides in the app
            if self.opts.get("descartar", False) and score < int(self.opts.get("nota_minima", 60)):
                self.discarded = getattr(self, "discarded", 0) + 1
                log(self.name, f"descartado pela IA (nota {score}): {why[:120]}")
                return
            if verdict["title"]:
                title = hook = verdict["title"]
            log(self.name, f"nota da IA {score}: {why[:120]}")
        # the title burned at the start of the video: off unless "titulo_no_video": true (it stays as the post caption)
        clip = {"start": a, "end": b, "hook": hook if self.opts.get("titulo_no_video", False) else "", "title": title}
        self.spans = (getattr(self, "spans", []) + [(offset + a, offset + b)])[-50:]
        n = len(self.made) + 1
        name = f"{stamp}-{slugify(title)[:40]}.mp4"
        # "legendas": false in live-canais.json = clips without captions (the title at the start stays)
        meta = render_clip(src, info, words if self.opts.get("legendas", True) else [], clip, cfg, folder / name, work,
                           self.layout)
        text = " ".join(w["text"] for w in words if a <= w["start"] < b)
        self.made.append({"rank": n, "file": name, "thumb": meta["thumb"], "title": title,
                          "score": round(min(99, 50 + 8 * max(p["chat_z"], p["audio_z"]))),
                          "reason": f"Chat {p['chat_z']:.1f}σ, áudio {p['audio_z']:.1f}σ acima do normal da live, às {clock}.",
                          "picker": "live", "duration": meta["duration"], "cuts": 0, "layout": meta["layout"],
                          "source_start": 0, "source_end": meta["duration"], "text": text})
        write_report(folder, f"{self.name} ao vivo ({datetime.now():%d/%m})", self.made,
                     {"explanation": f"{len(self.made)} momentos cortados automaticamente nesta live."})
        log(self.name, f"CLIPE PRONTO: {folder / name}")
        credit = self.spec.get("creditos") or self.opts.get("creditos") or self.name
        caption = post_text(title, credit, self.opts.get("hashtags", "#cortes #clips"))
        if verdict and verdict.get("caption"):
            # the AI's post text fits this moment; the credit to the streamer always goes along
            caption = f"{verdict['caption']}\n\nCréditos: {credit if credit.startswith('@') else '@' + credit}"
        # keep the raw cut (original quality, with room around the clip) for the app's editor
        raw = folder / f"{stamp}-bruto.mp4"
        try:
            shutil.move(str(src), raw)
        except OSError:
            raw = None
        clipserver.add_clip(folder / name, self.name, title, caption, score, why,  # the phone app picks it up
                            raw=raw, raw_start=meta.get("start", a), raw_end=meta.get("end", b),
                            category=(verdict or {}).get("category", ""))
        if TG:
            TG.send_clip(folder / name, caption)

    # ---------- main loop ----------
    def run(self):
        chat = None
        while not self.stopped.is_set():
            try:
                if not self.live_now():
                    with _waiting_lock:
                        WAITING.pop(self.name, None)
                    # said once, not every minute for every channel: with many channels the useful lines got buried
                    if not getattr(self, "said_offline", False):
                        log(self.name, "offline (checo a cada minuto; aviso quando entrar ao vivo)")
                        self.said_offline, self.last_wait_msg = True, None
                    self.stopped.wait(60)
                    continue
                self.said_offline = False
                with _waiting_lock:
                    twin = RECORDING_PEOPLE.get(self.person)
                    if twin and twin != self.name:
                        WAITING.pop(self.name, None)
                if twin and twin != self.name:
                    msg = f"ao vivo, mas já estou gravando essa mesma live por {twin} (não gravo em dobro)"
                    if msg != getattr(self, "last_wait_msg", None):
                        log(self.name, msg)
                        self.last_wait_msg = msg
                    self.stopped.wait(60)
                    continue
                first = higher_waiting(self.rank, self.name)
                if first or not RECORDING.acquire(blocking=False):
                    with _waiting_lock:
                        WAITING[self.name] = self.rank
                    msg = (f"ao vivo, esperando a vez ({first} está mais acima na lista)" if first else
                           "ao vivo, esperando: o limite de lives ao mesmo tempo está cheio")
                    if msg != getattr(self, "last_wait_msg", None):  # say it once, not every 15 s
                        log(self.name, msg)
                        self.last_wait_msg = msg
                    self.stopped.wait(15)
                    continue
                with _waiting_lock:
                    WAITING.pop(self.name, None)
                    twin = RECORDING_PEOPLE.setdefault(self.person, self.name)
                if twin != self.name:  # the other platform started in the same instant
                    RECORDING.release()
                    continue
                self.holding = True
                self.last_wait_msg = None
                log(self.name, "AO VIVO: gravando buffer" + ("" if self.platform == "kick" else " e lendo o chat"))
                if not self.live and TG:
                    TG.send(f"🔴 {self.name} entrou ao vivo. Estou vigiando.")
                self.live, self.live_since = True, self.live_since or time.time()
                self.start_recorder()
                if self.simulated:
                    chat = SimulatedChat(self.spec.get("chat", []), self.chat_sink)
                elif self.platform == "twitch":
                    chat = TwitchChat(self.name, self.chat_sink)
                elif self.platform == "kick":
                    # Kick has no open chat feed: moments come from the audio, and the AI review decides
                    chat = None
                    log(self.name, "Kick: sem leitura do chat; detectando pelo áudio (gritos, risadas) + revisão da IA")
                else:
                    chat = YouTubeChat(self.url, self.chat_sink)
                if chat:
                    chat.start()
                last_status = 0
                yielded = None
                while not self.stopped.is_set() and self.proc.poll() is None:
                    if not self.pending and self.segments:
                        yielded = higher_waiting(self.rank, self.name)
                        if yielded:
                            log(self.name, f"{yielded} entrou ao vivo e está mais acima na lista: passo a vez")
                            break
                    self.poll_segments()
                    self.detect()
                    self.ready_pending()
                    self.maybe_listen()
                    if time.time() - last_status > 60:
                        cz, az, _ = self.scores()
                        msgs = sum(1 for w, _, _ in self.chat.copy() if w >= time.time() - 60)
                        log(self.name, f"ok — {len(self.segments)} segmentos no buffer, {msgs} msgs/min, "
                                       f"chat {cz:+.1f}σ, áudio {az:+.1f}σ, {len(self.made)} clipes"
                                       + (f", {self.discarded} descartados pela IA" if getattr(self, "discarded", 0) else ""))
                        last_status = time.time()
                    self.stopped.wait(1)
                if yielded:
                    continue
                log(self.name, "a gravação parou (live acabou ou caiu); reconectando")
                if not self.simulated and not self.live_now():
                    self.end_live()
            except Exception as e:
                log(self.name, f"erro: {str(e)[:200]}")
                notify_error(self.name, f"erro: {str(e)[:150]}")
                self.stopped.wait(15)
            finally:
                if chat:
                    chat.stop.set()
                self.stop_recorder()
                if getattr(self, "holding", False):
                    self.holding = False
                    with _waiting_lock:
                        if RECORDING_PEOPLE.get(self.person) == self.name:
                            RECORDING_PEOPLE.pop(self.person)
                    RECORDING.release()


    def end_live(self):
        if self.live and TG:
            TG.send(f"⚫ A live de {self.name} acabou. {len(self.made)} clipe(s) nesta live.")
        self.live, self.live_since = False, None
        self.made = []

    def describe(self):
        if not self.live:
            return f"⚫ {self.name} ({self.platform}): offline. Checo a cada minuto."
        mins = int((time.time() - (self.live_since or time.time())) / 60)
        msgs = sum(1 for w, _, _ in self.chat.copy() if w >= time.time() - 60)
        return (f"🔴 {self.name} ({self.platform}): ao vivo há {mins} min, {msgs} msgs/min no chat, "
                f"{len(self.made)} clipe(s) nesta live.")


_groq_log = collections.deque()  # (time, audio seconds) sent to Groq: the free plan allows ~7200 s/hour, 28800 s/day
_groq_lock = threading.Lock()


def groq_budget_ok(seconds, opts):
    """Room in the free Groq plan for `seconds` more of audio (keeps a margin for the clips' own transcription)."""
    if not os.environ.get("GROQ_API_KEY"):
        return False
    now = time.time()
    with _groq_lock:
        while _groq_log and _groq_log[0][0] < now - 86400:
            _groq_log.popleft()
        hour = sum(s for t, s in _groq_log if t > now - 3600)
        day = sum(s for _, s in _groq_log)
    return hour + seconds <= float(opts.get("groq_por_hora", 5400)) and day + seconds <= float(opts.get("groq_por_dia", 24000))


def groq_hour_used():
    now = time.time()
    with _groq_lock:
        return sum(s for t, s in _groq_log if t > now - 3600)


def groq_spent(seconds):
    with _groq_lock:
        _groq_log.append((time.time(), float(seconds)))


def grab_frames(src, duration, work, n=6):
    """n small JPEG frames spread over the window, [(second, bytes)]: the AI's eyes."""
    out = []
    for i in range(n):
        t = duration * (i + 0.5) / n
        f = Path(work) / f"eye-{os.getpid()}-{threading.get_ident()}-{i}.jpg"
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", f"{t:.2f}", "-i", str(src), "-frames:v", "1",
                        "-vf", "scale=384:-2", "-q:v", "6", str(f)], check=False)
        if f.exists():
            out.append((t, f.read_bytes()))
            f.unlink(missing_ok=True)
    return out


def clear_screen(workers):
    """Wipe the terminal (and its scrollback) and print a short summary: the old lines piled up in Termux until it
    ran out of room. Everything stays in outbox/live/bot.log."""
    if not sys.stderr.isatty():
        return
    sys.stderr.write("\033[3J\033[2J\033[H")  # scrollback, screen, cursor home
    print(f"===== {datetime.now():%H:%M} · tela limpa (histórico completo em outbox/live/bot.log) =====", file=sys.stderr)
    for w in workers:
        if w.live:
            print("  " + w.describe(), file=sys.stderr)
    off = [w.name for w in workers if not w.live]
    if off:
        print(f"  offline: {', '.join(off)}", file=sys.stderr)
    try:
        clips = json.loads(clipserver.MANIFEST.read_text(encoding="utf-8"))[-8:]
    except (OSError, ValueError):
        clips = []
    if clips:
        print("  últimos clipes prontos:", file=sys.stderr)
        for c in clips:
            nota = f" (nota {c['score']})" if c.get("score") is not None else ""
            print(f"    {c['created'][11:16]}  {c['channel']}: {c['title']}{nota}", file=sys.stderr)
    print("=" * 40, file=sys.stderr, flush=True)


def apply_channel_pack(data, pack):
    """Merge a shipped channel pack into the user's list once (per pack version).

    The pack's order becomes the priority; the user's other channels follow. Channels already there keep their
    settings (paused stays paused), and a channel removed after a pack offered it never comes back.
    Returns the names added (empty when the pack was already applied).
    """
    if int(data.get("pacote_versao", 0)) >= int(pack.get("versao", 0)):
        return []
    mine = data.get("canais", [])
    key = lambda c: (c.get("plataforma", "twitch").lower(), c["canal"].lower())
    by_key = {key(c): c for c in mine}
    aut = next((c.get("autorizacao") for c in mine if c.get("autorizacao")), "")
    offered = set(data.get("pacote_oferecidos", []))  # "plataforma:canal" of every channel a pack ever added
    ordered, added = [], []
    for c in pack.get("canais", []):
        k = key(c)
        if k in by_key:
            mine_c = by_key.pop(k)
            if c.get("pessoa"):
                mine_c["pessoa"] = c["pessoa"]
            ordered.append(mine_c)
        elif aut and f"{k[0]}:{k[1]}" not in offered:
            ordered.append({**c, "autorizacao": aut, "ativo": True})
            added.append(c["canal"])
        offered.add(f"{k[0]}:{k[1]}")
    data["canais"] = ordered + [c for c in mine if key(c) in by_key]
    data["pacote_versao"] = int(pack["versao"])
    data["pacote_oferecidos"] = sorted(offered)
    data.update(pack.get("opcoes", {}))
    return added


def load_channels():
    path = ROOT / "live-canais.json"
    if not path.exists():
        example = ROOT / "live-canais.example.json"
        raise SystemExit(f"Crie {path.name} (copie {example.name}) com os canais que você tem autorização para clipar.")
    data = json.loads(path.read_text(encoding="utf-8"))
    pack_file = ROOT / "deploy" / "canais-pacote.json"
    if pack_file.exists():
        try:
            added = apply_channel_pack(data, json.loads(pack_file.read_text(encoding="utf-8")))
            if "pacote_versao" in data:
                path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            if added:
                print(f"Canais novos adicionados: {', '.join(added)}", file=sys.stderr)
        except (ValueError, KeyError, OSError) as e:
            print(f"pacote de canais ignorado ({e})", file=sys.stderr)
    opts = {**DEFAULTS, **{k: v for k, v in data.items() if k != "canais"}}
    chans = []
    for c in data.get("canais", []):
        if not c.get("ativo", True):
            continue
        if not str(c.get("autorizacao", "")).strip():
            print(f"IGNORADO {c.get('canal')}: sem 'autorizacao'. Diga de onde vem a permissão (campanha, regra "
                  f"publicada pelo streamer, acordo). Clipar sem permissão gera strike.", file=sys.stderr)
            continue
        if c.get("plataforma", "").lower() not in ("twitch", "youtube", "kick", "simulado"):
            print(f"IGNORADO {c.get('canal')}: plataforma deve ser twitch, youtube ou kick", file=sys.stderr)
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
    if cfg["layout"] == "auto":
        cfg["layout"] = "pip"  # live clips: webcam small in the corner (videos use the stacked layout)
    global RECORDING
    if int(opts.get("max_simultaneos") or 0) > 0:
        RECORDING = threading.BoundedSemaphore(int(opts["max_simultaneos"]))
    if any(c["plataforma"].lower() == "youtube" for c in chans):
        try:
            import chat_downloader  # noqa: F401
        except ImportError:
            print("Instalando o leitor do chat do YouTube (só desta vez)…", file=sys.stderr)
            subprocess.run([sys.executable, "-m", "pip", "install", "-q", "chat-downloader"])
    lock = threading.Lock()
    workers = [Channel(c, opts, cfg, lock) for c in chans]
    for i, w in enumerate(workers):
        w.rank = i  # order in live-canais.json = priority when only some lives can record
    # APP_HOST=127.0.0.1 on a phone (Termux): only apps on the same phone can fetch the clips
    clipserver.serve(os.environ.get("APP_CODE", "").strip(), int(os.environ.get("APP_PORT", "8080")),
                     status=lambda: [w.describe() for w in workers], host=os.environ.get("APP_HOST", "0.0.0.0"))
    global TG
    tg = Telegram(status=lambda: "\n".join(w.describe() for w in workers))
    if tg.enabled:
        TG = tg
        tg.start()
        if tg.chat:
            tg.send(f"▶️ Bot ligado. Vigiando: {', '.join(w.name for w in workers)}.")
        else:
            print("Telegram: mande /start para o seu bot no Telegram para receber os clipes.", file=sys.stderr)
    for w in workers:
        w.start()
    print(f"Vigiando {len(workers)} canal(is). Clipes em outbox/live/. Ctrl+C para parar.", file=sys.stderr)
    t0 = time.time()
    try:
        last_clear = time.time()
        clear_every = 60 * float(opts.get("limpar_tela_minutos", 15) or 0)
        while any(w.is_alive() for w in workers):
            time.sleep(1)
            if clear_every and time.time() - last_clear > clear_every:
                clear_screen(workers)
                last_clear = time.time()
            if a.minutos and time.time() - t0 > a.minutos * 60:
                break
    except KeyboardInterrupt:
        pass
    for w in workers:
        w.stopped.set()
    time.sleep(3)
    for w in workers:
        w.stop_recorder()
