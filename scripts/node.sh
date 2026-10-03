#!/usr/bin/env bash
# Runs a bundled script inside WSL: scripts/node.sh bots [env...]
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export RISE_ROOT="$ROOT"
exec node "$ROOT/scripts/dist/$1.js"
