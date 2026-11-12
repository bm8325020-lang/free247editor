"""Turn a horizontal shot into a vertical one that follows whoever is talking.

Faces come from OpenCV's YuNet detector (bundled model, runs on the CPU). The active speaker is the face whose
mouth moves most; the camera holds steady on small moves and hard-cuts when the speaker changes, the way an editor
would. Clips with no faces (screen recordings, slides) get the whole frame over a blurred copy instead.
"""
import subprocess

import cv2
import numpy as np

from .config import HERE

DETECT_FPS = 5.0
DETECT_W = 640


def _even(x):
    return max(2, int(round(x / 2)) * 2)


def _frames(src, start, dur, fps, w, h):
    """Yield BGR frames of src[start:start+dur] at a constant fps, scaled to w x h."""
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", str(src), "-an",
           "-vf", f"fps={fps},scale={w}:{h}:flags=bilinear", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=w * h * 3 * 4)
    size = w * h * 3
    try:
        while True:
            buf = p.stdout.read(size)
            if len(buf) < size:
                break
            yield np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3)
    finally:
        p.stdout.close()
        p.wait()


def track_faces(src, start, end, info):
    """Return samples [(t, faces)] where faces = [(cx, cy, fw, fh, mouth_activity)] in 0..1 units."""
    W, H = info["width"], info["height"]
    dw, dh = DETECT_W, _even(DETECT_W * H / W)
    det = cv2.FaceDetectorYN.create(str(HERE / "models" / "face_detection_yunet_2023mar.onnx"), "", (dw, dh), 0.6,
                                    0.3, 50)
    samples, prev_gray = [], None
    for i, frame in enumerate(_frames(src, start, end - start, DETECT_FPS, dw, dh)):
        t = i / DETECT_FPS
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        _, found = det.detect(frame)
        faces = []
        for f in (found if found is not None else []):
            x, y, w, h = [float(v) for v in f[:4]]
            if w < dw * 0.03:
                continue
            # Mouth region: lower part of the face box, between the mouth-corner landmarks.
            ml, mr = min(f[10], f[12]), max(f[10], f[12])
            mx0, mx1 = int(max(0, ml - w * 0.1)), int(min(dw, mr + w * 0.1))
            my0, my1 = int(max(0, y + h * 0.62)), int(min(dh, y + h * 1.0))
            act = 0.0
            if prev_gray is not None and mx1 > mx0 + 2 and my1 > my0 + 2:
                act = float(np.mean(cv2.absdiff(gray[my0:my1, mx0:mx1], prev_gray[my0:my1, mx0:mx1])))
            faces.append(((x + w / 2) / dw, (y + h / 2) / dh, w / dw, h / dh, act))
        samples.append((t, faces))
        prev_gray = gray
    return samples


def plan_camera(samples, info, out_w, out_h):
    """Pick a crop center (0..1) per sample. Returns (layout, [(t, cx, cy, face_h)])."""
    if not samples:
        return "fit", []
    with_faces = sum(1 for _, f in samples if f) / len(samples)
    if with_faces < 0.25:
        return "fit", []
    W, H = info["width"], info["height"]
    crop_w_frac = min(1.0, (H * out_w / out_h) / W)

    # 1) raw target per sample: the face that talks most (mouth motion), size as a tie-breaker.
    raw, last = [], None
    for t, faces in samples:
        if not faces:
            raw.append(last)
            continue
        acts = np.array([f[4] for f in faces])
        sizes = np.array([f[3] for f in faces])
        score = acts / (acts.max() + 1e-6) + 0.35 * sizes / (sizes.max() + 1e-6)
        best = faces[int(np.argmax(score))]
        # Two people close enough to share the frame: frame them both.
        xs = [f[0] for f in faces if f[3] > 0.5 * sizes.max()]
        if len(xs) > 1 and (max(xs) - min(xs)) < crop_w_frac * 0.6:
            best = ((max(xs) + min(xs)) / 2, best[1], best[2], best[3], best[4])
        last = best
        raw.append(best)
    first = next((r for r in raw if r is not None), (0.5, 0.4, 0.2, 0.3, 0))
    raw = [r if r is not None else first for r in raw]

    # 2) smooth activity so one noisy sample does not flip the speaker: majority over ~1.2 s windows.
    switch_dist = max(0.12, crop_w_frac * 0.45)
    hold = int(DETECT_FPS * 1.2)
    plan, cur = [], raw[0]
    pending, pending_n = None, 0
    for (t, _), r in zip(samples, raw):
        if abs(r[0] - cur[0]) > switch_dist:
            if pending is not None and abs(r[0] - pending[0]) < switch_dist:
                pending_n += 1
            else:
                pending, pending_n = r, 1
            if pending_n >= hold:  # speaker really changed: hard cut
                cur, pending, pending_n = r, None, 0
        else:
            pending, pending_n = None, 0
            # follow small moves gently, ignore jitter
            a = 0.25
            cur = (cur[0] + a * (r[0] - cur[0]), cur[1] + a * (r[1] - cur[1]), cur[2], cur[3] + a * (r[3] - cur[3]), r[4])
        plan.append((t, cur[0], cur[1], cur[3]))
    return "follow", plan


def camera_at(plan, t):
    """Crop center at time t (seconds from clip start); no interpolation across hard cuts."""
    if not plan:
        return 0.5, 0.4, 0.3
    i = min(len(plan) - 1, max(0, int(t * DETECT_FPS)))
    _, cx, cy, fh = plan[i]
    if i + 1 < len(plan):
        _, cx2, cy2, fh2 = plan[i + 1]
        if abs(cx2 - cx) < 0.05:  # same shot: interpolate for smoothness
            k = t * DETECT_FPS - i
            cx, cy, fh = cx + (cx2 - cx) * k, cy + (cy2 - cy) * k, fh + (fh2 - fh) * k
    return cx, cy, fh


def crop_frame(frame, layout, cx, cy, fh, zoom, out_w, out_h):
    H, W = frame.shape[:2]
    if layout == "fit":
        # cover-scale the background, then blur and darken
        s = max(out_w / W, out_h / H)
        bw, bh = int(W * s), int(H * s)
        big = cv2.resize(frame, (bw // 8, bh // 8), interpolation=cv2.INTER_AREA)
        big = cv2.GaussianBlur(big, (0, 0), 6)
        big = cv2.resize(big, (bw, bh), interpolation=cv2.INTER_LINEAR)
        x0, y0 = (bw - out_w) // 2, (bh - out_h) // 2
        bg = (big[y0:y0 + out_h, x0:x0 + out_w] * 0.55).astype(np.uint8)
        s2 = min(out_w / W, out_h / H) * zoom
        fw, fh2 = int(W * s2), int(H * s2)
        fg = cv2.resize(frame, (fw, fh2), interpolation=cv2.INTER_AREA if s2 < 1 else cv2.INTER_CUBIC)
        if fw > out_w:  # zoomed past the width: crop the center
            off = (fw - out_w) // 2
            fg, fw = fg[:, off:off + out_w], out_w
        x, y = (out_w - fw) // 2, (out_h - fh2) // 2
        bg[y:y + fh2, x:x + fw] = fg
        return bg
    ch = H / zoom
    cw = ch * out_w / out_h
    if cw > W:  # source narrower than 9:16 (already vertical): use full width
        cw = W / zoom
        ch = cw * out_h / out_w
    # put the face a little above the middle of the frame
    x0 = min(max(cx * W - cw / 2, 0), W - cw)
    y0 = min(max(cy * H - ch * 0.40, 0), H - ch)
    x0, y0 = int(round(x0)), int(round(y0))
    crop = frame[y0:y0 + int(round(ch)), x0:x0 + int(round(cw))]
    interp = cv2.INTER_CUBIC if crop.shape[0] < out_h else cv2.INTER_AREA
    return cv2.resize(crop, (out_w, out_h), interpolation=interp)
