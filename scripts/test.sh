#!/usr/bin/env bash
# Runs the program tests (Bankrun, with a controllable clock) on Linux / WSL.
# On WSL it mirrors the project to the native filesystem first: loading Bankrun over /mnt is slow.
# Usage: bash scripts/test.sh            # every file in tests/
#        bash scripts/test.sh tests/windows.ts
set -e
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$PATH"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$SRC"
if [[ "$SRC" == /mnt/* ]]; then
  DIR="$HOME/.cache/rise-test"
  mkdir -p "$DIR/target/deploy" "$DIR/tests/fixtures"
  rsync -a --delete "$SRC/tests/" "$DIR/tests/" --exclude fixtures
  rsync -a "$SRC/target/idl" "$SRC/target/types" "$DIR/target/"
  cp "$SRC/package.json" "$SRC/package-lock.json" "$SRC/.npmrc" "$SRC/tsconfig.json" "$DIR/"
  cmp -s "$SRC/package-lock.json" "$DIR/.installed-lock" || { (cd "$DIR" && npm ci --silent) && cp "$SRC/package-lock.json" "$DIR/.installed-lock"; }
fi
mkdir -p "$DIR/tests/fixtures" && cp "$SRC/target/deploy/rise.so" "$DIR/tests/fixtures/rise.so"
cd "$DIR"
set -o pipefail
TS_NODE_TRANSPILE_ONLY=1 NODE_OPTIONS=--no-experimental-strip-types node node_modules/ts-mocha/bin/ts-mocha -p ./tsconfig.json -t 1000000 "${@:-tests/*.ts}" 2>&1 | grep -v "DEBUG solana_runtime"
