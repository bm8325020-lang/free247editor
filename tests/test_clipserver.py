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
    assert cs.serve("TROQUE_AQUI", 0) is None and cs.serve("invente_um_codigo_secreto", 0) is None


def test_keys_only_on_the_same_phone(tmp_path, monkeypatch):
    import json, urllib.request, urllib.error
    from tools.clipper import clipserver
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setenv("COARENA_API_KEY", "coa_test")
    for host, expect in (("127.0.0.1", 200), ("0.0.0.0", 403)):
        srv = clipserver.serve("codigo-secreto-1", 0, host=host)
        port = srv.server_address[1]
        req = urllib.request.Request(f"http://127.0.0.1:{port}/api/keys", headers={"X-Code": "codigo-secreto-1"})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                assert expect == 200 and json.loads(r.read()) == {"groq": "gsk_test", "coarena": "coa_test"}
        except urllib.error.HTTPError as e:
            assert e.code == expect
        bad = urllib.request.Request(f"http://127.0.0.1:{port}/api/keys", headers={"X-Code": "errado"})
        try:
            urllib.request.urlopen(bad, timeout=5)
            assert False
        except urllib.error.HTTPError as e:
            assert e.code == 401
        srv.shutdown()


def test_raw_cut_is_served_once_then_freed(tmp_path, monkeypatch):
    import json, urllib.request
    from tools.clipper import clipserver
    monkeypatch.setattr(clipserver, "ROOT", tmp_path)
    monkeypatch.setattr(clipserver, "MANIFEST", tmp_path / "outbox" / "live" / "clips.json")
    d = tmp_path / "outbox" / "live" / "kick-x" / "2026-11-20"
    d.mkdir(parents=True)
    (d / "a.mp4").write_bytes(b"clip")
    (d / "a-bruto.mp4").write_bytes(b"raw-video")
    cid = clipserver.add_clip(d / "a.mp4", "x", "t", "c", 80, "ok", raw=d / "a-bruto.mp4", raw_start=30, raw_end=95)
    srv = clipserver.serve("codigo-secreto-1", 0, host="127.0.0.1")
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    h = {"X-Code": "codigo-secreto-1"}
    lst = json.loads(urllib.request.urlopen(urllib.request.Request(base + "/api/clips", headers=h)).read())
    assert lst[0]["raw"] is True and lst[0]["raw_start"] == 30 and lst[0]["raw_end"] == 95
    assert urllib.request.urlopen(urllib.request.Request(f"{base}/api/raw/{cid}", headers=h)).read() == b"raw-video"
    assert not (d / "a-bruto.mp4").exists()                    # freed after sending
    lst = json.loads(urllib.request.urlopen(urllib.request.Request(base + "/api/clips", headers=h)).read())
    assert "raw" not in lst[0]                                  # no longer offered
    srv.shutdown()
