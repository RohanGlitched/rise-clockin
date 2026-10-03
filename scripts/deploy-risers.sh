#!/usr/bin/env bash
# Puts the early risers on the always-on server: bundled script + their devnet keys + cron every 3 min.
set -e
HOST=${RISERS_HOST:?set RISERS_HOST=user@host}
SSH="ssh -i $HOME/.ssh/id_ed25519 -o ConnectTimeout=20"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
$SSH $HOST 'mkdir -p ~/rise/keys/bots && cd ~/rise && if [ ! -x node/bin/node ]; then curl -sL https://nodejs.org/dist/v22.18.0/node-v22.18.0-linux-arm64.tar.xz | tar -xJ && mv node-v22.18.0-linux-arm64 node; fi; node/bin/node -v'
scp -i $HOME/.ssh/id_ed25519 -q "$ROOT/scripts/dist/bots.js" $HOST:rise/bots.js
scp -i $HOME/.ssh/id_ed25519 -q "$ROOT"/keys/bots/*.json $HOST:rise/keys/bots/
$SSH $HOST 'chmod 600 ~/rise/keys/bots/*.json; LINE="*/3 * * * * RISE_ROOT=/home/ubuntu/rise /home/ubuntu/rise/node/bin/node /home/ubuntu/rise/bots.js >> /home/ubuntu/rise/bots.log 2>&1"; (crontab -l 2>/dev/null | grep -v "rise/bots.js"; echo "$LINE") | crontab -; crontab -l; cd ~/rise && RISE_ROOT=/home/ubuntu/rise node/bin/node bots.js 2>&1 | grep -v -E "bigint|punycode|deprecation" | tail -5; echo run-ok'
