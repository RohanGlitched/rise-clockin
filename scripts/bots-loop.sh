#!/usr/bin/env bash
# Keeps the devnet early risers waking up: one pass every 3 minutes.
while true; do
  bash "$(dirname "$0")/node.sh" bots 2>&1 | grep -v -E "bigint|punycode|trace-deprecation"
  sleep 180
done
