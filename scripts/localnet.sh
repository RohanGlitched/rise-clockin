#!/usr/bin/env bash
# Local validator with the Rise program preloaded (for app testing on the emulator).
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
cd "$(dirname "$0")/.."
mkdir -p ~/rise-ledger
exec solana-test-validator --reset --ledger ~/rise-ledger/ledger \
  --bpf-program 6kQL7PccHpE7yUrsq5TgxFgQc7K7FVbUJShRUPbWfCdS target/deploy/rise.so \
  --rpc-port 8899 > ~/rise-ledger/out.log 2>&1
