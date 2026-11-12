"""Word-level transcription: Groq (free key) when GROQ_API_KEY is set, else faster-whisper on this computer.

transcribe() does a whole file (talk mode). transcribe_ranges() does only the given windows (gaming mode), which
is what keeps a 4-hour stream fast: only the candidate moments are ever transcribed.
"""
import json
import os
import sys
import tempfile
import urllib.request
import uuid
from pathlib import Path

import numpy as np

from .media import run

_MODEL = {}
LANG_CODES = {"portuguese": "pt", "english": "en", "spanish": "es", "tagalog": "tl", "french": "fr"}


def _pcm16k(path, start=None, dur=None):
    cmd = ["ffmpeg", "-v", "error"]
    if start is not None:
        cmd += ["-ss", f"{start:.3f}", "-t", f"{dur:.3f}"]
    cmd += ["-i", str(path), "-vn", "-ac", "1", "-ar", "16000", "-f", "f32le", "-"]
    return np.frombuffer(run(cmd), dtype=np.float32).copy()


def _model(name):
    if name not in _MODEL:
        from faster_whisper import WhisperModel
        try:
            import ctranslate2
            cuda = ctranslate2.get_cuda_device_count() > 0
        except Exception:
            cuda = False
        _MODEL[name] = WhisperModel(name, device="cuda" if cuda else "cpu",
                                    compute_type="int8_float16" if cuda else "int8")
    return _MODEL[name]


def _local(audio, cfg, language, progress=None):
    segments, info = _model(cfg["whisper_model"]).transcribe(
        audio, language=language, word_timestamps=True, vad_filter=True, condition_on_previous_text=False)
    words, total = [], max(1e-6, len(audio) / 16000)
    for s in segments:
        for w in s.words or []:
            words.append({"text": w.word.strip(), "start": round(w.start, 3), "end": round(w.end, 3)})
        if progress:
            progress(min(1.0, s.end / total))
    return words, info.language


def _groq(path, start, dur, language):
    key = os.environ["GROQ_API_KEY"]
    with tempfile.TemporaryDirectory() as tmp:
        mp3 = Path(tmp) / "c.mp3"
        run(["ffmpeg", "-y", "-v", "error", "-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", str(path), "-vn",
             "-ac", "1", "-ar", "16000", "-b:a", "48k", str(mp3)])
        boundary = uuid.uuid4().hex
        fields = [("model", "whisper-large-v3"), ("response_format", "verbose_json"),
                  ("timestamp_granularities[]", "word")]
        if language:
            fields.append(("language", language))
        body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                        for k, v in fields)
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.mp3\"\r\n"
                 f"Content-Type: audio/mpeg\r\n\r\n").encode() + mp3.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request("https://api.groq.com/openai/v1/audio/transcriptions", data=body, method="POST",
                                 headers={"Authorization": f"Bearer {key}",
                                          "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=600) as r:
        res = json.loads(r.read())
    words = [{"text": w["word"].strip(), "start": round(w["start"], 3), "end": round(w["end"], 3)}
             for w in res.get("words") or []]
    lang = res.get("language")
    return words, LANG_CODES.get(str(lang).lower(), lang)


def _segment(path, start, dur, cfg, language, progress=None):
    """Transcribe path[start:start+dur]; word times are relative to `start`."""
    use_groq = cfg["transcriber"] == "groq" or (cfg["transcriber"] == "auto" and os.environ.get("GROQ_API_KEY"))
    if use_groq and not cfg.get("_groq_failed"):
        try:
            return _groq(path, start, dur, language)
        except Exception as e:  # network, quota, bad key: finish the job locally
            cfg["_groq_failed"] = True
            print(f"   Groq failed ({str(e)[:120]}); transcribing on this computer instead.", file=sys.stderr)
    return _local(_pcm16k(path, start, dur), cfg, language, progress)


def _bar(prefix):
    def f(x):
        print(f"\r   {prefix} {x * 100:5.1f}%", end="", file=sys.stderr, flush=True)
    return f


def transcribe(path, cfg, cache):
    """Whole file, in 10-minute pieces. Cached in `cache`."""
    cache = Path(cache)
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    dur = float(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)]))
    words, lang, start, chunk = [], cfg.get("language"), 0.0, 600.0
    while start < dur:
        part, lang2 = _segment(path, start, min(chunk, dur - start), cfg, lang,
                               lambda x, s=start: _bar("transcribing")((s + x * chunk) / dur))
        lang = lang or lang2
        words += [{**w, "start": round(w["start"] + start, 3), "end": round(w["end"] + start, 3)} for w in part]
        start += chunk
    print(file=sys.stderr)
    words = [w for w in words if w["text"] and w["end"] >= w["start"]]
    if not words:
        raise SystemExit("No speech found in this video.")
    data = {"language": lang, "words": words}
    cache.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def transcribe_ranges(path, ranges, cfg, cache):
    """Only the given (start, end) windows. Returns sorted words in absolute time. Cached per window."""
    cache = Path(cache)
    store = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else {}
    lang = cfg.get("language") or store.get("_language")
    words = []
    for i, (a, b) in enumerate(ranges):
        key = f"{a:.2f}-{b:.2f}"
        if key not in store:
            _bar("transcribing moments")(i / len(ranges))
            part, lang2 = _segment(path, a, b - a, cfg, lang)
            lang = lang or lang2
            store[key] = [{**w, "start": round(w["start"] + a, 3), "end": round(w["end"] + a, 3)} for w in part]
            store["_language"] = lang
            cache.write_text(json.dumps(store, ensure_ascii=False), encoding="utf-8")
        words += store[key]
    _bar("transcribing moments")(1.0)
    print(file=sys.stderr)
    seen, out = set(), []
    for w in sorted(words, key=lambda w: w["start"]):
        k = (round(w["start"], 1), w["text"])
        if w["text"] and w["end"] >= w["start"] and k not in seen:
            seen.add(k)
            out.append(w)
    return {"language": lang, "words": out}
