#!/usr/bin/env bash
# Bundles the TypeScript scripts so they start instantly (node_modules over WSL's /mnt is slow).
cd "$(dirname "$0")/.."
for f in seed bots local-demo setup-devnet; do
  node node_modules/esbuild/bin/esbuild scripts/$f.ts --bundle --platform=node --target=node20 --outfile=scripts/dist/$f.js --log-level=warning
done
