# Quality

## Checks

| Command | What it proves | Needs |
| --- | --- | --- |
| `npm run check` | All scripts parse (`node --check`, Python `ast`), the skill has `name` and `description`, the `.claude` copy matches the shared skill, unit tests pass | Node 22+; Python optional (skipped if missing) |
| `npm test` | Unit tests in `tests/` for `tools/transcript.mjs` | Node 22+ |
| `node setup.mjs` | Real tools, kit install, and a HyperFrames render work on this computer | Network, Chrome, ~1 GB |
| `node tools/transcribe.mjs <video>` | Real transcription with the configured provider | Groq key or faster-whisper |

CI (`.github/workflows/check.yml`) runs `npm run check` on every push and pull request. It
does not run setup, rendering, or transcription: those need network, a browser, and a key.

## Rules

- Keep pure logic (data shaping) in small modules with unit tests; keep scripts that call
  FFmpeg, Groq, or Git thin.
- A bug fix gets a test that fails without it when the logic is testable offline.
- Never weaken a check to make it pass. Do not commit secrets or the user's media.

## Security review

For a review of the repo or a change, use the toolkit's security review prompt:
`docs/prompts/13-security-review.md` in
https://github.com/soumatheusgomes/vibe-coding-toolkit (revision recorded in
`docs/setup.md`). Paste its Prompt block into the agent with the range to review. Keep the
report private. Areas that matter here: the Groq key in `.env`, files sent to Groq, commands
run with user-supplied file paths, and the pinned external `kit/`.
