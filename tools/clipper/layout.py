"""Decide how a horizontal video becomes vertical, and compose each frame.

- split:  facecam on top, gameplay below (the standard stream-clip layout). Used when a small face sits in the
          same place for most of the video, which is what a webcam overlay looks like.
- game:   no facecam: gameplay zoomed in over a blurred copy of itself.
- follow: a big face fills the frame (just chatting, podcasts): crop to 9:16 and follow whoever talks.
"""
import math
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
    if edge_freq is not None:
        circle = _overlay_circle(edge_freq, cam)
        if circle:  # a round webcam: a steady circle around the face
            cam["circle"] = circle
            cx, cy, rx, ry = circle
            cam["rect"] = (cx - rx, cy - ry, 2 * rx, 2 * ry)  # its bounding square
            cam["shape"] = "round"
        else:
            rect = _overlay_rect(edge_freq, cam)
            if rect:
                cam["rect"] = rect
                cam["shape"] = "rect"
    cam.setdefault("shape", "unknown")
    return cam


N_ANGLES = 72


def _overlay_circle(freq, cam, min_score=0.6):
    """Find a round webcam: the circle around the face whose outline stays put while the game changes.

    Brute-force search over centres near the face and radii from a little bigger than the face up to ~2.6x,
    scoring the share of points on the circle that are steady edges (1 px tolerance). Returns
    (cx, cy, r/width, r/height) as fractions, or None.
    """
    h, w = freq.shape
    p = freq > 0.45
    tol = p.copy()  # 1 px tolerance
    tol[1:, :] |= p[:-1, :]
    tol[:-1, :] |= p[1:, :]
    tol[:, 1:] |= p[:, :-1]
    tol[:, :-1] |= p[:, 1:]
    fcx, fcy = (cam["cx0"] + cam["cx1"]) / 2 * w, (cam["cy0"] + cam["cy1"]) / 2 * h
    fh = cam["fh"] * h
    ang = np.arange(N_ANGLES) * 2 * np.pi / N_ANGLES
    cos, sin = np.cos(ang), np.sin(ang)
    radii = np.arange(0.75 * fh, 2.6 * fh, 1.0)
    best = (0.0, 0.0, 0.0, 0.0)
    span = 0.6 * fh
    for cy in np.arange(fcy - span, fcy + span + 1e-9, 2.0):
        for cx in np.arange(fcx - span, fcx + span + 1e-9, 2.0):
            px = np.rint(cx + radii[:, None] * cos[None, :]).astype(int)
            py = np.rint(cy + radii[:, None] * sin[None, :]).astype(int)
            ok = (px >= 0) & (px < w) & (py >= 0) & (py < h)
            hit = np.zeros(px.shape, bool)
            hit[ok] = tol[py[ok], px[ok]]
            sc = hit.mean(axis=1)
            i = int(np.argmax(sc))
            if sc[i] > best[0]:
                best = (float(sc[i]), float(cx), float(cy), float(radii[i]))
    score, cx, cy, r = best
    if score < min_score:
        return None
    # the whole face must be inside the circle
    if math.hypot(fcx - cx, fcy - cy) + 0.45 * fh > r:
        return None
    return (cx / w, cy / h, r / w, r / h)


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
    if want in ("split", "pip") and isinstance(cam, dict):
        return {"kind": want, "cam": cam}
    if want in ("split", "pip"):
        print("   No facecam found; using the gameplay layout.")
        return {"kind": "game"}
    # auto: the streamer's webcam, small in the corner, over full-screen gameplay ("pip"); "split" stacks them
    if cfg["mode"] == "talk" or cam == "big":
        return {"kind": "follow"}
    if isinstance(cam, dict):
        return {"kind": "split" if want == "split" else "pip", "cam": cam}
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


def pip_layout(cam, info, cfg):
    """Picture-in-picture: the streamer's whole webcam, in its own shape, small in the top-left corner over a
    full-screen gameplay crop. Nothing of the face is cut.

    Returns {"src": (x, y, w, h) fractions of the source, "round": bool, "dst": (x, y, w, h) output pixels,
             "game": (x, y, w, h) fractions of the source}.
    """
    W, H = info["width"], info["height"]
    out_w, out_h = cfg["width"], cfg["height"]
    fx0, fx1, fy0, fy1 = cam["x0"] * W, cam["x1"] * W, cam["y0"] * H, cam["y1"] * H
    fh = cam["fh"] * H
    rnd = cam.get("shape") == "round"
    if rnd:  # the circle, a hair inside so the game around it never shows
        cx, cy, rx, _ = cam["circle"]
        r = rx * W * 0.97
        x0, y0, bw, bh = cx * W - r, cy * H - r, 2 * r, 2 * r
    elif cam.get("rect"):  # the rectangle, inset 1.5% so its frame line and the game stay out
        rx, ry, rw, rh = cam["rect"]
        x0, y0, bw, bh = (rx + rw * 0.015) * W, (ry + rh * 0.015) * H, rw * W * 0.97, rh * H * 0.97
    else:  # no border found: a generous box around the face, so nothing of it is cut
        fcx, fcy = (fx0 + fx1) / 2, (fy0 + fy1) / 2
        bw = max(fx1 - fx0, fh) * 1.8
        bh = (fy1 - fy0) + fh * 0.8
        x0, y0 = fcx - bw / 2, fcy - bh / 2
    x1, y1 = x0 + bw, y0 + bh
    if not (rnd or cam.get("rect")):
        # no border known: grow the box to contain everywhere the face goes, so nothing of it is cut.
        # (With a known border the whole webcam is shown; a face box poking past it is detector overshoot.)
        x1, y1 = max(x1, fx1), max(y1, fy1)
        x0, y0 = min(x0, fx0), min(y0, fy0)
    x0, y0, x1, y1 = max(0, x0), max(0, y0), min(W, x1), min(H, y1)
    bw, bh = x1 - x0, y1 - y0
    # small, in the top-left corner
    pw = out_w * cfg["pip_width"]
    ph = pw * bh / bw
    if ph > out_h * 0.3:
        ph = out_h * 0.3
        pw = ph * bw / bh
    m = cfg["pip_margin"]
    dst = (int(m), int(out_h * cfg["pip_top"]), int(round(pw / 2) * 2), int(round(ph / 2) * 2))
    # gameplay fills the frame (9:16 crop), steering clear of the webcam when a small shift does it
    ga = out_w / out_h
    gh = H / cfg["split_game_zoom"]
    gw = gh * ga
    if gw > W:
        gw, gh = W, W / ga
    gx, gy = (W - gw) / 2, (H - gh) / 2
    if x1 > gx and x0 < gx + gw:
        mg = W * 0.01
        if x1 - gx < W * 0.12 and x1 + mg + gw <= W:
            gx = x1 + mg
        elif gx + gw - x0 < W * 0.12 and x0 - mg - gw >= 0:
            gx = x0 - mg - gw
    return {"src": (x0 / W, y0 / H, bw / W, bh / H), "round": rnd, "dst": dst,
            "game": (gx / W, gy / H, gw / W, gh / H)}


_MASKS = {}


def pip_compose(frame, L, cfg):
    out_w, out_h = cfg["width"], cfg["height"]
    H, W = frame.shape[:2]
    gx, gy, gw, gh = L["game"]
    game = frame[int(gy * H): int((gy + gh) * H), int(gx * W): int((gx + gw) * W)]
    out = cv2.resize(game, (out_w, out_h), interpolation=cv2.INTER_CUBIC if game.shape[0] < out_h else cv2.INTER_AREA)
    sx, sy, sw, sh = L["src"]
    cam = frame[int(sy * H): int((sy + sh) * H), int(sx * W): int((sx + sw) * W)]
    x, y, pw, ph = L["dst"]
    cam = cv2.resize(cam, (pw, ph), interpolation=cv2.INTER_AREA if cam.shape[0] > ph else cv2.INTER_CUBIC)
    border = max(3, pw // 90)
    if L["round"]:
        if (pw, ph) not in _MASKS:  # anti-aliased circle
            yy, xx = np.mgrid[0:ph, 0:pw]
            d = np.hypot(xx - (pw - 1) / 2, yy - (ph - 1) / 2)
            _MASKS[(pw, ph)] = np.clip(min(pw, ph) / 2 - d, 0, 1)[..., None]
        a = _MASKS[(pw, ph)]
        roi = out[y:y + ph, x:x + pw]
        out[y:y + ph, x:x + pw] = (cam * a + roi * (1 - a)).astype(np.uint8)
        cv2.circle(out, (x + pw // 2, y + ph // 2), min(pw, ph) // 2, (255, 255, 255), border, cv2.LINE_AA)
    else:
        out[y:y + ph, x:x + pw] = cam
        cv2.rectangle(out, (x, y), (x + pw - 1, y + ph - 1), (255, 255, 255), border, cv2.LINE_AA)
    return out
