#!/usr/bin/env node
// Fast checks that need no install, no network, and no video: syntax, skill files, unit tests.
//
//   npm run check
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const failures = [];
const ok = (label) => console.log(`ok   ${label}`);
const fail = (label, why) => { failures.push(label); console.log(`FAIL ${label}\n     ${why}`); };
const run = (label, cmd, args) => {
  const r = spawnSync(cmd, args, { cwd: root, encoding: 'utf8' });
  if (r.error) fail(label, r.error.message);
  else if (r.status !== 0) fail(label, (r.stderr || r.stdout).trim());
  else ok(label);
};

for (const f of ['setup.mjs', 'tools/transcribe.mjs', 'tools/transcript.mjs', 'scripts/check.mjs']) {
  run(`syntax ${f}`, process.execPath, ['--check', f]);
}
const python = process.platform === 'win32' ? 'python' : 'python3';
const py = spawnSync(python, ['--version']);
if (py.error || py.status !== 0) console.log('skip syntax tools/transcribe_local.py (no Python)');
else run('syntax tools/transcribe_local.py', python, ['-c',
  "import ast,sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())", 'tools/transcribe_local.py']);

// Every agent reads .agents/; Claude Code reads only .claude/, so the two descriptions must match.
const frontmatter = (path) => {
  const m = readFileSync(join(root, path), 'utf8').replace(/\r\n/g, '\n').match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) return null;
  return Object.fromEntries(m[1].split('\n').map((l) => l.match(/^(\w+):\s*(.*)$/)).filter(Boolean).map((x) => [x[1], x[2]]));
};
const shared = frontmatter('.agents/skills/free247editor/SKILL.md');
const claude = frontmatter('.claude/skills/free247editor/SKILL.md');
if (!shared?.name || !shared?.description) fail('skill frontmatter', '.agents/skills/free247editor/SKILL.md needs name and description');
else if (shared.name !== 'free247editor') fail('skill frontmatter', `name is "${shared.name}", expected "free247editor"`);
else ok('skill frontmatter');
if (!claude || claude.name !== shared?.name || claude.description !== shared?.description) {
  fail('skill copies match', 'copy name and description from .agents/skills/free247editor/SKILL.md into .claude/skills/free247editor/SKILL.md');
} else ok('skill copies match');

const tests = readdirSync(join(root, 'tests')).filter((f) => f.endsWith('.test.mjs')).map((f) => join('tests', f));
run('unit tests', process.execPath, ['--test', ...tests]);

console.log(failures.length ? `\n${failures.length} check(s) failed.` : '\nAll checks passed.');
process.exit(failures.length ? 1 : 0);
