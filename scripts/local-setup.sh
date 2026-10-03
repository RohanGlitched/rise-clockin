#!/usr/bin/env bash
set -e
export PATH="$HOME/.nvm/versions/node/v22.18.0/bin:$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
solana airdrop 100 -u http://127.0.0.1:8899 >/dev/null
RPC_URL=http://127.0.0.1:8899 WEB_FAUCET_SOL=1 NODE_OPTIONS=--no-experimental-strip-types node node_modules/ts-node/dist/bin.js -T -P tsconfig.json scripts/setup-devnet.ts
NODE_OPTIONS=--no-experimental-strip-types node node_modules/ts-node/dist/bin.js -T -P tsconfig.json scripts/local-demo.ts
