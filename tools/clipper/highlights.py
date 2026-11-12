"""Find the best moments in a long transcript.

Two pickers share the same candidate windows (whole sentences, a sensible length):
- Claude (when ANTHROPIC_API_KEY is set): reads the transcript and picks clips with titles.
- Built-in scorer (always available): hook words, questions, numbers, emotion, loudness, pace.
"""
import math
import os
import re
import sys

import numpy as np

HOOK_WORDS = {
    # pt
    "segredo", "ninguém", "nunca", "sempre", "erro", "erros", "verdade", "mentira", "dinheiro", "milhão", "milhões",
    "mil", "como", "porque", "por que", "pior", "melhor", "maior", "incrível", "absurdo", "loucura", "problema",
    "importante", "atenção", "cuidado", "descobri", "aprendi", "dica", "regra", "simples", "rápido", "grátis",
    "ninguem", "voce", "você", "vocês", "imagina", "olha", "sério", "real", "medo", "ódio", "amor", "morte",
    "chocante", "ganhar", "perder", "perdi", "ganhei", "quebrei", "rico", "pobre", "fracasso", "sucesso",
    # en
    "secret", "nobody", "never", "always", "mistake", "truth", "lie", "money", "million", "billion", "how", "why",
    "worst", "best", "biggest", "crazy", "insane", "problem", "important", "careful", "learned", "discovered", "tip",
    "rule", "simple", "free", "you", "imagine", "look", "seriously", "fear", "hate", "love", "death", "shocking",
    "win", "lose", "lost", "rich", "broke", "failure", "success", "exciting", "first", "actually", "realize",
    # es
    "secreto", "nadie", "nunca", "verdad", "dinero", "mejor", "peor", "increíble", "cómo", "por qué",
}
FILLERS = {"uh", "um", "uhm", "er", "ah", "eh", "hmm", "né", "tipo", "assim", "então", "like", "you know", "é", "ã"}
WEAK_STARTS = {"and", "but", "so", "or", "because", "also", "e", "mas", "aí", "daí", "então", "ou", "porque", "também",
               "y", "pero", "entonces", "o"}
END = re.compile(r"[.!?…]$")


def sentences(words, gap=0.9):
    """Group words into sentences on punctuation or long pauses."""
    out, cur = [], []
    for i, w in enumerate(words):
        cur.append(i)
        nxt = words[i + 1] if i + 1 < len(words) else None
        if END.search(w["text"]) or nxt is None or nxt["start"] - w["end"] > gap or len(cur) >= 60:
            out.append({"w0": cur[0], "w1": cur[-1], "start": words[cur[0]]["start"], "end": words[cur[-1]]["end"],
                        "text": " ".join(words[j]["text"] for j in cur)})
            cur = []
    return out


def speech_seconds(words, w0, w1, max_pause):
    """Duration after long pauses are cut (what the viewer will actually see)."""
    total = words[w1]["end"] - words[w0]["start"]
    if max_pause <= 0:
        return total
    for i in range(w0, w1):
        g = words[i + 1]["start"] - words[i]["end"]
        if g > max_pause:
            total -= g - max_pause
    return total


def candidates(words, sents, cfg):
    lo, hi = cfg["min_seconds"], cfg["max_seconds"]
    out = []
    for a in range(len(sents)):
        for b in range(a, len(sents)):
            d = speech_seconds(words, sents[a]["w0"], sents[b]["w1"], cfg["max_pause"])
            if d > hi:
                break
            if d >= lo:
                out.append((a, b, d))
    return out


def _tok(text):
    return [t for t in re.findall(r"[\wÀ-ÿ']+", text.lower())]


def _sentence_hook(s):
    toks = _tok(s["text"])
    if not toks:
        return 0.0
    score = 0.0
    score += 1.5 if s["text"].rstrip().endswith("?") else 0
    score += 1.0 if re.search(r"\d", s["text"]) else 0
    score += min(2.0, sum(t in HOOK_WORDS for t in toks) * 0.5)
    score += 0.5 if 4 <= len(toks) <= 14 else 0
    score -= 3.0 if toks[0] in FILLERS or toks[0] in WEAK_STARTS else 0
    return score


def score_heuristic(words, sents, cands, loud, hop, cfg):
    gmean, gstd = float(np.median(loud)), float(np.std(loud) + 1e-6)
    feats = []
    for a, b, d in cands:
        w0, w1 = sents[a]["w0"], sents[b]["w1"]
        toks = _tok(" ".join(words[i]["text"] for i in range(w0, w1 + 1)))
        n = max(1, len(toks))
        i0, i1 = int(words[w0]["start"] / hop), max(int(words[w0]["start"] / hop) + 1, int(words[w1]["end"] / hop))
        seg = loud[i0:i1]
        feats.append([
            _sentence_hook(sents[a]) * 1.6 + (_sentence_hook(sents[a + 1]) * 0.5 if a + 1 <= b else 0),  # hook
            sum(t in HOOK_WORDS for t in toks) / n * 10,  # punchy vocabulary
            (float(np.mean(seg)) - gmean) / gstd if len(seg) else 0,  # louder than usual
            float(np.percentile(seg, 95) - np.percentile(seg, 30)) / gstd if len(seg) > 5 else 0,  # dynamics
            n / d,  # pace
            -sum(t in FILLERS for t in toks) / n * 10,  # fillers
            1.0 if END.search(words[w1]["text"]) else -1.0,  # ends on a complete thought
            -abs(d - cfg["target_seconds"]) / cfg["target_seconds"],  # preferred length
        ])
    f = np.array(feats, dtype=np.float64)
    z = (f - f.mean(0)) / (f.std(0) + 1e-6)
    weights = np.array([1.6, 1.0, 0.8, 0.6, 0.5, 0.6, 0.9, 0.7])
    raw = z @ weights
    return [{"a": a, "b": b, "seconds": d, "score": float(s)} for (a, b, d), s in zip(cands, raw)]


def pick(scored, n):
    """Greedy best-first, no overlapping clips."""
    chosen = []
    for c in sorted(scored, key=lambda c: -c["score"]):
        if all(c["b"] < k["a"] or c["a"] > k["b"] for k in chosen):
            chosen.append(c)
        if len(chosen) >= n:
            break
    return chosen


def heuristic_hook(sents, c):
    """A short, complete on-screen hook from the clip's opening, or "" when none reads well."""
    opts = []
    for i in range(c["a"], min(c["b"], c["a"] + 2) + 1):
        text = sents[i]["text"].strip()
        pieces = [text] + [p.strip() for p in re.split(r"(?<=[,;:])\s+", text)]
        for p in pieces:
            n = len(p.split())
            if 3 <= n <= 9 and _tok(p) and _tok(p)[0] not in WEAK_STARTS:
                opts.append((_sentence_hook({"text": p}) - (i - c["a"]) * 0.5, p.rstrip(",;:")))
    return max(opts)[1] if opts else ""


def heuristic_title(sents, c):
    toks = sents[c["a"]]["text"].split()
    return " ".join(toks[:9]).rstrip(",;:") + ("…" if len(toks) > 9 else "")


def claude_pick(words, sents, cfg, language):
    """Ask Claude for the best clips. Returns list of dicts or None if unavailable."""
    if cfg["picker"] == "heuristic" or (cfg["picker"] == "auto" and not os.environ.get("ANTHROPIC_API_KEY")):
        return None
    try:
        import anthropic
        from pydantic import BaseModel
    except ImportError:
        return None

    class Clip(BaseModel):
        start_sentence: int
        end_sentence: int
        title: str
        reason: str
        virality: int

    class Picks(BaseModel):
        clips: list[Clip]

    def mmss(t):
        return f"{int(t // 60):02d}:{int(t % 60):02d}"

    lines = "\n".join(f"[{i}] ({mmss(s['start'])}) {s['text']}" for i, s in enumerate(sents))
    prompt = f"""You are a senior short-form video editor. Below is the transcript of a long video, split into
numbered sentences with timestamps. Pick the {cfg['clips']} best moments to publish as standalone vertical clips
(TikTok, Reels, Shorts).

Each clip is a contiguous range of sentences, from start_sentence to end_sentence inclusive. Spoken length must be
between {cfg['min_seconds']} and {cfg['max_seconds']} seconds (aim for about {cfg['target_seconds']}). Clips must
not overlap. A good clip:
- opens with a hook in its first sentence (a bold claim, a question, a surprising fact, a story beat), never with
  "and", "so", or a reference to something said earlier;
- makes sense to someone who never saw the rest of the video;
- ends on a complete thought or a punchline.

For each clip give a title: the on-screen hook text, at most 8 words, in the video's language ({language or 'same as transcript'}),
written to stop the scroll, no hashtags, no emoji. Give reason (one short sentence) and virality from 1 to 100.
Order clips from best to worst.

Transcript:
{lines}"""
    try:
        client = anthropic.Anthropic()
        resp = client.messages.parse(
            model=cfg["claude_model"],
            max_tokens=16000,
            output_config={"effort": "medium"},
            messages=[{"role": "user", "content": prompt}],
            output_format=Picks,
        )
        if resp.stop_reason == "refusal" or resp.parsed_output is None:
            print("   Claude declined; using the built-in scorer.", file=sys.stderr)
            return None
        picks = resp.parsed_output.clips
    except Exception as e:
        print(f"   Claude unavailable ({str(e)[:160]}); using the built-in scorer.", file=sys.stderr)
        return None

    out, n = [], len(sents)
    for p in picks:
        a, b = max(0, p.start_sentence), min(n - 1, p.end_sentence)
        if b < a:
            continue
        d = speech_seconds(words, sents[a]["w0"], sents[b]["w1"], cfg["max_pause"])
        # Trim from the end if Claude went long; extend if short.
        while d > cfg["max_seconds"] * 1.15 and b > a:
            b -= 1
            d = speech_seconds(words, sents[a]["w0"], sents[b]["w1"], cfg["max_pause"])
        while d < cfg["min_seconds"] * 0.8 and b + 1 < n:
            b += 1
            d = speech_seconds(words, sents[a]["w0"], sents[b]["w1"], cfg["max_pause"])
        if any(not (b < k["a"] or a > k["b"]) for k in out):
            continue
        out.append({"a": a, "b": b, "seconds": d, "score": float(p.virality), "title": p.title.strip(), "hook": p.title.strip(),
                    "reason": p.reason.strip(), "picker": "claude"})
    return out or None


def find_highlights(words, loud, hop, cfg, language):
    sents = sentences(words)
    clips = claude_pick(words, sents, cfg, language)
    if clips is None:
        cands = candidates(words, sents, cfg)
        if not cands:  # very short video: take everything as one clip
            d = speech_seconds(words, 0, len(words) - 1, cfg["max_pause"])
            cands = [(0, len(sents) - 1, d)]
        scored = score_heuristic(words, sents, cands, loud, hop, cfg)
        clips = pick(scored, cfg["clips"])
        lo, hi = min(c["score"] for c in clips), max(c["score"] for c in clips)
        for c in clips:
            c["hook"] = heuristic_hook(sents, c)
            c["title"] = c["hook"] or heuristic_title(sents, c)
            c["reason"] = "Strong opening, energy and complete thought (built-in scorer)."
            c["picker"] = "heuristic"
            c["score"] = round(60 + 35 * ((c["score"] - lo) / (hi - lo) if hi > lo else 1.0))
    for c in clips:
        c["start"] = words[sents[c["a"]]["w0"]]["start"]
        c["end"] = words[sents[c["b"]]["w1"]]["end"]
        c["w0"], c["w1"] = sents[c["a"]]["w0"], sents[c["b"]]["w1"]
        c["text"] = " ".join(s["text"] for s in sents[c["a"]: c["b"] + 1])
    return clips
