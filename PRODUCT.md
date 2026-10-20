# Product

## Problem

People who record short videos (Reels, TikTok, Shorts, YouTube) on a phone want them edited
like a human editor would, without learning an editing app or paying for one.

## Audience

Creators who are not editors or programmers, writing in English, Tagalog, Taglish, or other
languages, using an AI coding agent (Claude Code, Codex, Cursor, and others).

## What it does today

- One-time setup (`setup.mjs`): checks Node.js 22+, Git, FFmpeg; installs the pinned Herk Edit
  Kit into `kit/`; renders a test video; reports transcription status.
- Free word-level transcription (`tools/transcribe.mjs`): Groq whisper-large-v3 with a free
  key, or faster-whisper on the user's computer with `--local`.
- The editing workflow itself is instructions in the skill
  (`.agents/skills/free247editor/SKILL.md`): style setup, cuts, captions, hook, motion
  graphics, sound, drafts to `outbox/`, notes saved as rules in `my-rules.md`.

## Constraints

- Free by default. Paid services (ElevenLabs, Kie.ai, others) only when the user asks.
- The user's footage, renders, style, rules, sounds, and keys stay local and are never
  committed, uploaded, or posted.
- Groq's free tier accepts files up to 25 MB, about 50 minutes of the compressed audio that
  `transcribe.mjs` sends. Longer recordings would need chunking (not built).
- Tested on Windows 11 with Claude Code. macOS and Linux are expected to work but are less
  tested.

## Acceptance

- `node setup.mjs` ends with "SETUP COMPLETE" and `outbox/setup-test.mp4` plays.
- `npm run check` passes.
- A video in `inbox/` plus "edit this" produces `outbox/<slug>-v1.mp4`.
