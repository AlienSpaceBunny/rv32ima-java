#!/usr/bin/env bash
# Fetches EEMBC CoreMark at a pinned upstream commit into DIR (default bench/out/ext/coremark)
# and verifies the six benchmark sources. The sources are Apache-2.0; they are used unmodified
# and are not vendored here. Only bench/guest/coremark-port/ (the documented port layer) is ours.
set -euo pipefail
COMMIT=1f483d5b8316753a742cbf5590caf5bd0a4e4777
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="${1:-$ROOT/bench/out/ext/coremark}"
if [ ! -d "$DIR/.git" ]; then
    git clone -q https://github.com/eembc/coremark.git "$DIR"
fi
git -C "$DIR" -c advice.detachedHead=false checkout -q "$COMMIT"
# Upstream's coremark.md5 covers the five .c files; its coremark.h entry is stale at this
# commit, so the header is pinned by SHA-256 instead.
(cd "$DIR" && grep -v ' coremark.h$' coremark.md5 | md5sum -c --quiet -)
echo "42642b9a06c7ed2b3bd9eda971b7c3868c4f5d27bf7ef6c4bba11291a0c2598a  $DIR/coremark.h" | sha256sum -c --quiet -
echo "$DIR"
