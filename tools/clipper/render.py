"""Render one clip: (talk mode) cut pauses, reframe to vertical, burn captions, normalize loudness."""
import math
import os
import subprocess
from pathlib import Path

from .captions import Timeline, build_ass
from .config import HERE
from .layout import cam_box, panel_height, split_compose
from .media import run
from .reframe import _frames, camera_at, crop_frame, plan_camera, track_faces


def keep_ranges(words, clip, cfg):
    """Source time ranges to keep. Gaming mode keeps the whole window; talk mode drops long pauses."""
    start, end = clip["start"], clip["end"]
    maxp, pad = cfg["max_pause"], cfg["pause_pad"]
    inside = [w for w in words if start - 0.01 <= w["start"] and w["end"] <= end + 0.5]
    if maxp <= 0 or len(inside) < 2:
        return [(start, end)]
    ranges = [[max(0.0, inside[0]["start"] - 0.08), None]]
    for w, nxt in zip(inside, inside[1:]):
        if nxt["start"] - w["end"] > maxp:
            ranges[-1][1] = w["end"] + pad
            ranges.append([nxt["start"] - pad, None])
    ranges[-1][1] = inside[-1]["end"] + 0.35
    return [(a, b) for a, b in ranges if b > a]


def quantize(ranges, start, fps):
    out = []
    for a, b in ranges:
        qa = start + math.ceil(round((a - start) * fps, 6)) / fps
        qb = start + math.ceil(round((b - start) * fps, 6)) / fps
        if qb - qa >= 1 / fps:
            out.append((qa, qb))
    return out


def _filter_path(p, cwd):
    rel = os.path.relpath(p, cwd).replace("\\", "/")
    return rel.replace(":", "\\:").replace("'", "\\'")


def render_clip(src, info, words, clip, cfg, out_path, workdir, layout):
    workdir = Path(workdir)
    workdir.mkdir(parents=True, exist_ok=True)
    fps, W, H = cfg["fps"], cfg["width"], cfg["height"]
    ranges = keep_ranges(words, clip, cfg)
    S = ranges[0][0]
    E = min(ranges[-1][1], info["duration"])
    ranges = quantize([(a, min(b, E)) for a, b in ranges], S, fps)
    E = ranges[-1][1]
    tl = Timeline(ranges)

    kind = layout["kind"]
    plan, box = [], None
    if kind == "follow":
        samples = track_faces(src, S, E, info)
        kind, plan = plan_camera(samples, info, W, H)
        if kind == "fit":
            kind = "game"
    elif kind == "split":
        cfg = dict(cfg)
        cfg["_panel_h"] = panel_height(layout["cam"], info, cfg)
        cfg["caption_y"] = cfg["_panel_h"] / H  # captions sit on the seam
        box = cam_box(layout["cam"], info, W, cfg["_panel_h"])

    if kind == "game":  # gameplay band sits in the middle: text goes on the blurred bands above and below
        band = min(1.0, cfg["game_zoom"] * W / info["width"] * info["height"] / H)
        cfg = dict(cfg)
        cfg["caption_y"] = min(0.9, 0.5 + band / 2 + 0.07)
        cfg["hook_y"] = max(0.05, 0.5 - band / 2 - 0.13)

    ass = workdir / f"{out_path.stem}.ass"
    clip_words = [w for w in words if S - 0.05 <= w["start"] < E]
    build_ass(clip_words, tl, clip.get("hook"), cfg, ass)

    # decode at source resolution (capped at 1080 tall for speed)
    sh = min(info["height"], 1080)
    sw = int(round(info["width"] * sh / info["height"] / 2)) * 2
    sh = int(round(sh / 2)) * 2

    sel = "+".join(f"between(t,{a - S:.4f},{b - S - 1e-4:.4f})" for a, b in ranges)
    lufs = cfg["loudness_lufs"]
    fc = (f"[0:v]ass='{_filter_path(ass, workdir)}':fontsdir='{_filter_path(HERE / 'fonts', workdir)}',format=yuv420p[v];"
          f"[1:a]aselect='{sel}',asetpts=N/SR/TB,loudnorm=I={lufs}:TP=-1.5:LRA=11,aresample=48000[a]")
    enc = subprocess.Popen(
        ["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}", "-r", str(fps),
         "-i", "-", "-ss", f"{S:.3f}", "-t", f"{E - S:.3f}", "-i", str(src), "-filter_complex", fc,
         "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", cfg["preset"], "-crf", str(cfg["crf"]),
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-shortest", str(out_path.resolve())],
        stdin=subprocess.PIPE, stderr=subprocess.PIPE, cwd=workdir)

    zooms = [1.0 if i % 2 == 0 else cfg["punch_zoom"] for i in range(len(ranges))]
    ri, written = 0, 0
    try:
        for i, frame in enumerate(_frames(src, S, E - S, fps, sw, sh)):
            t = S + (i + 0.5) / fps
            while ri < len(ranges) and t >= ranges[ri][1]:
                ri += 1
            if ri >= len(ranges):
                break
            if t < ranges[ri][0]:
                continue
            if kind == "split":
                out = split_compose(frame, box, cfg)
            elif kind == "game":
                out = crop_frame(frame, "fit", 0.5, 0.5, 0.3, cfg["game_zoom"] * zooms[ri], W, H)
            else:
                cx, cy, fh = camera_at(plan, t - S)
                out = crop_frame(frame, "follow", cx, cy, fh, zooms[ri], W, H)
            enc.stdin.write(out.tobytes())
            written += 1
        enc.stdin.close()
    except BrokenPipeError:
        pass
    err = enc.stderr.read().decode("utf-8", "replace")
    if enc.wait() != 0 or written == 0:
        raise RuntimeError(f"render failed for {out_path.name}: {err.strip()[-600:]}")

    thumb = out_path.with_suffix(".jpg")
    run(["ffmpeg", "-y", "-v", "error", "-ss", "0.6", "-i", str(out_path), "-frames:v", "1", "-q:v", "3", str(thumb)])
    return {"duration": round(tl.duration, 2), "layout": kind, "cuts": len(ranges) - 1, "thumb": thumb.name}
