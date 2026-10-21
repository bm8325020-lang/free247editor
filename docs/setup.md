# Setup and evidence

Record of adopting the Vibe Coding Toolkit in this repo.

## Source and context

- Date: 2026-10-20.
- Toolkit: https://github.com/soumatheusgomes/vibe-coding-toolkit at `790a895304e0c08b70a5b215c9d334f1c86b816c`, prompt
  `docs/prompts/12-existing-project-setup.md`.
- Client: Claude Code in a temporary cloud session (Linux, Node 22.22.0, Python 3.13).
  Anything installed globally there is lost when the session ends, so only repo files were
  changed.
- Subagents: none used; the project is small enough to do directly.

## Baseline (before)

No `package.json`, no tests, no lint, no CI. `node setup.mjs` completed and rendered the test
video. A real Groq transcription returned one word out of order ("Bom." at 25.24 s after
"isso." at 25.48 s); `transcribe.mjs` wrote it unsorted.

## Components

| Component | Action and reason | Scope | State and evidence |
| --- | --- | --- | --- |
| Shared instructions (`AGENTS.md`, `CLAUDE.md`) | Updated: kept the skill pointer first, added commands, limits, and a doc map for code work | Project | Done |
| Project docs (manifest) | Added `PRODUCT.md`, `docs/architecture.md`, `docs/onboarding.md`, `docs/quality.md`, this file. Kept `README.md` as the user guide | Project | Done |
| `docs/design.md` | Not created: no UI. Video style is per user in `my-style.md` (private) and the skill | n/a | Not applicable |
| Quality gate | Added `npm run check` (`scripts/check.mjs`) and `node:test` unit tests; no new dependencies | Project | Passes; drift and regression checks shown to fail when broken |
| ESLint/Biome | Not added: about 200 lines of dependency-free scripts; syntax check plus tests cover the current risk | n/a | Revisit if the code grows |
| CI | Added `.github/workflows/check.yml` running `npm run check` | Project | Configured; first run happens on push |
| Superpowers, Ponytail, Caveman | Installed with `claude plugin install --scope project` (2026-10-21): superpowers 7.0.0 and context7 from `claude-plugins-official`, ponytail 5.3.2 from `DietrichGebert/ponytail`, caveman 4.0.1 from `JuliusBrussee/caveman`. Declared in `.claude/settings.json`, so new Claude Code sessions in this repo offer to install them | Project | Installed; loads in the next session. Ponytail options (task board, webhook) left unset so nothing is sent out |
| Context7 | Plugin `context7@claude-plugins-official` (remote MCP at mcp.context7.com) | Project | Installed; `claude mcp list` shows "Needs authentication". Anonymous use or a `CONTEXT7_API_KEY` environment secret still to verify with a real query |
| RTK | Not installed: it is a machine-wide binary plus a hook, and cloud sessions are temporary. Would belong in the environment setup script | Machine | Pending, needs the user's environment settings |
| Graphify | Not used: repo is small enough to read whole | n/a | Not applicable |
| Memory (layered) | Repo docs are the memory; `AGENTS.md` is the short index. User preferences already live in `my-style.md` / `my-rules.md` (private) | Project | Done |
| Hooks | None added; nothing needs automatic enforcement beyond CI | n/a | Not applicable |
| agent-browser / Chrome DevTools MCP | No web UI to test | n/a | Not applicable |
| Vercel skills, next-dev-loop, next-bundle-optimizer, shadcn | No Next.js or React | n/a | Not applicable |
| PostgreSQL skills | No database | n/a | Not applicable |
| gh-fix-ci | GitHub Actions now exists, but the skill targets Codex; Claude Code fixes CI directly | n/a | Not applicable |
| Sentry MCP | No deployed service | n/a | Not applicable |
| Security review | Invocation recorded in `docs/quality.md` (prompt 13 at the revision above) | Project | Configured, not run |
| Anthropic skills / skill creator | The editor is already a skill; no extra skill needed | n/a | Not applicable |
| Harness2-style improvement | No observed agent failure to compare against. Smoke cases below; repeat with prompt 15 when an agent misbehaves | Project | Procedure recorded |

## Checks

| Item | State | Evidence |
| --- | --- | --- |
| `npm run check` | Validated | 8 checks pass |
| Skill copy drift | Validated | Changing the `.claude` description makes `npm run check` exit 1 |
| Word order fix | Validated | Unit test fails without the sort; real Groq run now writes "Bom." before "Só isso." |
| `node setup.mjs` | Validated (before changes) | "SETUP COMPLETE", test render OK; setup code unchanged |
| CI workflow | Configured without execution | Runs on next push |

## Harness smoke cases

| Case | Result |
| --- | --- |
| Agent told "start" finds and follows the skill | Pass in this session (setup ran, style saved) |
| Agent told "edit this" with a video link finds the workflow | Pass in this session |
| Fresh client session reading `AGENTS.md` | Not run (cannot restart the client from here) |

## Pending work and next delivery

- Enable GitHub Actions on the fork so CI runs.
- Open a new session and confirm the four plugins load; run one Context7 query.
- Groq's 25 MB limit: long recordings would need audio chunking in `transcribe.mjs`.
- Rerun: paste prompt 12 again; it should find this file, keep the decisions above, and only
  update what changed in the toolkit.
