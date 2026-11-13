#!/usr/bin/env python3
"""Clip factory: turn a long video into ready-to-post vertical clips.

  python clip.py                      clip every video waiting in streams/
  python clip.py VIDEO_OR_URL         clip one file or link (YouTube, Twitch VOD, etc.)
  python clip.py --watch              keep running and clip whatever lands in streams/
  python clip.py --setup              install the Python packages it needs

Options: --mode gaming|talk  --layout auto|split|game|follow  --clips 10  --min 15  --max 90  --no-captions  --no-hook  --picker heuristic|claude
Output: outbox/<video>-<date>/  with clip-01-....mp4, a cover .jpg and .txt per clip, and index.html to review.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools"))


def setup():
    req = ROOT / "tools" / "clipper" / "requirements.txt"
    print("Installing Python packages...")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "-r", str(req)])
    if r.returncode != 0:
        sys.exit("pip install failed; read the error above.")
    if not shutil.which("ffmpeg"):
        hint = {"win32": "winget install Gyan.FFmpeg", "darwin": "brew install ffmpeg"}.get(sys.platform,
                                                                                            "sudo apt install ffmpeg")
        sys.exit(f"Packages installed. FFmpeg is still missing: {hint}")
    print("Ready. Put a stream or gameplay video in streams/ and run: python clip.py")


def main():
    ap = argparse.ArgumentParser(description="Turn long videos into vertical clips.")
    ap.add_argument("source", nargs="?", help="video file or URL (default: everything in streams/)")
    ap.add_argument("--watch", action="store_true", help="keep watching streams/ and clip new videos")
    ap.add_argument("--setup", action="store_true", help="install requirements")
    ap.add_argument("--mode", choices=["gaming", "talk"], help="gaming (default) or talk (podcasts, talking heads)")
    ap.add_argument("--layout", choices=["auto", "split", "game", "follow"],
                    help="split = facecam on top; game = gameplay only; follow = track the face")
    ap.add_argument("--clips", type=int, dest="clips")
    ap.add_argument("--min", type=float, dest="min_seconds")
    ap.add_argument("--max", type=float, dest="max_seconds")
    ap.add_argument("--picker", choices=["auto", "claude", "heuristic"])
    ap.add_argument("--language", help="force transcription language, e.g. pt")
    ap.add_argument("--all", action="store_true", help="keep weak moments too, to always reach --clips")
    ap.add_argument("--no-captions", action="store_true")
    ap.add_argument("--no-hook", action="store_true")
    ap.add_argument("--out", help="output folder (default: outbox/)")
    a = ap.parse_args()

    if a.setup:
        return setup()

    from clipper.config import load_config
    from clipper.pipeline import pending_inbox, process, watch

    over = {k: getattr(a, k) for k in ("mode", "layout", "clips", "min_seconds", "max_seconds", "picker", "language")}
    if a.all:
        over["min_audio_spike"] = 0
        over["min_hype"] = 0
    if a.no_captions:
        over["captions"] = False
    if a.no_hook:
        over["hook"] = False
    cfg = load_config(over)
    if cfg["max_seconds"] < cfg["min_seconds"]:
        sys.exit("--max must be at least --min")
    cfg["target_seconds"] = min(max(cfg["target_seconds"], cfg["min_seconds"]), cfg["max_seconds"])

    if a.watch:
        return watch(cfg)
    if a.source:
        process(a.source, cfg, a.out)
        return
    vids, done_dir = pending_inbox()
    if not vids:
        sys.exit(f"No videos in {ROOT / 'streams'}. Put one there, or run: python clip.py <file or link>")
    for v in vids:
        process(v, cfg, a.out)
        done_dir.mkdir(exist_ok=True)
        shutil.move(str(v), str(done_dir / v.name))


if __name__ == "__main__":
    main()
