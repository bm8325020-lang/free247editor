#!/bin/bash
# Setup script for Claude Code cloud sessions: paste this file's contents into the cloud
# environment's settings (Edit > Setup script). Each new session then has the free247editor
# engine, the Vibe Coding Toolkit, Claude Code Game Studios, and OmniRoute ready. Safe to re-run.
#
# Put the Groq key in the environment's secrets as GROQ_API_KEY (never in this script).
set -u
HOME_DIR=/home/user
cd "$HOME_DIR"

# Vibe Coding Toolkit: prompts and guides, read by the agent on request.
[ -d vibe-coding-toolkit ] || git clone --quiet --depth 1 https://github.com/soumatheusgomes/vibe-coding-toolkit.git

# Claude Code Game Studios: game-dev agents and skills (open a session in it and run /start).
[ -d Claude-Code-Game-Studios ] || git clone --quiet --depth 1 https://github.com/Donchitos/Claude-Code-Game-Studios.git

# OmniRoute: AI gateway. Installed with the session's Node 22; its CLI needs Node 22.22.2+,
# so a checksum-verified Node 24 goes in ~/.local/node24 just for the CLI.
if [ ! -x "$HOME_DIR/.local/node24/bin/node" ]; then
  mkdir -p "$HOME_DIR/.local/node24" && cd "$HOME_DIR/.local/node24" &&
    curl -sfL https://nodejs.org/dist/v24.14.1/SHASUMS256.txt -o SHASUMS256.txt &&
    curl -sfLO https://nodejs.org/dist/v24.14.1/node-v24.14.1-linux-x64.tar.xz &&
    grep " node-v24.14.1-linux-x64.tar.xz$" SHASUMS256.txt | sha256sum -c --quiet - &&
    tar -xJf node-v24.14.1-linux-x64.tar.xz --strip-components=1 && rm -f node-v24.14.1-linux-x64.tar.xz
  cd "$HOME_DIR"
fi
[ -d OmniRoute ] || git clone --quiet --depth 1 https://github.com/diegosouzapw/OmniRoute.git
[ -d OmniRoute/node_modules ] || (cd OmniRoute && npm ci --no-fund --no-audit --loglevel=error)

# free247editor: editing engine and test render (only if the repo is in this session).
[ -f free247editor/setup.mjs ] && (cd free247editor && node setup.mjs > /tmp/free247editor-setup.log 2>&1)

exit 0
