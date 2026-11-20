"""Real eyes and ears: Gemini watches a short video of the live WITH its audio (the free plan covers the Flash
models). Same manual and answer as aigate.review; returns None when there is no GEMINI_API_KEY or no model answers,
and the bot falls back to frames + transcript."""
import base64
import json
import os
import urllib.error
import urllib.request

from . import aigate

URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
# measured on a 2-minute stretch: 3.6 Flash answered in ~30 s with a small thinking budget; 3.8 Flash thought for
# over 3 minutes; Flash-Lite and the others are tried when one is busy (HTTP 503 "high demand")
MODELS = ["gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"]
_last_error = ""
_calls = []  # times of the calls in the last 24 h (the free plan has a daily cap)
_cooldown_until = 0.0  # after a 429 (quota), rest a while instead of hammering


def available(per_day=None):
    import time
    if not os.environ.get("GEMINI_API_KEY", "").strip() or time.time() < _cooldown_until:
        return False
    if per_day is not None:
        now = time.time()
        _calls[:] = [t for t in _calls if t > now - 86400]
        return len(_calls) < per_day
    return True


def last_error():
    return _last_error


def review_video(channel, video, duration, chat_lines=(), language="pt", context=None, made=None, peak=None, fps=2):
    """video: a small MP4 (360p, mono audio) of the stretch, under ~15 MB."""
    global _last_error
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        return None
    data = base64.b64encode(open(video, "rb").read()).decode()
    prompt = aigate.build_prompt(channel, "", list(chat_lines), language, None, peak if peak is not None else duration / 2,
                                 duration, None, context, made, video=True)
    def body(think=True):
        cfg = {"responseMimeType": "application/json", "maxOutputTokens": 2048, "mediaResolution": "MEDIA_RESOLUTION_LOW"}
        if think:
            cfg["thinkingConfig"] = {"thinkingBudget": 512}  # long thinking made answers take minutes
        return json.dumps({"contents": [{"parts": [
            {"inlineData": {"mimeType": "video/mp4", "data": data}, "videoMetadata": {"fps": fps}},
            {"text": prompt}]}], "generationConfig": cfg}).encode()
    import time
    global _cooldown_until
    _calls.append(time.time())
    quota = False
    attempts = [(m, True) for m in MODELS]
    while attempts:
        model, think = attempts.pop(0)
        req = urllib.request.Request(URL.format(model=model), data=body(think), method="POST", headers={
            "x-goog-api-key": key, "Content-Type": "application/json", "User-Agent": "fabrica-de-clipes/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                out = json.loads(r.read())
            text = "".join(p.get("text", "") for p in out["candidates"][0]["content"]["parts"])
            v = aigate.parse(text, timed=True, duration=duration)
            if v:
                v["model"] = model
                _last_error = ""
                return v
        except urllib.error.HTTPError as e:
            _last_error = f"{model}: HTTP {e.code} {e.read()[:200]!r}"
            quota = quota or e.code == 429
            if e.code == 400 and think:  # a model that does not take a thinking budget: same model without it
                attempts.insert(0, (model, False))
        except Exception as e:
            _last_error = f"{model}: {type(e).__name__} {str(e)[:150]}"
    if quota:  # every model out of quota: back to frames + transcript for 20 minutes
        _cooldown_until = time.time() + 1200
    return None
