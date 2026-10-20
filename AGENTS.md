# free247editor

This folder is the user's personal video editor. They record; you edit.

Everything you do here follows the `free247editor` skill in
`.agents/skills/free247editor/SKILL.md`. If your agent has not loaded it yet, read it before
you act on "start", "edit this", a dropped or new video, notes on a draft, or anything about
the user's style or rules.

## Working on the editor itself

Only when the user asks to change the editor's code or docs (not when editing a video):

- Stack: Node.js 22+ scripts (ES modules, no dependencies), one Python helper, FFmpeg. The
  editing engine in `kit/` is a pinned external repo installed by `setup.mjs`; never edit it.
- Check: `npm run check` (syntax, skill files, unit tests). Tests only: `npm test`. No install
  or network needed. CI runs the same check.
- The skill lives in `.agents/skills/free247editor/SKILL.md`. If you change its description,
  copy it into `.claude/skills/free247editor/SKILL.md`; the check fails until they match.
- Never commit `inbox/`, `outbox/`, `my-style.md`, `my-rules.md`, `my-sounds/`, or `.env`.
- Map: `PRODUCT.md` (scope), `docs/architecture.md` (how it fits together),
  `docs/onboarding.md` (developer setup), `docs/quality.md` (checks and review),
  `docs/setup.md` (toolkit adoption record). Update the one that owns a change.
