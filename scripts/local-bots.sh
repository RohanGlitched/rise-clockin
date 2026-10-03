#!/usr/bin/env bash
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$PATH"
cd "$(dirname "$0")/.."
TICKS=${TICKS:-180} NODE_OPTIONS=--no-experimental-strip-types node node_modules/ts-node/dist/bin.js -T -P tsconfig.json scripts/local-demo.ts
