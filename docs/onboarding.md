# Onboarding (working on the editor)

For using the editor, see `README.md`. This page is for changing its code or docs.

1. Install Node.js 22+, Git, FFmpeg, and optionally Python 3 (for the local transcription
   helper and its syntax check).
2. Clone the repo and run `npm run check`. No `npm install` is needed at the root.
3. To work on anything that touches the engine, run `node setup.mjs` once; it installs `kit/`
   and renders `outbox/setup-test.mp4`.
4. To try transcription, copy `.env.example` to `.env` and add a free Groq key, or install
   `faster-whisper` and pass `--local`. Never commit `.env`.
5. Before committing, run `npm run check`. Read `AGENTS.md` for the rules agents follow here.

## Claude Code cloud sessions

Cloud sessions start from a fresh container. Paste `scripts/cloud-session-setup.sh` into the
cloud environment's setup script (environment menu > Edit) so every new session clones the
Vibe Coding Toolkit, Claude Code Game Studios, and OmniRoute, installs OmniRoute, and runs
`node setup.mjs` here. Keep the Groq key in the environment's secrets as `GROQ_API_KEY`.
