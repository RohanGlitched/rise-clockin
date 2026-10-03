#!/usr/bin/env bash
set -e
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
export NODE_OPTIONS=--no-experimental-strip-types
WEB_FAUCET_SOL=0.6 node node_modules/ts-node/dist/bin.js -T -P tsconfig.json scripts/setup-devnet.ts
node node_modules/ts-node/dist/bin.js -T -P tsconfig.json scripts/seed.ts
