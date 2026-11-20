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


def review(channel, said, chat_lines, language="pt"):
    """{"clip": bool, "score": int, "title": str, "why": str}, or None when no AI is available (keep the clip)."""
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

What the streamer said in the clip:
{said.strip() or "(nothing understandable)"}

What the chat wrote right then:
{chat}

Answer with ONLY a JSON object: {{"clip": true, "score": 0-100, "title": "...", "why": "one short sentence"}}
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
            if _VERDICT.search(title):  # a verdict, not a title: never burn it on the video
                title = ""
            return {"clip": bool(d.get("clip")), "score": int(d.get("score", 0)),
                    "title": title, "why": str(d.get("why", "")).strip()}
        except Exception:
            continue
    return None
