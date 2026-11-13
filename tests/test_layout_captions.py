"""Facecam detection, vertical layout geometry, caption timing and pause cutting."""
import cv2
import numpy as np
import pytest
from conftest import FIXTURES

from clipper.captions import Timeline, build_ass, clean
from clipper.config import load_config
from clipper.layout import cam_box, facecam_from, panel_height, pip_compose, pip_layout, split_compose, webcam_area
from clipper.render import keep_ranges, quantize

INFO = {"width": 1280, "height": 720, "duration": 276.0}


def facecam():
    faces = []
    for line in (FIXTURES / "faces.txt").read_text().splitlines():
        if line.strip():
            faces += [tuple(float(v) for v in f.split(",")) for f in line.split(";")]
    w, h = (int(x) for x in (FIXTURES / "edgefreq.dim").read_text().split())
    freq = np.fromfile(FIXTURES / "edgefreq.f32", dtype=np.float32).reshape(h, w)
    return facecam_from(faces, 40, freq)


def test_facecam_overlay_border_matches_reference():
    cam = facecam()
    exp = (FIXTURES / "expected_cam.txt").read_text().splitlines()
    np.testing.assert_allclose(cam["rect"], [float(v) for v in exp[0].split()], atol=2e-3)
    cfg = load_config()
    ph = panel_height(cam, INFO, cfg)
    assert ph == int(exp[1])
    np.testing.assert_allclose(cam_box(cam, INFO, 1080, ph), [float(v) for v in exp[2].split()], atol=2e-3)


def synthetic_frame():
    """Gameplay painted green, the webcam (where the fixture's webcam really is) painted white."""
    frame = np.zeros((720, 1280, 3), np.uint8)
    frame[:, :] = (0, 255, 0)
    frame[440:696, 24:344] = (255, 255, 255)
    return frame


def test_webcam_panel_is_small_and_shows_no_gameplay_and_gameplay_shows_no_webcam():
    cam = facecam()
    cfg = load_config()
    ph = panel_height(cam, INFO, cfg)
    assert ph == round(1920 * 0.28 / 2) * 2  # 28% of the height
    box = cam_box(cam, INFO, 1080, ph)
    out = split_compose(synthetic_frame(), box, dict(cfg, _panel_h=ph), webcam_area(cam, box))
    assert out.shape == (1920, 1080, 3)
    top, bottom = out[: ph - 4], out[ph + 4:]
    green = (top[:, :, 1] > 200) & (top[:, :, 0] < 60)
    white = (bottom[:, :, 0] > 200) & (bottom[:, :, 2] > 200)
    assert green.sum() == 0, "gameplay leaked into the webcam panel"
    assert white.sum() == 0, "webcam leaked into the gameplay panel"


def test_webcam_crop_is_tight_on_the_face():
    cam = facecam()
    box = cam_box(cam, INFO, 1080, 538)
    face_h = cam["fh"]
    assert face_h * 0.8 <= box[3] <= face_h * 1.6  # the face, not the whole webcam
    face_cx, face_cy = (cam["cx0"] + cam["cx1"]) / 2, (cam["cy0"] + cam["cy1"]) / 2
    assert box[0] <= face_cx <= box[0] + box[2] and box[1] <= face_cy <= box[1] + box[3]


def test_no_webcam_and_big_face_are_told_apart():
    assert facecam_from([], 40, None) is None
    big = [(0.5, 0.45, 0.3, 0.5)] * 30
    assert facecam_from(big, 40, None) == "big"


def test_timeline_maps_source_time_across_cuts():
    tl = Timeline([(10.0, 12.0), (15.0, 16.0)])
    assert tl.out(10.5) == 0.5 and tl.out(13.0) == 2.0 and tl.out(15.5) == 2.5 and tl.duration == 3.0


def test_captions_highlight_each_word_in_order(tmp_path):
    words = [{"text": "Não", "start": 0.0, "end": 0.3}, {"text": "acredito!", "start": 0.35, "end": 0.9},
             {"text": "olha", "start": 1.0, "end": 1.2}]
    cfg = load_config()
    build_ass(words, Timeline([(0.0, 2.0)]), "NÃO ACREDITO", cfg, tmp_path / "s.ass")
    lines = [l for l in (tmp_path / "s.ass").read_text(encoding="utf-8").splitlines() if l.startswith("Dialogue")]
    assert "Hook" in lines[0]
    caps = lines[1:]
    starts = [c.split(",")[1] for c in caps]
    assert starts == sorted(starts) and len(caps) == 3
    assert clean("“acredito!”", True) == "ACREDITO!"


def test_talk_mode_cuts_long_pauses_and_gaming_keeps_everything():
    words = [{"text": "a", "start": 1.0, "end": 1.5}, {"text": "b", "start": 3.0, "end": 3.4}]
    clip = {"start": 1.0, "end": 3.4}
    talk = dict(load_config({"mode": "talk"}), max_pause=0.45, pause_pad=0.1)
    assert len(keep_ranges(words, clip, talk)) == 2
    assert keep_ranges(words, clip, load_config()) == [(1.0, 3.4)]
    q = quantize([(1.0, 1.6), (2.9, 3.75)], 1.0, 30)
    assert all(abs((b - a) * 30 - round((b - a) * 30)) < 1e-6 for a, b in q)


# ---------- picture-in-picture webcam (whole webcam, its own shape, small in the corner) ----------

def round_facecam():
    faces = []
    lines = (FIXTURES / "round_faces.txt").read_text().splitlines()
    for line in lines:
        if line.strip():
            faces += [tuple(float(v) for v in f.split(",")) for f in line.split(";")]
    w, h = (int(x) for x in (FIXTURES / "round_edgefreq.dim").read_text().split())
    freq = np.fromfile(FIXTURES / "round_edgefreq.f32", dtype=np.float32).reshape(h, w)
    return facecam_from(faces, len(lines), freq)


def test_round_and_square_webcams_are_told_apart():
    rnd = round_facecam()
    assert rnd["shape"] == "round"
    # the test video's round webcam: centre (170, 550), radius 139 px in 1280x720
    cx, cy, rx, ry = rnd["circle"]
    assert abs(cx * 1280 - 170) < 5 and abs(cy * 720 - 550) < 5 and abs(rx * 1280 - 139) < 5
    np.testing.assert_allclose(rnd["circle"], [float(v) for v in (FIXTURES / "expected_round.txt").read_text().split()],
                               atol=2e-3)
    assert facecam()["shape"] == "rect"


def _pip_frame(round_cam):
    frame = np.zeros((720, 1280, 3), np.uint8)
    frame[:, :] = (0, 255, 0)  # gameplay: green
    if round_cam:
        cv2.circle(frame, (170, 550), 139, (255, 255, 255), -1)  # webcam: white
    else:
        frame[440:696, 24:344] = (255, 255, 255)
    return frame


@pytest.mark.parametrize("round_cam", [True, False])
def test_pip_shows_the_whole_webcam_small_in_the_corner_with_nothing_leaking(round_cam):
    cam = round_facecam() if round_cam else facecam()
    cfg = load_config()
    L = pip_layout(cam, INFO, cfg)
    x, y, pw, ph = L["dst"]
    assert x < 100 and y < 200 and pw == round(1080 * cfg["pip_width"] / 2) * 2  # small, top-left
    # the face is never cut: everywhere the face goes (inside the webcam) is inside the crop
    sx, sy, sw, sh = L["src"]
    wx, wy, ww, wh = cam["rect"]
    fx0, fy0 = max(cam["x0"], wx), max(cam["y0"], wy)
    fx1, fy1 = min(cam["x1"], wx + ww), min(cam["y1"], wy + wh)
    tol = 0.02 * max(ww, wh)  # the crop sits 1.5-3% inside the webcam edge to keep its frame line out
    assert sx <= fx0 + tol and sy <= fy0 + tol and sx + sw >= fx1 - tol and sy + sh >= fy1 - tol
    out = pip_compose(_pip_frame(round_cam), L, cfg)
    assert out.shape == (1920, 1080, 3) and L["round"] == round_cam
    inner = out[y + 8: y + ph - 8, x + 8: x + pw - 8]
    if round_cam:  # only look inside the circle
        yy, xx = np.mgrid[0:inner.shape[0], 0:inner.shape[1]]
        keep = np.hypot(xx - inner.shape[1] / 2, yy - inner.shape[0] / 2) < min(inner.shape[:2]) / 2 - 4
        inner = inner[keep]
    else:
        inner = inner.reshape(-1, 3)
    assert ((inner[:, 1] > 200) & (inner[:, 0] < 60)).sum() == 0, "gameplay inside the webcam"
    rest = out.copy()
    rest[: y + ph + 8, : x + pw + 8] = 0
    assert ((rest[:, :, 0] > 200) & (rest[:, :, 2] > 200)).sum() == 0, "webcam inside the gameplay"
