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
