"""Long video in, a folder of ready vertical clips out."""
import json
import shutil
import sys
import time
from datetime import date
from pathlib import Path

from .config import ROOT, load_env
from .gaming import find_gaming_highlights
from .highlights import find_highlights
from .layout import choose_layout
from .media import VIDEO_EXT, download, is_url, loudness_curve, need, probe, slugify
from .render import render_clip
from .report import write_report
from .transcribe import transcribe, transcribe_ranges

HOP = 0.1


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def process(source, cfg, out_root=None):
    load_env()
    need("ffmpeg")
    need("ffprobe")
    t0 = time.time()
    out_root = Path(out_root or ROOT / "outbox")
    downloaded = is_url(source)
    if downloaded:
        log(f"== Downloading {source}")
        source = download(source, ROOT / "streams")
    src = Path(source).resolve()
    if not src.exists():
        raise SystemExit(f"File not found: {src}")
    info = probe(src)
    slug = f"{slugify(src.stem)}-{date.today().isoformat()}"
    folder = out_root / slug
    work = folder / ".work"
    work.mkdir(parents=True, exist_ok=True)
    log(f"== {src.name}: {info['width']}x{info['height']}, {info['duration'] / 60:.1f} min -> {folder}")

    log(f"== 1/4 Listening to the whole video ({cfg['mode']} mode)")
    loud = loudness_curve(src, HOP)
    layout = choose_layout(src, info, cfg)
    log(f"   layout: {layout['kind']}" + (" (facecam found)" if layout["kind"] == "split" else ""))

    if cfg["mode"] == "gaming":
        log("== 2/4 Finding hype moments")
        clips, words, lang = find_gaming_highlights(
            src, info, loud, HOP, cfg, lambda r: transcribe_ranges(src, r, cfg, work / "moments.json"))
    else:
        log("== 2/4 Transcribing and finding the best moments")
        tr = transcribe(src, cfg, work / "transcript.json")
        words, lang = tr["words"], tr.get("language")
        clips = find_highlights(words, loud, HOP, cfg, lang)
    log(f"   {len(clips)} clips picked by {clips[0]['picker'] if clips else '-'}, language: {lang}")

    log("== 3/4 Rendering vertical clips")
    done = []
    for rank, c in enumerate(clips, 1):
        name = f"clip-{rank:02d}-{slugify(c['title'])[:40]}.mp4"
        out = folder / name
        log(f"   [{rank}/{len(clips)}] {c['title']}  ({c['seconds']:.0f}s)")
        try:
            meta = render_clip(src, info, words, c, cfg, out, work, layout)
        except Exception as e:  # one bad clip should not sink the batch
            log(f"   skipped: {e}")
            continue
        done.append({"rank": rank, "file": name, "thumb": meta["thumb"], "title": c["title"], "score": c["score"],
                     "reason": c["reason"], "picker": c["picker"], "duration": meta["duration"], "cuts": meta["cuts"],
                     "layout": meta["layout"], "source_start": round(c["start"], 2), "source_end": round(c["end"], 2),
                     "text": c["text"]})
        (folder / name).with_suffix(".txt").write_text(f"{c['title']}\n\n{c['text']}\n", encoding="utf-8")

    log("== 4/4 Writing the review page")
    write_report(folder, src.name, done)
    if not cfg.get("keep_work"):
        for p in work.glob("*.ass"):
            p.unlink()
    if downloaded:  # keep the download, but out of the queue
        (src.parent / "done").mkdir(exist_ok=True)
        shutil.move(str(src), str(src.parent / "done" / src.name))
    log(f"== Done in {(time.time() - t0) / 60:.1f} min: {len(done)} clips in {folder}")
    log(f"   Open {folder / 'index.html'} to review them.")
    return folder


def pending_inbox():
    inbox = ROOT / "streams"
    inbox.mkdir(exist_ok=True)
    done_dir = inbox / "done"
    vids = sorted((p for p in inbox.iterdir() if p.is_file() and p.suffix.lower() in VIDEO_EXT),
                  key=lambda p: p.stat().st_mtime)
    return vids, done_dir


def watch(cfg, interval=15):
    """Clip every video that lands in streams/, then move it to streams/done/. Runs until stopped (Ctrl+C)."""
    log(f"== Watching {ROOT / 'streams'} (Ctrl+C to stop)")
    sizes = {}
    while True:
        vids, done_dir = pending_inbox()
        for v in vids:
            size = v.stat().st_size
            if sizes.get(v) != size:  # still copying: wait for the size to settle
                sizes[v] = size
                continue
            try:
                process(v, cfg)
            except SystemExit as e:
                log(f"   {v.name}: {e}")
            except Exception as e:
                log(f"   {v.name} failed: {e}")
            done_dir.mkdir(exist_ok=True)
            shutil.move(str(v), str(done_dir / v.name))
            sizes.pop(v, None)
        time.sleep(interval)
