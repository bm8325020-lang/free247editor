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


def _call(key, parts, max_tokens=2048):
    """One generateContent call over the models in order; the answer's text, or None."""
    global _last_error, _cooldown_until
    import time
    _calls.append(time.time())
    quota = False
    attempts = [(m, True) for m in MODELS]
    while attempts:
        model, think = attempts.pop(0)
        cfg = {"responseMimeType": "application/json", "maxOutputTokens": max_tokens,
               "mediaResolution": "MEDIA_RESOLUTION_LOW"}
        if think:
            cfg["thinkingConfig"] = {"thinkingBudget": 512}
        req = urllib.request.Request(URL.format(model=model), method="POST",
                                     data=json.dumps({"contents": [{"parts": parts}], "generationConfig": cfg}).encode(),
                                     headers={"x-goog-api-key": key, "Content-Type": "application/json",
                                              "User-Agent": "fabrica-de-clipes/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                out = json.loads(r.read())
            _last_error = ""
            return "".join(p.get("text", "") for p in out["candidates"][0]["content"]["parts"])
        except urllib.error.HTTPError as e:
            _last_error = f"{model}: HTTP {e.code} {e.read()[:200]!r}"
            quota = quota or e.code == 429
            if e.code == 400 and think:
                attempts.insert(0, (model, False))
        except Exception as e:
            _last_error = f"{model}: {type(e).__name__} {str(e)[:150]}"
    if quota:
        _cooldown_until = time.time() + 1200
    return None


def refine_cut(video, duration, event, fps=4):
    """Second, closer look at a chosen moment: the exact second it naturally begins and the second its reaction
    settles. video: the moment with some room before and after. {"start", "end"} in its seconds, or None."""
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        return None
    data = base64.b64encode(open(video, "rb").read()).decode()
    prompt = (f"This {duration:.0f}-second video of a GTA live holds this moment: {event}\n"
              "Watch it closely (with the sound) and give the EXACT cut for a TikTok clip:\n"
              "- start: the natural beginning, with just the context needed to understand it (the first line or action "
              "that sets it up). Not after it started, and no dead air before it.\n"
              "- end: right after the reaction settles (laugh, scream, comment). Never in the middle of the action or "
              "before the punchline. No dead tail.\n"
              'Answer with ONLY JSON: {"start": seconds, "end": seconds}')
    text = _call(key, [{"inlineData": {"mimeType": "video/mp4", "data": data}, "videoMetadata": {"fps": fps}},
                       {"text": prompt}], max_tokens=512)
    try:
        d = json.loads(text[text.index("{"): text.rindex("}") + 1])
        st, en = max(0.0, float(d["start"])), min(float(duration), float(d["end"]))
        return {"start": st, "end": en} if 10 <= en - st <= 120 else None
    except (TypeError, ValueError, KeyError, AttributeError):
        return None


def review_video(channel, video, duration, chat_lines=(), language="pt", context=None, made=None, peak=None, fps=2,
                 style=None):
    """video: a small MP4 (360p, mono audio) of the stretch, under ~15 MB."""
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        return None
    data = base64.b64encode(open(video, "rb").read()).decode()
    prompt = aigate.build_prompt(channel, "", list(chat_lines), language, None, peak if peak is not None else duration / 2,
                                 duration, None, context, made, video=True, style=style)
    text = _call(key, [{"inlineData": {"mimeType": "video/mp4", "data": data}, "videoMetadata": {"fps": fps}},
                       {"text": prompt}])
    v = aigate.parse(text, timed=True, duration=duration) if text else None
    return v
