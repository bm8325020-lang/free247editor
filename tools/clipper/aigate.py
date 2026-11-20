"""A clipper's second look before a live clip is made: the free Coarena models read what the streamer said and what
the chat wrote at that moment, and say whether it is worth a clip (and give it a title).

Fails open: without COARENA_API_KEY, or if no model answers, the clip is made as before.
"""
import json
import os
import re
import urllib.request

URL = "https://coarena.ai/api/v1/chat/completions"
_VERDICT = re.compile(r"contexto|aleat[oó]ri|nada (de )?(acontece|demais)|sem graça|irrelevante|fraco|"
                      r"no context|random|nothing happens", re.I)
MODELS = ["deepseek-v4-1-flash-1017", "gpt-6-luna", "gemini-3-8-flash"]


def _ask(key, model, prompt):
    body = json.dumps({"model": model, "max_tokens": 1500, "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(URL, data=body, method="POST", headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
        "User-Agent": "fabrica-de-clipes/1.0"})  # some APIs refuse Python's default agent
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"]


def _window(timed, peak, duration):
    lines = "\n".join(timed[:60]) or "(nothing understandable)"
    return (f"Everything the streamer said in the recorded window ({duration:.0f} s; [n] = seconds from its start). "
            f"The bot noticed something at second {peak:.0f}:\n{lines}\n\n"
            "Also choose where the clip should START and END (seconds, 20 to 90 s long) so it holds the whole moment: "
            "the setup that makes it understandable, the funny/epic part, and the reaction. If the best story in the "
            "window is not exactly at the noticed second, pick the best story.")


def timed_lines(words, every=5.0):
    """'[12] text…' lines, a new one every few seconds: lets the AI point at where the story starts and ends."""
    out, cur, t0 = [], [], None
    for w in words:
        if t0 is None:
            t0 = w["start"]
        if w["start"] - t0 >= every and cur:
            out.append(f"[{t0:.0f}] " + " ".join(cur))
            cur, t0 = [], w["start"]
        cur.append(w["text"])
    if cur:
        out.append(f"[{t0:.0f}] " + " ".join(cur))
    return out


def review(channel, said, chat_lines, language="pt", timed=None, peak=None, duration=None):
    """{"clip": bool, "score": int, "title": str, "why": str, "start"?: s, "end"?: s}, or None when no AI is
    available (keep the clip). With `timed` (lines of the whole recorded window, seconds from its start) the AI also
    picks where the clip should start and end, so a story that started earlier or ended later is not cut off."""
    key = os.environ.get("COARENA_API_KEY", "").strip()
    if not key:
        return None
    chat = "\n".join(f"- {c[:120]}" for c in chat_lines[:80]) or "(no chat available: judge from the speech alone)"
    prompt = f"""You are a top Brazilian clipper of gaming lives (GTA RP, GTA 6, and others).
A bot flagged a moment of {channel}'s live because the chat or the streamer's audio spiked. Decide like a clipper whether it deserves a
TikTok/Reels/Shorts clip: something clearly happened that works on its own (funny line or situation, epic play,
fail, rage, scare, absurd moment, savage reply). Reject when nothing clear happened: random gameplay, the chat
reacting to something off-stream (giveaway, subs, raid, a donation message, a meme unrelated to the moment), or
talk that needs context. Be strict: a wrong clip costs more than a missed one.

{_window(timed, peak, duration) if timed else "What the streamer said in the clip:" + chr(10) + (said.strip() or "(nothing understandable)")}

What the chat wrote right then:
{chat}

Answer with ONLY a JSON object: {{"clip": true, "score": 0-100, "title": "...", "why": "one short sentence"{', "start": seconds, "end": seconds' if timed else ''}}}
"title" is burned on the video and posted, so it is ALWAYS a hook a viewer would click, written from what was said
(max 6 words, in {language}, no hashtags, no emoji), even when the score is low. Never describe the clip's quality
in the title (no "sem contexto", "conversa aleatória", "nada acontece"): that goes in "why"."""
    for model in MODELS:
        try:
            text = _ask(key, model, prompt)
            m = re.search(r"\{.*\}", text, re.S)
            if not m:
                continue
            d = json.loads(m.group(0))
            title = str(d.get("title", "")).strip().strip('"')
            span = {}
            if timed and duration:
                try:
                    st, en = float(d.get("start")), float(d.get("end"))
                    st, en = max(0.0, st), min(float(duration), en)
                    if 15 <= en - st <= 120:
                        span = {"start": st, "end": en}
                except (TypeError, ValueError):
                    pass
            if _VERDICT.search(title):  # a verdict, not a title: never burn it on the video
                title = ""
            return {"clip": bool(d.get("clip")), "score": int(d.get("score", 0)),
                    "title": title, "why": str(d.get("why", "")).strip(), **span}
        except Exception:
            continue
    return None
