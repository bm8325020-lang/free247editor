"""Live bot: chat scoring, spike detection without false alarms, and the authorization gate."""
import json

import numpy as np

from clipper import live


def test_reaction_messages_weigh_more_than_plain_ones():
    assert live.message_weight("boa noite chat") == 1.0
    assert live.message_weight("KEKW KEKW") > 3
    assert live.message_weight("CLIPA ISSO kkkkkk") > 4
    assert live.message_weight("????? " * 20) <= 6.0  # capped: one spammer cannot fake a moment


def test_slow_chat_noise_never_looks_like_a_spike():
    rng = np.random.default_rng(1)
    worst = 0.0
    for _ in range(300):
        series = rng.poisson(1.5, 300).astype(float)  # ~1.5 messages per second, pure noise
        worst = max(worst, live.robust_z(series, 240))
    assert worst < 3.0


def test_a_real_burst_stands_out_even_in_a_busy_chat():
    rng = np.random.default_rng(2)
    series = rng.poisson(20, 300).astype(float)
    series[-3:] = 80  # the chat explodes for 3 seconds
    assert live.robust_z(series, 240) > 6


def test_too_little_history_never_fires():
    assert live.robust_z([0, 0, 50, 50, 50], 240) == 0.0


def test_channels_without_authorization_are_refused(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(live, "ROOT", tmp_path)
    (tmp_path / "live-canais.json").write_text(json.dumps({"canais": [
        {"plataforma": "twitch", "canal": "sem_permissao"},
        {"plataforma": "twitch", "canal": "com_permissao", "autorizacao": "campanha X"},
        {"plataforma": "kick", "canal": "outro", "autorizacao": "y"},
        {"plataforma": "youtube", "canal": "@desligado", "autorizacao": "z", "ativo": False},
    ], "limiar": 2.5}), encoding="utf-8")
    chans, opts = live.load_channels()
    assert [c["canal"] for c in chans] == ["com_permissao"]
    assert opts["limiar"] == 2.5 and opts["antes"] == 20
    assert "sem 'autorizacao'" in capsys.readouterr().err


def test_onset_finds_when_the_chat_started_reacting_not_when_it_was_detected():
    rng = np.random.default_rng(3)
    series = list(rng.poisson(2, 280).astype(float))
    # the chat starts reacting 7 seconds ago, ramps up, and is detected only now
    series += [6, 9, 14, 20, 30, 40, 45, 50]
    # never later than the real start (that would cut the scene); a noisy second before it may add a little
    assert 7 <= live.chat_onset(series) <= 9


def test_onset_ignores_a_single_quiet_second_inside_the_burst():
    series = [2.0] * 280 + [20, 25, 2, 30, 40, 50]  # one dip in the middle
    assert live.chat_onset(series) == 5


def test_no_burst_means_no_extra_going_back():
    assert live.chat_onset([2.0] * 300) == 0


def test_default_goes_back_three_seconds_and_is_configurable():
    assert live.DEFAULTS["volta_segundos"] == 3
