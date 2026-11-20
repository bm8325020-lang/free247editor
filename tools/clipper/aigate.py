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
VISION = ["gpt-6-luna", "gemini-3-8-flash"]  # these two also see images (checked: deepseek does not)


def _ask(key, model, prompt, frames=None):
    content = prompt
    if frames:
        import base64
        content = [{"type": "text", "text": prompt}] + [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpg).decode()}}
            for _, jpg in frames]
    body = json.dumps({"model": model, "max_tokens": 1500, "messages": [{"role": "user", "content": content}]}).encode()
    req = urllib.request.Request(URL, data=body, method="POST", headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
        "User-Agent": "fabrica-de-clipes/1.0"})  # some APIs refuse Python's default agent
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"]


def _frames_note(frames):
    if not frames:
        return ""
    when = ", ".join(f"{t:.0f}" for t, _ in frames)
    return (f"\nThe images attached are frames of the stream at seconds {when} (same clock as the transcript), "
            "about every 10 s: follow what happens on screen across them. A crash, a fall, a chase, something absurd "
            "can be the clip even with little or no talk. Say only what the frames and the speech actually show.\n")


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


def playbook():
    """The clipper's manual (tools/clipper/clipador.md) plus the user's own notes (meu-clipador.md in the clipper's
    folder, never touched by updates): read on every judgement, so editing them changes the bot at once."""
    from pathlib import Path
    here = Path(__file__).resolve().parent
    parts = []
    for f in (here / "clipador.md", here.parent.parent / "meu-clipador.md"):
        try:
            parts.append(f.read_text(encoding="utf-8").strip())
        except OSError:
            pass
    if len(parts) == 2:
        parts[1] = "## Notas do dono (valem mais que o resto do manual)\n" + parts[1]
    return "\n\n".join(parts) or "You are a top Brazilian clipper of gaming lives."


CATEGORIES = ("engraçado", "épico", "fail", "raiva", "susto", "história", "polêmica", "fofo", "absurdo")


def review(channel, said, chat_lines, language="pt", timed=None, peak=None, duration=None, frames=None, context=None,
           made=None):
    """{"clip", "score", "title", "why", "category", "caption", "summary", "start"?, "end"?}, or None when no AI is
    available (keep the clip). With `timed` (lines of the whole recorded window, seconds from its start) the AI also
    picks where the clip should start and end, so a story that started earlier or ended later is not cut off.
    frames: [(second, jpeg bytes)] from the same window: the AI sees what happened on screen, not only what was said
    (a visual moment with little talk got a grade of 12). context: one-line summaries of what happened earlier in
    this live, oldest first: callbacks and running jokes make sense only with them."""
    key = os.environ.get("COARENA_API_KEY", "").strip()
    if not key:
        return None
    chat = "\n".join(f"- {c[:120]}" for c in chat_lines[:80]) or "(no chat available: judge from the speech and the images)"
    earlier = "\n".join(f"- {c}" for c in (context or [])[-6:]) or "(start of the live)"
    done = "\n".join(f"- {c}" for c in (made or [])[-12:]) or "(none yet)"
    prompt = f"""{playbook()}

---
You are watching {channel}'s live now. Judge this stretch exactly as the manual above says, before posting.

What happened earlier in this live (for callbacks and running jokes):
{earlier}

Clips already made from this live (do not repeat the same kind of moment unless clearly better):
{done}

{_window(timed, peak, duration) if timed else "What the streamer said in the clip:" + chr(10) + (said.strip() or "(nothing understandable)")}

What the chat wrote right then:
{chat}
{_frames_note(frames)}
Answer with ONLY a JSON object:
{{"clip": true, "score": 0-100, "title": "...", "why": "one short sentence", "category": "one of {"/".join(CATEGORIES)}",
 "caption": "...", "summary": "...", "event": "...", "reaction": "...", "developing": false{', "start": seconds, "end": seconds' if timed else ''}}}
- "title" is a hook a viewer would click, written from what was said (max 6 words, in {language}, no hashtags, no
  emoji), even when the score is low. Never describe the clip's quality in it ("sem contexto", "conversa
  aleatória", "nada acontece"): that goes in "why".
- "caption": the post text in {language}: one short punchy line plus 3 hashtags that fit this moment.
- "summary": one sentence of what is going on in the live right now (it becomes context for the next judgement).
- "event": the main thing that happens, in a few words, only what the images and the speech show (never invent).
- "reaction": how the streamer reacts (laughs, screams, despair, silence…), or "" if there is none.
- "developing": true when something is building up and may become a clip in the next minutes (cops provoked,
  a fight starting, a risky stunt coming), even if this stretch is not a clip yet.
- "start"/"end": start right before the hook line (no dead air before it), end after the reaction settles."""
    order = ([(m, frames) for m in VISION] + [(m, None) for m in MODELS]) if frames else [(m, None) for m in MODELS]
    for model, imgs in order:
        try:
            text = _ask(key, model, prompt, imgs)
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
            cat = str(d.get("category", "")).strip().lower()
            return {"clip": bool(d.get("clip")), "score": int(d.get("score", 0)),
                    "title": title, "why": str(d.get("why", "")).strip(),
                    "category": cat if cat in CATEGORIES else "",
                    "caption": str(d.get("caption", "")).strip()[:300],
                    "summary": str(d.get("summary", "")).strip()[:200],
                    "event": str(d.get("event", "")).strip()[:160], "reaction": str(d.get("reaction", "")).strip()[:120],
                    "developing": bool(d.get("developing")), **span}
        except Exception:
            continue
    return None
