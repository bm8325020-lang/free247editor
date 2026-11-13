"""Default settings for the clip factory. Override any key in clipper.config.json at the repo root."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent

DEFAULTS = {
    # "gaming": streams and gameplay (picks hype peaks, facecam-over-gameplay layout, no pause cuts).
    # "talk": podcasts and talking heads (picks complete thoughts, follows the speaker, cuts pauses).
    "mode": "gaming",
    # Layout: "auto" = "stack" for videos with a webcam (webcam across the top, wide gameplay band, captions below),
    # gameplay only without one; talk mode follows the face. Also "split" (webcam on top, tall gameplay crop),
    # "pip" (webcam small in a corner; the live bot's default), "game", "follow".
    "layout": "auto",
    # "pip" (default with a webcam): the streamer's whole webcam, in its own shape (square or round), small in the
    # top-left corner over full-screen gameplay. pip_width = share of the frame width.
    "pip_width": 0.40,
    "pip_margin": 36,
    "pip_top": 0.06,
    # split: the whole webcam across the full width on top; its panel takes cam_min..cam_max of the height
    "stack_cam_max": 0.36,  # stack (videos): webcam share of the height at most, leaving room for captions
    "cam_min": 0.30,
    "cam_max": 0.45,
    "game_zoom": 1.30,  # "game" layout: how far to zoom into the gameplay (1.0 = whole frame, letterboxed)
    "split_game_zoom": 1.0,  # split layout: >1 crops the gameplay tighter
    # How many clips to make from one long video, and how long each may be (seconds, after cuts).
    "clips": 8,
    "min_seconds": 20,
    "max_seconds": 60,
    "target_seconds": 35,
    # Output
    "width": 1080,
    "height": 1920,
    "fps": 30,
    "crf": 20,
    "preset": "medium",
    "loudness_lufs": -14,
    # Pacing: cut any pause between words longer than this (seconds). 0 disables cutting.
    "max_pause": 0.45,
    "pause_pad": 0.10,
    # Camera: alternate a punch-in zoom on every jump cut (1.0 = off).
    "punch_zoom": 1.10,
    # Captions
    "captions": True,
    "caption_font": "Montserrat Black",
    "caption_size": 96,
    "caption_y": 0.68,  # vertical position as a fraction of height (0 top, 1 bottom)
    "caption_max_words": 3,
    "caption_max_chars": 16,
    "caption_uppercase": True,
    "caption_color": "#FFFFFF",
    "caption_highlight": "#FFD400",
    "caption_outline": 7,
    # Hook title over the first seconds
    "hook": True,
    "hook_seconds": 3.0,
    "hook_size": 72,
    "hook_y": 0.12,  # vertical position of the hook box (top of the box)
    # Gaming: a window only becomes a clip if its audio spike (in standard deviations) or hype-word score clears
    # these. "clips" is a maximum, never a quota. --all sets both to 0.
    "min_audio_spike": 1.0,
    "min_hype": 2.0,
    # Highlight picking: "auto" uses Claude when ANTHROPIC_API_KEY is set, else the built-in scorer.
    "picker": "auto",
    "claude_model": "claude-opus-5-5",
    # Transcription: "auto" uses Groq when GROQ_API_KEY is set, else faster-whisper on this computer.
    "transcriber": "auto",
    "whisper_model": "large-v3-turbo",
    "language": None,  # e.g. "pt"; None = detect
}


def load_env():
    env = ROOT / ".env"
    if not env.exists():
        return
    import os
    for line in env.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        v = v.strip().strip('"').strip("'")
        if v and not os.environ.get(k.strip()):
            os.environ[k.strip()] = v


# Applied on top of DEFAULTS in gaming mode unless the user set the key explicitly.
GAMING = {
    "max_pause": 0,  # never cut silence out of gameplay
    "punch_zoom": 1.0,
    "min_seconds": 15,
    "max_seconds": 50,
    "target_seconds": 28,
    "caption_y": 0.36,  # on the seam between facecam and gameplay
    "hook_y": 0.80,
}


def load_config(overrides=None):
    user, cfg = {}, dict(DEFAULTS)
    path = ROOT / "clipper.config.json"
    if path.exists():
        user = json.loads(path.read_text(encoding="utf-8"))
    over = {k: v for k, v in (overrides or {}).items() if v is not None}
    mode = over.get("mode") or user.get("mode") or cfg["mode"]
    if mode == "gaming":
        cfg.update(GAMING)
    cfg.update(user)
    cfg.update(over)
    cfg["mode"] = mode
    return cfg
