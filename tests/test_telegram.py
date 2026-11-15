import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from clipper.telegram import _multipart, _safe, post_text  # noqa: E402


def test_post_text_matches_phone_app():
    t = post_text("ELE NÃO ACREDITOU", "paulinholoko", "#GTA6, cortes  #cortes")
    assert t == "ELE NÃO ACREDITOU\nCréditos: @paulinholoko\n\n#gta6 #cortes"


def test_post_text_without_credit_or_tags():
    assert post_text("Título", "", "") == "Título"


def test_token_is_hidden_in_errors():
    assert "123:abc" not in _safe(Exception("https://api.telegram.org/bot123:abc/sendVideo failed"), "123:abc")


def test_multipart_carries_caption_and_file(tmp_path):
    f = tmp_path / "c.mp4"
    f.write_bytes(b"VIDEO")
    body, ctype = _multipart({"caption": "oi"}, {"video": f})
    assert ctype.startswith("multipart/form-data; boundary=")
    assert b'name="caption"\r\n\r\noi' in body and b"VIDEO" in body and b'filename="c.mp4"' in body
