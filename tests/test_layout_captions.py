"""Facecam detection, vertical layout geometry, caption timing and pause cutting."""
import numpy as np
from conftest import FIXTURES

from clipper.captions import Timeline, build_ass, clean
from clipper.config import load_config
from clipper.layout import cam_box, facecam_from, panel_height, split_compose
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


def test_split_frame_has_the_right_size_and_keeps_the_webcam_out_of_the_gameplay():
    cfg = dict(load_config(), _panel_h=864)
    frame = np.zeros((720, 1280, 3), np.uint8)
    frame[440:696, 24:344] = 255  # the webcam, white
    out = split_compose(frame, cam_box(facecam(), INFO, 1080, 864), cfg)
    assert out.shape == (1920, 1080, 3)
    assert out[880:, :].max() == 0  # no webcam pixels in the gameplay panel


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
