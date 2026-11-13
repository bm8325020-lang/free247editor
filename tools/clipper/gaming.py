"""Find hype moments in gameplay and streams.

1. Audio pass over the whole VOD: where it gets suddenly louder than its own recent baseline (screams, laughs,
   explosions, the streamer losing it).
2. Only the strongest candidate windows are transcribed.
3. Rescore with what was said: hype words in PT/EN/ES, laughter, repeated words ("no no no", "vai vai vai"), fast talk.
4. Claude (if ANTHROPIC_API_KEY is set) makes the final pick and writes titles; otherwise the scorer does.
"""
import os
import re
import sys

import numpy as np

# weight per phrase; matched on the lower-cased, accent-kept transcript
HYPE = {
    # pt-BR
    "meu deus": 2.0, "não acredito": 2.5, "nao acredito": 2.5, "que isso": 1.5, "olha isso": 1.5, "caralho": 2.0,
    "porra": 1.5, "pqp": 2.0, "puta": 1.0, "socorro": 2.0, "morri": 1.5, "matei": 1.5, "ganhamos": 2.0,
    "ganhei": 1.5, "perdemos": 1.5, "perdi": 1.0, "bora": 1.0, "vamo": 1.0, "vamos": 0.8, "eita": 1.5, "nossa": 1.0,
    "rapaz": 1.0, "jesus": 1.2, "que absurdo": 2.0, "absurdo": 1.2, "inacreditável": 2.0, "insano": 1.5,
    "mds": 1.5, "corre": 1.2, "ajuda": 1.0, "cuidado": 1.0, "toma": 1.2, "tomaaa": 2.0, "chora": 1.5, "lixo": 1.0,
    "como assim": 1.5, "não não não": 2.5, "nao nao nao": 2.5, "vai vai": 1.5, "sim sim": 1.5, "mano": 0.5,
    "foi de base": 2.0, "amassou": 1.5, "rushou": 1.0, "clipa": 2.5, "clipa isso": 3.0, "clip it": 3.0,
    # GTA
    "wasted": 2.0, "busted": 2.0, "mission passed": 1.5, "missão concluída": 1.5, "cinco estrelas": 2.0,
    "5 estrelas": 2.0, "five stars": 2.0, "polícia": 1.0, "policia": 1.0, "fuga": 1.0, "perseguição": 1.5,
    "atropelou": 1.5, "explodiu": 1.5, "capotou": 1.5, "roubei": 1.0,
    # en
    "let's go": 2.0, "lets go": 2.0, "no way": 2.5, "oh my god": 2.0, "oh my gosh": 1.5, "what the": 1.5, "holy": 1.5,
    "clutch": 2.0, "ace": 1.0, "headshot": 1.5, "one tap": 1.5, "oh no": 1.5, "no no no": 2.5, "yes yes": 1.5,
    "wtf": 2.0, "omg": 1.5, "insane": 1.5, "crazy": 1.0, "dude": 0.6, "bro": 0.6, "run": 0.8, "help": 0.8,
    "are you kidding": 2.0, "you're kidding": 1.5, "gg": 1.0, "wow": 1.0, "dead": 0.8, "killed": 0.8,
    # es
    "dios mío": 2.0, "no puede ser": 2.5, "vamos": 0.8, "qué es eso": 1.5, "increíble": 1.5,
}
LAUGH = re.compile(r"\b(k{3,}|(ha){2,}|(ah){3,}|(he){3,}|(ja){2,}|lol|lmao|risos|laughs?|laughter)\b", re.I)


def _smooth(x, n):
    if n <= 1:
        return x
    k = np.ones(n) / n
    return np.convolve(np.pad(x, (n // 2, n - 1 - n // 2), mode="edge"), k, mode="valid")


def _rolling_median(x, n):
    pad = np.pad(x, (n // 2, n - 1 - n // 2), mode="edge")
    win = np.lib.stride_tricks.sliding_window_view(pad, n)
    return np.median(win, axis=1)


def audio_excitement(loud, hop):
    """Per-second excitement: how much louder than the last minute or so."""
    per = int(round(1 / hop))
    sec = loud[: len(loud) // per * per].reshape(-1, per)
    if len(sec) == 0:
        return np.zeros(1)
    level = sec.mean(1)  # mean dB in each second
    peak = sec.max(1)
    base = _rolling_median(level, min(91, max(3, len(level) | 1)))
    rise = _smooth(level - base, 3) + 0.5 * _smooth(peak - base, 2)
    rise[level < np.percentile(level, 10)] = 0  # dead air is never a highlight
    s = np.std(rise) + 1e-6
    z = (rise - np.median(rise)) / s
    # intros and outros ("hi chat", "bye chat") are almost never the highlight
    edge = min(15, len(z) // 6)
    if edge > 0:
        ramp = np.linspace(0.3, 1.0, edge)
        z[:edge] = np.minimum(z[:edge], z[:edge] * ramp)
        z[-edge:] = np.minimum(z[-edge:], z[-edge:] * ramp[::-1])
    return z


def candidate_peaks(ex, cfg, k):
    """Strongest local peaks, at least one clip length apart."""
    sep = int(cfg["max_seconds"] * 0.8)
    order = np.argsort(-ex)
    taken = []
    for i in order:
        if ex[i] <= 0.5 and len(taken) >= cfg["clips"]:
            break
        if all(abs(i - j) >= sep for j in taken):
            taken.append(int(i))
        if len(taken) >= k:
            break
    return sorted(taken)


def window_for(peak, dur, cfg):
    """Build-up before the peak matters more than after: ~70% before, ~30% after."""
    t = cfg["target_seconds"]
    a = max(0.0, peak - t * 0.7)
    b = min(dur, peak + t * 0.3 + 1.0)
    if b - a < cfg["min_seconds"]:
        a = max(0.0, b - cfg["min_seconds"])
        b = min(dur, a + cfg["min_seconds"])
    return a, b


def merge(ranges, pad=1.5):
    out = []
    for a, b in sorted(ranges):
        a, b = max(0.0, a - pad), b + pad
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def hype_score(words, a, b, peak):
    text_words = [w for w in words if a <= w["start"] < b]
    if not text_words:
        return 0.0, 0.0, 0
    score = 0.0
    low = " ".join(w["text"].lower() for w in text_words)
    for phrase, wt in HYPE.items():
        n = len(re.findall(r"(?<![\wÀ-ÿ])" + re.escape(phrase) + r"(?![\wÀ-ÿ])", low))
        score += wt * min(n, 3)
    score += 1.5 * min(3, len(LAUGH.findall(low)))
    score += 0.5 * min(4, low.count("!"))
    toks = re.findall(r"[\wÀ-ÿ']+", low)
    for i in range(len(toks) - 2):  # repeated words: "no no no", "vai vai vai"
        if toks[i] == toks[i + 1] == toks[i + 2]:
            score += 1.5
            break
    near = [w for w in text_words if peak - 3 <= w["start"] <= peak + 3]
    burst = len(near) / 6.0  # words per second around the peak
    return score, burst, len(text_words)


def snap(words, a, b, dur, cfg):
    """Never start or end in the middle of a sentence."""
    inside = [w for w in words if w["end"] > a - 4 and w["start"] < b + 6]
    for w in inside:  # a word straddles the start: start before it, at the pause that precedes it
        if w["start"] < a < w["end"]:
            a = w["start"] - 0.15
    prev = [w for w in inside if w["end"] <= a]
    if prev and a - prev[-1]["end"] < 0.3:  # mid-phrase: back up to the last pause (up to 3 s)
        for i in range(len(prev) - 1, 0, -1):
            if prev[i]["start"] - prev[i - 1]["end"] > 0.35 or a - prev[i]["start"] > 3:
                a = prev[i]["start"] - 0.15
                break
    nxt = [w for w in inside if w["start"] >= b - 0.05 or w["start"] < b < w["end"]]
    limit = b + 5
    for w in nxt:  # keep going while the streamer is mid-sentence
        if w["start"] > b + 0.4 or w["end"] > limit:
            break
        b = w["end"] + 0.3
    return max(0.0, a), min(dur, b)


def is_strong(c, cfg):
    """A real moment: a clear audio spike or clear hype words. Weak windows never become clips just to fill the count."""
    return c["audio"] >= cfg.get("min_audio_spike", 1.0) or c["hype"] >= cfg.get("min_hype", 2.0)


def cap_length(a, b, peak, max_seconds):
    """Keep a snapped window within the length limit: ~70% before the peak, ~30% after."""
    if b - a <= max_seconds:
        return a, b
    na = max(a, peak - max_seconds * 0.7)
    nb = min(b, na + max_seconds)
    return max(a, nb - max_seconds), nb


def hook_from(words, peak):
    """The words the streamer shouted at the peak, as an on-screen hook (max 6 words)."""
    near = [w for w in words if peak - 2.5 <= w["start"] <= peak + 2.5]
    if not near:
        return ""
    best, best_s = None, 0.0
    for i in range(len(near)):
        for j in range(i, min(len(near), i + 6)):
            seg = " ".join(w["text"] for w in near[i: j + 1])
            low = seg.lower()
            s = sum(wt for p, wt in HYPE.items() if re.search(r"(?<![\wÀ-ÿ])" + re.escape(p) + r"(?![\wÀ-ÿ])", low))
            s += 1.0 if "!" in seg or "?" in seg else 0
            s -= 0.1 * (j - i)
            if s > best_s:
                best, best_s = seg, s
    return (best or "").strip(" ,.;:") if best_s >= 1.5 else ""


def claude_pick(cands, words, cfg, language):
    if cfg["picker"] == "heuristic" or (cfg["picker"] == "auto" and not os.environ.get("ANTHROPIC_API_KEY")):
        return None
    try:
        import anthropic
        from pydantic import BaseModel
    except ImportError:
        return None

    class Pick(BaseModel):
        id: int
        title: str
        reason: str
        virality: int

    class Picks(BaseModel):
        clips: list[Pick]

    def mmss(t):
        return f"{int(t // 3600)}:{int(t % 3600 // 60):02d}:{int(t % 60):02d}"

    blocks = []
    for i, c in enumerate(cands):
        said = " ".join(w["text"] for w in words if c["start"] <= w["start"] < c["end"]) or "(no speech, game audio only)"
        blocks.append(f"[{i}] {mmss(c['start'])}-{mmss(c['end'])}  audio_spike={c['audio']:.1f}  hype_words={c['hype']:.1f}\n"
                      f"    {said}")
    prompt = f"""You are the clipper for a gaming streamer. These are candidate moments from a stream or gameplay
video, found by audio spikes (screams, laughs, explosions) and hype words. For each you get the time range, how hard
the audio spiked, a hype-word score, and what the streamer said.

Pick up to {cfg['clips']} moments most likely to go viral as vertical clips (TikTok, Reels, Shorts): big reactions,
clutch plays, fails, funny moments, rage, jump scares, wholesome or absurd moments. Skip moments that only make
sense with context or where nothing happens. Fewer picks are better than weak picks: return only moments that
genuinely work on their own. Order best first.

For each pick give: id, a title for the on-screen hook (max 6 words, in the streamer's language
({language or 'same as transcript'}), punchy, like a clip title on Twitch or TikTok, no hashtags, no emoji),
reason (one short sentence), virality 1-100.

Candidates:
""" + "\n".join(blocks)
    try:
        resp = anthropic.Anthropic().messages.parse(
            model=cfg["claude_model"], max_tokens=16000, output_config={"effort": "medium"},
            messages=[{"role": "user", "content": prompt}], output_format=Picks)
        if resp.stop_reason == "refusal" or resp.parsed_output is None:
            print("   Claude declined; using the built-in scorer.", file=sys.stderr)
            return None
        picks = resp.parsed_output.clips
    except Exception as e:
        print(f"   Claude unavailable ({str(e)[:160]}); using the built-in scorer.", file=sys.stderr)
        return None
    out, used = [], set()
    for p in picks:
        if 0 <= p.id < len(cands) and p.id not in used:
            used.add(p.id)
            c = dict(cands[p.id])
            c.update(title=p.title.strip(), hook=p.title.strip(), reason=p.reason.strip(), score=float(p.virality),
                     picker="claude")
            out.append(c)
    return out[: cfg["clips"]] or None


def find_gaming_highlights(src, info, loud, hop, cfg, transcribe_fn):
    dur = info["duration"]
    ex = audio_excitement(loud, hop)
    peaks = candidate_peaks(ex, cfg, max(12, cfg["clips"] * 3))
    wins = [(p, *window_for(p + 0.5, dur, cfg)) for p in peaks]
    tr = transcribe_fn(merge([(a, b + 6) for _, a, b in wins]))
    words, lang = tr["words"], tr.get("language")

    cands = []
    for p, a, b in wins:
        a, b = cap_length(*snap(words, a, b, dur, cfg), p + 0.5, cfg["max_seconds"])
        hype, burst, nwords = hype_score(words, a, b, p + 0.5)
        lo, hi = int(a), max(int(a) + 1, int(b))
        cands.append({"peak": p + 0.5, "start": a, "end": b, "audio": float(ex[p]),
                      "audio_mean": float(np.mean(ex[lo:hi])), "hype": hype, "burst": burst, "nwords": nwords})
    f = np.array([[c["audio"], c["audio_mean"], c["hype"], c["burst"]] for c in cands], dtype=float)
    z = (f - f.mean(0)) / (f.std(0) + 1e-6)
    raw = z @ np.array([1.0, 0.4, 1.2, 0.5])
    for c, s in zip(cands, raw):
        c["raw"] = float(s)
    cands.sort(key=lambda c: -c["raw"])

    strong = [c for c in cands if is_strong(c, cfg)]
    picked = claude_pick(cands, words, cfg, lang)
    if picked is None:
        picked = []
        for c in strong:
            if all(c["end"] <= k["start"] or c["start"] >= k["end"] for k in picked):
                picked.append(c)
            if len(picked) >= cfg["clips"]:
                break
    if picked and picked[0].get("picker") != "claude":
        lo, hi = min(c["raw"] for c in picked), max(c["raw"] for c in picked)
        for c in picked:
            c["hook"] = hook_from(words, c["peak"])
            c["title"] = c["hook"] or f"Momento {int(c['peak'] // 60)}:{int(c['peak'] % 60):02d}"
            c["reason"] = (f"Pico de áudio {c['audio']:.1f}σ" + (f", palavras de hype {c['hype']:.0f}" if c["hype"] else "")
                           + " (seletor automático).")
            c["picker"] = "heuristic"
            c["score"] = round(60 + 35 * ((c["raw"] - lo) / (hi - lo) if hi > lo else 1.0))
    for c in picked:
        c["text"] = " ".join(w["text"] for w in words if c["start"] <= w["start"] < c["end"])
        c["seconds"] = c["end"] - c["start"]
    summary = {"requested": cfg["clips"], "candidates": len(cands), "strong": len(strong), "picked": len(picked),
               "picker": picked[0]["picker"] if picked else "-"}
    return picked, words, lang, summary
