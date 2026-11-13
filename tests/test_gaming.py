"""Gaming-mode engine: excitement, peaks, windows, honesty about how many clips come out."""
import numpy as np
import pytest
from conftest import FIXTURES

from clipper import gaming
from clipper.config import load_config
from clipper.pipeline import explain

DUR = 276.0


def words():
    out = []
    for line in (FIXTURES / "words.tsv").read_text(encoding="utf-8").splitlines():
        if line.strip():
            a, b, t = line.split("\t")
            out.append({"text": t, "start": float(a), "end": float(b)})
    return out


def loud():
    return np.loadtxt(FIXTURES / "loud.txt").astype(np.float32)


def run(cfg):
    w = words()
    return gaming.find_gaming_highlights(None, {"duration": DUR}, loud(), 0.1, cfg,
                                         lambda ranges: {"words": w, "language": "en"})


@pytest.fixture
def cfg():
    c = load_config({"picker": "heuristic"})
    return c


def test_excitement_and_peaks_match_the_shared_fixtures(cfg):
    ex = gaming.audio_excitement(loud(), 0.1)
    np.testing.assert_allclose(ex, np.loadtxt(FIXTURES / "ex.txt"), atol=1e-3)
    peaks = gaming.candidate_peaks(ex, cfg, max(12, cfg["clips"] * 3))
    assert peaks == [int(x) for x in (FIXTURES / "peaks.txt").read_text().split()]


def test_snap_hype_and_hooks_match_the_shared_fixtures(cfg):
    w = words()
    for line in (FIXTURES / "expected_windows.tsv").read_text(encoding="utf-8").splitlines():
        p = line.split("\t")
        peak = int(p[0]) + 0.5
        a, b = gaming.window_for(peak, DUR, cfg)
        a2, b2 = gaming.snap(w, a, b, DUR, cfg)
        assert (round(a2, 3), round(b2, 3)) == (round(float(p[3]), 3), round(float(p[4]), 3))
        h, burst, n = gaming.hype_score(w, a2, b2, peak)
        assert h == pytest.approx(float(p[5]), abs=1e-3) and n == int(p[7])
        assert gaming.hook_from(w, peak) == (p[8] if len(p) > 8 else "")


def test_loudest_planted_scream_is_the_top_clip(cfg):
    cfg["clips"] = 3
    picked, _, _, _ = run(cfg)
    assert picked[0]["peak"] == 233.5


@pytest.mark.parametrize("max_s", [20, 35, 50])
def test_clips_respect_max_length_never_overlap_and_contain_their_peak(cfg, max_s):
    cfg.update(clips=8, max_seconds=max_s, target_seconds=min(28, max_s))
    picked, _, _, _ = run(cfg)
    assert picked
    for c in picked:
        assert c["end"] - c["start"] <= max_s + 1e-9
        assert c["start"] <= c["peak"] <= c["end"]
    spans = sorted((c["start"], c["end"]) for c in picked)
    assert all(a2 >= b1 for (_, b1), (a2, _) in zip(spans, spans[1:]))


def test_weak_moments_are_not_used_to_fill_the_count(cfg):
    cfg["clips"] = 15
    picked, _, _, summary = run(cfg)
    assert len(picked) <= summary["strong"] < 15
    assert all(gaming.is_strong(c, cfg) for c in picked)
    # --all keeps weak moments too
    cfg2 = dict(cfg, min_audio_spike=0, min_hype=0)
    picked2, _, _, _ = run(cfg2)
    assert len(picked2) >= len(picked)


def test_explanation_states_requested_vs_rendered_and_why():
    s = {"requested": 8, "strong": 5, "picked": 5, "picker": "heuristic", "rendered": 4,
         "failed": ["clipe 3 (X): encoder"]}
    msg = explain(s)
    assert "até 8" in msg and "Saíram 4" in msg and "só 5 momentos fortes" in msg and "1 falharam" in msg
    assert explain({"requested": 3, "picked": 3, "picker": "x", "rendered": 3, "failed": []}) == \
        "Pedido: até 3 clipes. Saíram 3."


def test_cap_length_keeps_the_peak_with_more_build_up_than_aftermath():
    a, b = gaming.cap_length(10, 45, 40, 20)
    assert (a, b) == (25, 45)
    assert gaming.cap_length(0, 15, 5, 20) == (0, 15)
