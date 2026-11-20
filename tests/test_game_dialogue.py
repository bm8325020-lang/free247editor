import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from clipper.transcribe import drop_game_dialogue  # noqa: E402

W = [{"text": t, "start": s, "end": s + 0.3} for s, t in
     [(0.5, "Hey"), (1, "everyone"), (3.5, "mano"), (4, "olha"), (6.5, "Okay"), (8.5, "não")]]


def test_brazilian_streamer_keeps_only_his_speech_not_the_game_characters():
    segs = [{"start": 0, "end": 3, "text": "Hey everyone, what's up? I need you to get in the car."},
            {"start": 3, "end": 6, "text": "mano olha isso kkkk o cara tá louco"},
            {"start": 6, "end": 8, "text": "Okay, let's go!"},
            {"start": 8, "end": 10, "text": "não acredito, ele bateu o carro"}]
    assert [w["text"] for w in drop_game_dialogue(W, segs)] == ["mano", "olha", "não"]


def test_english_speaking_streamer_keeps_every_caption():
    segs = [{"start": 0, "end": 10, "text": "Hey everyone, what's up? I can't believe this, let's go"}]
    assert drop_game_dialogue(W, segs) == W
