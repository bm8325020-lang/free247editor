"""Word-by-word animated captions and a hook title, written as an ASS subtitle file for FFmpeg/libass."""
import re


def ass_color(hex_color, alpha=0):
    h = hex_color.lstrip("#")
    r, g, b = h[0:2], h[2:4], h[4:6]
    return f"&H{alpha:02X}{b}{g}{r}".upper()


def ts(t):
    t = max(0.0, t)
    h, m = int(t // 3600), int(t % 3600 // 60)
    s = t % 60
    return f"{h}:{m:02d}:{s:05.2f}"


def clean(text, upper):
    text = re.sub(r"[{}\\]", "", text)
    text = re.sub(r"^[\"'“”‘’(\[]+|[\"'“”‘’)\],.;:…]+$", "", text)
    return text.upper() if upper else text


class Timeline:
    """Maps source times to output times after pauses are cut."""

    def __init__(self, ranges):
        self.ranges = ranges
        self.offsets, acc = [], 0.0
        for a, b in ranges:
            self.offsets.append(acc)
            acc += b - a
        self.duration = acc

    def out(self, t):
        for (a, b), off in zip(self.ranges, self.offsets):
            if t < a:
                return off
            if t <= b:
                return off + (t - a)
        return self.duration


def build_ass(words, timeline, title, cfg, path):
    W, H = cfg["width"], cfg["height"]
    up = cfg["caption_uppercase"]
    font = cfg["caption_font"]
    size = cfg["caption_size"]
    hl = ass_color(cfg["caption_highlight"])
    lines = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}", "WrapStyle: 0",
        "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
        "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
        "MarginR, MarginV, Encoding",
        f"Style: Cap,{font},{size},{ass_color(cfg['caption_color'])},&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,"
        f"1,0,1,{cfg['caption_outline']},3,5,60,60,0,1",
        f"Style: Hook,{font},{cfg['hook_size']},&H00101010,&H000000FF,&H00FFFFFF,&H64000000,-1,0,0,0,100,100,0,0,3,"
        f"22,0,8,110,110,0,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]

    if cfg["hook"] and title:
        end = min(cfg["hook_seconds"], timeline.duration)
        lines.append(f"Dialogue: 1,{ts(0)},{ts(end)},Hook,,0,0,0,,{{\\an8\\pos({W // 2},{int(H * cfg.get('hook_y', 0.12))})"
                     f"\\fad(120,180)\\fscx85\\fscy85\\t(0,140,\\fscx100\\fscy100)}}{clean(title, up)}")

    if cfg["captions"]:
        items = []
        for w in words:
            txt = clean(w["text"], up)
            if not txt:
                continue
            items.append({"t": txt, "s": timeline.out(w["start"]), "e": timeline.out(w["end"]),
                          "brk": bool(re.search(r"[.!?,;:…]$", w["text"]))})
        groups, cur = [], []
        for i, it in enumerate(items):
            if cur:
                chars = sum(len(x["t"]) + 1 for x in cur) + len(it["t"])
                gap = it["s"] - cur[-1]["e"]
                if len(cur) >= cfg["caption_max_words"] or chars > cfg["caption_max_chars"] or gap > 0.35 or cur[-1]["brk"]:
                    groups.append(cur)
                    cur = []
            cur.append(it)
        if cur:
            groups.append(cur)
        y = int(H * cfg["caption_y"])
        for gi, g in enumerate(groups):
            g_end = g[-1]["e"] + 0.2
            if gi + 1 < len(groups):
                g_end = min(g_end, groups[gi + 1][0]["s"])
            for k, it in enumerate(g):
                s = g[0]["s"] if k == 0 else it["s"]
                e = g[k + 1]["s"] if k + 1 < len(g) else g_end
                if e - s < 0.02:
                    continue
                parts = []
                for j, other in enumerate(g):
                    if j == k:
                        parts.append(f"{{\\c{hl}\\fscx112\\fscy112}}{other['t']}{{\\r}}")
                    else:
                        parts.append(other["t"])
                pop = "\\fscx70\\fscy70\\t(0,90,\\fscx100\\fscy100)" if k == 0 else ""
                lines.append(f"Dialogue: 0,{ts(s)},{ts(e)},Cap,,0,0,0,,{{\\an5\\pos({W // 2},{y}){pop}}}"
                             + " ".join(parts))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
