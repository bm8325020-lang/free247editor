# Architecture

free247editor is a thin layer around an external editing engine. Most of the "program" is
the skill: instructions the agent follows.

```
user ──"edit this"──▶ agent ──reads──▶ .agents/skills/free247editor/SKILL.md
                        │
                        ├─ node setup.mjs ──clones (pinned)──▶ kit/  (Herk Edit Kit, HyperFrames)
                        ├─ node tools/transcribe.mjs ──▶ Groq API  or  tools/transcribe_local.py
                        │        └─ tools/transcript.mjs  (pure: shape, clean, sort words)
                        └─ kit/ scripts + HyperFrames render ──▶ outbox/<slug>-vN.mp4
```

## Pieces

| Path | Role |
| --- | --- |
| `.agents/skills/free247editor/SKILL.md` | The workflow: setup, style, editing, notes to rules, limits. Shared by all agents. |
| `.claude/skills/free247editor/SKILL.md` | Pointer for Claude Code, which reads only `.claude/skills/`. Same frontmatter. |
| `AGENTS.md` / `CLAUDE.md` | Tell every agent to use the skill. `CLAUDE.md` imports `AGENTS.md`. |
| `setup.mjs` | Tool check, sparse clone of `kit/` at `KIT_COMMIT`, `npm ci`, test render, transcription status. |
| `tools/transcribe.mjs` | Extracts 16 kHz mono audio with FFmpeg, calls Groq or the local helper, writes `<video>.json`. |
| `tools/transcript.mjs` | Pure functions turning provider output into the kit's `{ text, audio_duration_secs, words }`. |
| `tools/transcribe_local.py` | faster-whisper with word timestamps; GPU if CUDA works, else CPU. |
| `scripts/check.mjs`, `tests/` | Fast checks; see `docs/quality.md`. |

## Decisions

- **Engine is pinned, not vendored.** `KIT_COMMIT` in `setup.mjs` fixes the version; the kit's
  showcase media is skipped with sparse checkout. Upgrade by changing the commit deliberately.
- **No npm dependencies at the root.** Scripts use Node's standard library only, so `npm run
  check` works right after cloning.
- **Transcription replaces the kit's ElevenLabs step** to stay free. Output matches the kit's
  expected JSON so its cutting tools work unchanged. Words are sorted by start time because
  Groq can return one out of order.
- **Private data is ignored by Git** (`.gitignore`), not just by convention.
