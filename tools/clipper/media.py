"""FFmpeg helpers: probing, downloading, audio loudness curve."""
import json
import re
import shutil
import subprocess
from pathlib import Path

import numpy as np

VIDEO_EXT = {".mp4", ".mov", ".mkv", ".webm", ".m4v", ".avi", ".mts", ".ts", ".flv", ".wmv"}


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, **kw)
    if r.returncode != 0:
        err = (r.stderr or b"").decode("utf-8", "replace").strip().splitlines()[-5:]
        raise RuntimeError(f"{Path(cmd[0]).name} failed: " + " | ".join(err))
    return r.stdout


def need(tool):
    if not shutil.which(tool):
        raise SystemExit(f"Missing {tool}. Install FFmpeg (it includes ffprobe) and run again.")


def probe(path):
    out = run(["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)])
    info = json.loads(out)
    v = next((s for s in info["streams"] if s["codec_type"] == "video"), None)
    a = next((s for s in info["streams"] if s["codec_type"] == "audio"), None)
    if v is None:
        raise SystemExit(f"{path} has no video stream.")
    if a is None:
        raise SystemExit(f"{path} has no audio, so there is nothing to clip by speech.")
    w, h = int(v["width"]), int(v["height"])
    rot = 0
    for sd in v.get("side_data_list", []) or []:
        if "rotation" in sd:
            rot = abs(int(sd["rotation"])) % 180
    if rot == 90:
        w, h = h, w
    num, den = (v.get("avg_frame_rate") or "30/1").split("/")
    fps = float(num) / float(den) if float(den) else 30.0
    return {"width": w, "height": h, "fps": fps or 30.0, "duration": float(info["format"]["duration"])}


def slugify(text):
    s = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE).strip().lower()
    s = re.sub(r"[\s_-]+", "-", s)
    return s[:60].strip("-") or "video"


def is_url(s):
    return bool(re.match(r"^https?://", str(s), re.I))


def download(url, dest_dir):
    """Download a video with yt-dlp. Returns the local path."""
    import yt_dlp

    dest_dir = Path(dest_dir)
    dest_dir.mkdir(parents=True, exist_ok=True)
    opts = {
        "outtmpl": str(dest_dir / "%(title).80s [%(id)s].%(ext)s"),
        "format": "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b",
        "merge_output_format": "mp4",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        path = Path(ydl.prepare_filename(info))
    if not path.exists():
        path = path.with_suffix(".mp4")
    if not path.exists():
        raise SystemExit(f"Download finished but the file was not found for {url}")
    return path


def loudness_curve(path, hop=0.1):
    """RMS loudness in dB every `hop` seconds of the whole file. Streams, so multi-hour VODs fit in memory."""
    sr = 8000
    n = int(sr * hop)
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(path), "-vn", "-ac", "1", "-ar", str(sr), "-f", "s16le", "-"],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    out, block = [], n * 2 * 600  # read 60 s at a time
    rest = b""
    while True:
        buf = p.stdout.read(block)
        if not buf:
            break
        buf = rest + buf
        usable = len(buf) // (n * 2) * (n * 2)
        rest = buf[usable:]
        pcm = np.frombuffer(buf[:usable], dtype=np.int16).astype(np.float32) / 32768.0
        if len(pcm):
            out.append(np.sqrt(np.mean(pcm.reshape(-1, n) ** 2, axis=1) + 1e-10))
    p.wait()
    if not out:
        return np.full(1, -90, dtype=np.float32)
    return (20 * np.log10(np.concatenate(out))).astype(np.float32)
