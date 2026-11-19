import json
import socket
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import clipper.clipserver as cs  # noqa: E402


def _get(port, path, code):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"X-Code": code})
    return urllib.request.urlopen(req, timeout=5)


def test_lists_and_serves_clips_only_with_the_code(tmp_path, monkeypatch):
    monkeypatch.setattr(cs, "ROOT", tmp_path)
    monkeypatch.setattr(cs, "MANIFEST", tmp_path / "outbox" / "live" / "clips.json")
    clip = tmp_path / "outbox" / "live" / "x" / "c.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"VIDEO" * 1000)
    cid = cs.add_clip(clip, "paulinho", "Que tiro", "Que tiro\nCréditos: @paulinho")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]
    srv = cs.serve("segredo123", port, status=lambda: ["ok"])
    try:
        try:
            _get(port, "/api/clips", "errado")
            raise AssertionError("aceitou código errado")
        except urllib.error.HTTPError as e:
            assert e.code == 401
        clips = json.loads(_get(port, "/api/clips", "segredo123").read())
        assert clips[0]["id"] == cid and clips[0]["title"] == "Que tiro" and "path" not in clips[0]
        assert _get(port, f"/api/clip/{cid}", "segredo123").read() == b"VIDEO" * 1000
        assert json.loads(_get(port, "/api/status", "segredo123").read())["clips"] == 1
        for bad in ("/api/clip/..%2f..%2fetc", "/api/clip/nao-existe", "/etc/passwd"):
            try:
                _get(port, bad, "segredo123")
                raise AssertionError(bad)
            except urllib.error.HTTPError as e:
                assert e.code == 404
    finally:
        srv.shutdown()


def test_refuses_to_start_without_a_real_code():
    assert cs.serve("", 0) is None and cs.serve("curto", 0) is None
