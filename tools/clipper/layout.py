"""Decide how a horizontal video becomes vertical, and compose each frame.

- split:  facecam on top, gameplay below (the standard stream-clip layout). Used when a small face sits in the
          same place for most of the video, which is what a webcam overlay looks like.
- game:   no facecam: gameplay zoomed in over a blurred copy of itself.
- follow: a big face fills the frame (just chatting, podcasts): crop to 9:16 and follow whoever talks.
"""
import subprocess
from collections import Counter

import cv2
import numpy as np

from .config import HERE
from .reframe import DETECT_W, _even


def _grab(src, t, w, h, exact=False):
    """One frame at t seconds. exact=True decodes from the start of the file: needed for live segments, which do
    not begin on a keyframe and break fast seeking; fine for short files only."""
    seek = ["-i", str(src), "-ss", f"{t:.2f}"] if exact else ["-ss", f"{t:.2f}", "-i", str(src)]
    cmd = ["ffmpeg", "-v", "error", *seek, "-frames:v", "1", "-an",
           "-vf", f"scale={w}:{h}", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"]
    buf = subprocess.run(cmd, capture_output=True).stdout
    return np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3) if len(buf) == w * h * 3 else None


_DETECTORS = {}


def facecam_sample(src, t, info, exact=False):
    """One frame's evidence for facecam detection: (faces [(cx, cy, w, h)], steady-edge map) or None."""
    W, H = info["width"], info["height"]
    dw, dh = DETECT_W, _even(DETECT_W * H / W)
    if (dw, dh) not in _DETECTORS:
        _DETECTORS[(dw, dh)] = cv2.FaceDetectorYN.create(
            str(HERE / "models" / "face_detection_yunet_2023mar.onnx"), "", (dw, dh), 0.7, 0.3, 20)
    frame = _grab(src, t, dw, dh, exact)
    if frame is None:
        return None
    edges = cv2.Canny(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), 40, 120) > 0
    _, found = _DETECTORS[(dw, dh)].detect(frame)
    faces = [((float(f[0]) + float(f[2]) / 2) / dw, (float(f[1]) + float(f[3]) / 2) / dh, float(f[2]) / dw,
              float(f[3]) / dh) for f in (found if found is not None else [])]
    return faces, edges


def facecam_from_samples(samples):
    """samples: list of facecam_sample results (frames spread over time, the more spread the better)."""
    samples = [x for x in samples if x is not None]
    if not samples:
        return None
    hits = [f for faces, _ in samples for f in faces]
    return facecam_from(hits, len(samples), np.mean([e for _, e in samples], axis=0))


def detect_facecam(src, info, samples=40):
    """Return the facecam area {x0,x1,y0,y1,fh} in 0..1, 'big' for a full-frame face, or None."""
    dur = info["duration"]
    return facecam_from_samples([facecam_sample(src, dur * (i + 0.5) / samples, info) for i in range(samples)])


def facecam_from(hits, n, edge_freq):
    """Pure part of detect_facecam: faces [(cx, cy, w, h)] from n sampled frames, plus edge persistence."""
    if not n or not hits:
        return None
    # The face moves around inside the webcam, so cluster loosely and keep the area it moves in.
    bins = Counter((int(c[0] / 0.2), int(c[1] / 0.2)) for c in hits)
    (bx, by), _ = bins.most_common(1)[0]
    seed = [c for c in hits if int(c[0] / 0.2) == bx and int(c[1] / 0.2) == by]
    mx, my = float(np.median([c[0] for c in seed])), float(np.median([c[1] for c in seed]))
    group = [c for c in hits if abs(c[0] - mx) < 0.15 and abs(c[1] - my) < 0.2]
    frames_with = len(group)
    if frames_with < 0.35 * n:
        return None
    fh = float(np.median([g[3] for g in group]))
    if fh > 0.33:
        return "big"
    x0 = float(np.percentile([g[0] - g[2] / 2 for g in group], 10))
    x1 = float(np.percentile([g[0] + g[2] / 2 for g in group], 90))
    y0 = float(np.percentile([g[1] - g[3] / 2 for g in group], 10))
    y1 = float(np.percentile([g[1] + g[3] / 2 for g in group], 90))
    cam = {"x0": x0, "x1": x1, "y0": y0, "y1": y1, "fh": fh,
           # where the face centre wanders; the overlay border is searched outward from here
           "cx0": float(np.percentile([g[0] for g in group], 10)), "cx1": float(np.percentile([g[0] for g in group], 90)),
           "cy0": float(np.percentile([g[1] for g in group], 10)), "cy1": float(np.percentile([g[1] for g in group], 90)),
           "fw": float(np.median([g[2] for g in group]))}
    rect = _overlay_rect(edge_freq, cam) if edge_freq is not None else None
    if rect:
        cam["rect"] = rect
    return cam


def _overlay_rect(freq, cam):
    """Find the webcam overlay's border: straight edges that stay put while the game changes around them."""
    h, w = freq.shape
    persist = freq > 0.45
    # inner bounds: a bit around the face centres (a face can be cut off by the overlay edge)
    fx0, fx1 = int((cam["cx0"] - cam["fw"] * 0.2) * w), int((cam["cx1"] + cam["fw"] * 0.2) * w)
    fy0, fy1 = int((cam["cy0"] - cam["fh"] * 0.2) * h), int((cam["cy1"] + cam["fh"] * 0.2) * h)

    def best_line(lo, hi, profile, edge_value, face_side_hi):
        """Steady line closest to the face (face_side_hi: the face is on the high side of the range)."""
        lo, hi = max(0, lo), min(len(profile) - 1, hi)
        if hi <= lo:
            return edge_value, 0.0
        idx = range(hi, lo - 1, -1) if face_side_hi else range(lo, hi + 1)
        for i in idx:
            if profile[i] >= 0.5:
                return i, float(profile[i])
        return edge_value, 0.0

    ys, ye = max(0, fy0 - h // 10), min(h, fy1 + h // 10)
    xs, xe = max(0, fx0 - w // 10), min(w, fx1 + w // 10)
    col = persist[ys:ye].mean(0)  # fraction of the face rows where this column is a steady edge
    row = persist[:, xs:xe].mean(1)
    left, lv = best_line(fx0 - w // 3, fx0 - 2, col, 0, True)
    right, rv = best_line(fx1 + 2, fx1 + w // 3, col, w - 1, False)
    top, tv = best_line(fy0 - h // 3, fy0 - 2, row, 0, True)
    bottom, bv = best_line(fy1 + 2, fy1 + h // 3, row, h - 1, False)
    # a side with no steady line is where the cam touches the frame edge (or has no border): use the frame edge
    # only if the face area is already close to it
    if lv < 0.5:
        left = 0 if fx0 < w * 0.12 else None
    if rv < 0.5:
        right = w - 1 if fx1 > w * 0.88 else None
    if tv < 0.5:
        top = 0 if fy0 < h * 0.15 else None
    if bv < 0.5:
        bottom = h - 1 if fy1 > h * 0.85 else None
    if None in (left, right, top, bottom) or right - left < w * 0.08 or bottom - top < h * 0.1:
        return None
    return (left + 1) / w, (top + 1) / h, (right - left - 1) / w, (bottom - top - 1) / h


def choose_layout(src, info, cfg, cam="detect"):
    """cam: a facecam_from_samples result gathered elsewhere (the live bot samples the whole live), or "detect"."""
    want = cfg["layout"]
    if info["width"] / info["height"] <= 0.75:  # already vertical
        return {"kind": "follow"}
    if want in ("follow", "game"):
        return {"kind": want}
    if isinstance(cam, str) and cam == "detect":
        cam = detect_facecam(src, info)
    if want == "split" and isinstance(cam, dict):
        return {"kind": "split", "cam": cam}
    if want == "split":
        print("   No facecam found; using the gameplay layout.")
        return {"kind": "game"}
    # auto
    if cfg["mode"] == "talk" or cam == "big":
        return {"kind": "follow"}
    if isinstance(cam, dict):
        return {"kind": "split", "cam": cam}
    return {"kind": "game"}


def panel_height(cam, info, cfg):
    """Webcam panel height in pixels: a fixed, small share of the frame (cam_height, default 28%)."""
    return _even(cfg["height"] * min(max(cfg["cam_height"], 0.18), 0.40))


def cam_box(cam, info, out_w, panel_h):
    """Crop for the webcam panel: tight on the face, never outside the webcam (so no gameplay gets in).

    Returns (x, y, w, h) as fractions of the source frame, with the panel's aspect ratio.
    """
    W, H = info["width"], info["height"]
    aspect = out_w / panel_h
    fx0, fx1, fy0, fy1 = cam["x0"] * W, cam["x1"] * W, cam["y0"] * H, cam["y1"] * H  # where the face moves
    fcx, fcy = (fx0 + fx1) / 2, (fy0 + fy1) / 2
    fh = cam["fh"] * H
    if cam.get("rect"):  # the webcam's own border, pulled in 2% so its frame line stays out too
        rx, ry, rw, rh = cam["rect"]
        mx, my = rw * W * 0.02, rh * H * 0.02
        ax0, ay0, ax1, ay1 = rx * W + mx, ry * H + my, (rx + rw) * W - mx, (ry + rh) * H - my
    else:  # no border found: stay close around the face, where only the webcam can be
        uw = max(fx1 - fx0, fh)
        ax0, ax1 = max(0, fcx - uw), min(W, fcx + uw)
        ay0, ay1 = max(0, fy0 - fh * 0.3), min(H, fy1 + fh * 0.3)
    bh = max(fh * 1.5, (fy1 - fy0) * 1.1)
    bw = bh * aspect
    if bw > ax1 - ax0:
        bw = ax1 - ax0
        bh = bw / aspect
    if bh > ay1 - ay0:
        bh = ay1 - ay0
        bw = bh * aspect
    x0 = min(max(fcx - bw / 2, ax0), ax1 - bw)
    y0 = min(max(fcy - bh * 0.48, ay0), ay1 - bh)  # face a touch above the middle
    return x0 / W, y0 / H, bw / W, bh / H


def webcam_area(cam, box):
    """The whole webcam overlay (to keep out of the gameplay panel): its border if found, else the face crop."""
    return tuple(cam["rect"]) if cam.get("rect") else tuple(box)


def split_compose(frame, box, cfg, avoid=None):
    out_w, out_h = cfg["width"], cfg["height"]
    top_h = cfg["_panel_h"]
    bot_h = out_h - top_h
    H, W = frame.shape[:2]
    x0, y0, bw, bh = box
    cam = frame[int(y0 * H): int((y0 + bh) * H), int(x0 * W): int((x0 + bw) * W)]
    top = cv2.resize(cam, (out_w, top_h), interpolation=cv2.INTER_CUBIC if cam.shape[0] < top_h else cv2.INTER_AREA)
    ga = out_w / bot_h
    gh = H / cfg["split_game_zoom"]
    gw = gh * ga
    if gw > W:
        gw, gh = W, W / ga
    gx, gy = (W - gw) / 2, (H - gh) / 2
    # keep the webcam out of the gameplay panel when a small shift is enough
    av = avoid or box
    cx0, cx1 = av[0] * W, (av[0] + av[2]) * W
    if cx1 > gx and cx0 < gx + gw and av[1] * H < gy + gh:
        m = W * 0.01  # the detected border sits a pixel or two inside the real one
        if cx1 - gx < W * 0.12 and cx1 + m + gw <= W:
            gx = cx1 + m
        elif gx + gw - cx0 < W * 0.12 and cx0 - m - gw >= 0:
            gx = cx0 - m - gw
    gx, gy = int(gx), int(gy)
    game = frame[gy: gy + int(gh), gx: gx + int(gw)]
    bot = cv2.resize(game, (out_w, bot_h), interpolation=cv2.INTER_CUBIC if game.shape[0] < bot_h else cv2.INTER_AREA)
    out = np.vstack([top, bot])
    out[top_h - 3: top_h + 3] = 0  # thin seam
    return out
