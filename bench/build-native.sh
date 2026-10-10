#!/usr/bin/env bash
# Builds the bench native images from bench/target/rv32emu-bench-*.jar (run
# `./mvnw -pl bench -am package` first). Images are built sequentially, never
# during measurement. Usage: [BENCH_JAR=jar] bench/build-native.sh [OUTPUT_DIR] [IMAGE...]
# Default images: poly, mono-{bytearray,ffm,directbuffer,router}, poly-o3, poly-v2, poly-v3.
# Only portable targets: -march=native would bake in the build host's CPU features.
# Each build log records the applied level/target ("Graal compiler: optimization level").
set -euo pipefail

NATIVE_IMAGE="${NATIVE_IMAGE:-$HOME/.jdks/graalvm-community-25.3.4.1+1.1/bin/native-image}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/bench/out/native}"
shift || true
IMAGES=("$@")
if [ ${#IMAGES[@]} -eq 0 ]; then
    IMAGES=(poly mono-bytearray mono-ffm mono-directbuffer mono-router poly-o3 poly-v2 poly-v3)
fi
JAR="${BENCH_JAR:-$(ls "$ROOT"/bench/target/rv32emu-bench-*-SNAPSHOT.jar 2>/dev/null | grep -v -e original -e javadoc -e sources | head -n 1)}"
[ -n "$JAR" ] || { echo "bench jar not found; run ./mvnw -pl bench -am package" >&2; exit 1; }
mkdir -p "$OUT"

# Matches V-32's native flags (O2, compatibility target, Serial GC, shared arenas).
BASE=(--no-fallback --parallelism=4 -J-Xmx4g -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport)
PKG=com.alienspacebunny.bench

for image in "${IMAGES[@]}"; do
    case "$image" in
        poly) main=PolyMain; flags=(-O2 -march=compatibility) ;;
        mono-bytearray) main=MonoByteArrayMain; flags=(-O2 -march=compatibility) ;;
        mono-ffm) main=MonoFfmMain; flags=(-O2 -march=compatibility) ;;
        mono-directbuffer) main=MonoDirectBufferMain; flags=(-O2 -march=compatibility) ;;
        mono-router) main=MonoRouterMain; flags=(-O2 -march=compatibility) ;;
        poly-o3) main=PolyMain; flags=(-O3 -march=compatibility) ;;
        poly-v2) main=PolyMain; flags=(-O2 -march=x86-64-v2) ;;
        poly-v3) main=PolyMain; flags=(-O2 -march=x86-64-v3) ;;
        *) echo "unknown image $image" >&2; exit 1 ;;
    esac
    echo "== $image: $main ${flags[*]}"
    "$NATIVE_IMAGE" "${BASE[@]}" "${flags[@]}" -cp "$JAR" "$PKG.$main" -o "$OUT/$image" \
        > "$OUT/$image.build.log" 2>&1 || { tail -n 40 "$OUT/$image.build.log" >&2; exit 1; }
    printf '%s %s %s\n' "$image" "$(sha256sum "$OUT/$image" | cut -d' ' -f1)" "${flags[*]}" >> "$OUT/images.txt"
done
sha256sum "$JAR" > "$OUT/jar.sha256"
