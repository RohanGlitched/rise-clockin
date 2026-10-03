#!/usr/bin/env bash
# Runs the program tests (Bankrun, with a controllable clock). Linux / WSL.
set -e
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$PATH"
cd "$(dirname "$0")/.."
mkdir -p tests/fixtures && cp target/deploy/rise.so tests/fixtures/rise.so
NODE_OPTIONS=--no-experimental-strip-types node node_modules/ts-mocha/bin/ts-mocha -p ./tsconfig.json -t 1000000 tests/rise.ts 2>&1 | grep -v "DEBUG solana_runtime"
