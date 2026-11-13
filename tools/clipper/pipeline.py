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
        clips, words, lang, summary = find_gaming_highlights(
            src, info, loud, HOP, cfg, lambda r: transcribe_ranges(src, r, cfg, work / "moments.json"))
    else:
        log("== 2/4 Transcribing and finding the best moments")
        tr = transcribe(src, cfg, work / "transcript.json")
        words, lang = tr["words"], tr.get("language")
        clips = find_highlights(words, loud, HOP, cfg, lang)
        summary = {"requested": cfg["clips"], "picked": len(clips), "picker": clips[0]["picker"] if clips else "-"}
    log(f"   {len(clips)} clips picked by {clips[0]['picker'] if clips else '-'}, language: {lang}")

    log("== 3/4 Rendering vertical clips")
    done, failed = [], []
    for rank, c in enumerate(clips, 1):
        name = f"clip-{rank:02d}-{slugify(c['title'])[:40]}.mp4"
        out = folder / name
        log(f"   [{rank}/{len(clips)}] {c['title']}  ({c['seconds']:.0f}s)")
        try:
            meta = render_clip(src, info, words, c, cfg, out, work, layout)
        except Exception as e:  # one bad clip should not sink the batch
            log(f"   skipped: {e}")
            failed.append(f"clipe {rank} ({c['title']}): {str(e)[:160]}")
            continue
        done.append({"rank": rank, "file": name, "thumb": meta["thumb"], "title": c["title"], "score": c["score"],
                     "reason": c["reason"], "picker": c["picker"], "duration": meta["duration"], "cuts": meta["cuts"],
                     "layout": meta["layout"], "source_start": round(c["start"], 2), "source_end": round(c["end"], 2),
                     "text": c["text"]})
        (folder / name).with_suffix(".txt").write_text(f"{c['title']}\n\n{c['text']}\n", encoding="utf-8")

    log("== 4/4 Writing the review page")
    summary.update(rendered=len(done), failed=failed)
    summary["explanation"] = explain(summary)
    log("   " + summary["explanation"])
    write_report(folder, src.name, done, summary)
    if not cfg.get("keep_work"):
        for p in work.glob("*.ass"):
            p.unlink()
    if downloaded:  # keep the download, but out of the queue
        (src.parent / "done").mkdir(exist_ok=True)
        shutil.move(str(src), str(src.parent / "done" / src.name))
    log(f"== Done in {(time.time() - t0) / 60:.1f} min: {len(done)} clips in {folder}")
    log(f"   Open {folder / 'index.html'} to review them.")
    return folder


def explain(s):
    """One honest sentence: what was asked, what came out, and why any difference."""
    req, out = s["requested"], s["rendered"]
    msg = f"Pedido: até {req} clipes. Saíram {out}."
    if out >= req:
        return msg
    reasons = []
    if "strong" in s and s["strong"] < req:
        reasons.append(f"só {s['strong']} momentos fortes de verdade foram encontrados (pico de áudio claro ou "
                       f"palavras de hype); trechos fracos não viram clipe só para completar a conta")
    elif s["picked"] < req:
        reasons.append(f"o seletor ({s['picker']}) escolheu {s['picked']} momentos que se sustentam sozinhos")
    if s.get("failed"):
        reasons.append(f"{len(s['failed'])} falharam ao renderizar: " + "; ".join(s["failed"]))
    return msg + (" Motivo: " + "; ".join(reasons) + "." if reasons else "")


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
