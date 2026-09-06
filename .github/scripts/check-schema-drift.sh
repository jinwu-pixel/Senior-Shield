#!/usr/bin/env bash
# Fails when Room's exported schema directory differs from the committed tree.
# `git status --porcelain --untracked-files=all` covers modified/deleted tracked JSON AND new
# untracked JSON (a bumped database version whose schema was never committed), which a plain
# `git diff --exit-code` would miss. Usage: check-schema-drift.sh [dir]  (default data/schemas)
set -euo pipefail
dir="${1:-data/schemas}"
if [ ! -d "$dir" ]; then
  echo "Room schema directory missing: $dir"
  exit 1
fi
drift="$(git status --porcelain --untracked-files=all -- "$dir")"
if [ -n "$drift" ]; then
  echo "Room schema drift detected under $dir (tracked or untracked):"
  echo "$drift"
  git --no-pager diff --stat -- "$dir"
  exit 1
fi
echo "Room schema drift 0 under $dir (tracked and untracked)"
