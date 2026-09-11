#!/bin/bash
set -e

# RV32IMA Java Emulator Release Validation Script
#
# Runs the full quality-gated build (Spotless, Checkstyle, SpotBugs, all tests, and the
# packaged-CLI smoke test -- all bound to the `verify` phase) and then a final validation
# run against a freshly compiled baremetal test binary.
#
# This script does NOT version, tag, or publish anything. See docs/RELEASING.md for the
# maven-release-plugin-driven version/tag/build procedure. Run this script standalone at
# any time as a sanity check, or from inside target/checkout after `mvn release:perform`
# to validate a tagged release build specifically.

echo "--- Starting Release Validation for RV32IMA Java Emulator ---"

echo "Step 1: Running the full quality-gated build (./mvnw clean verify)..."
./mvnw clean verify

VERSION=$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout)
CORE_JAR="core/target/rv32emu-core-${VERSION}.jar"
CLI_JAR="cli/target/rv32emu-cli-${VERSION}.jar"

echo "Step 2: Compiling baremetal test binary using Clang..."
(cd baremetal && make clean && make)

echo "Step 3: Final validation run against the freshly built baremetal binary..."
java -cp "${CLI_JAR}:${CORE_JAR}" com.alienspacebunny.cli.Main -f baremetal/baremetal.bin -c 1000000

echo "--- Release validation successful! ---"
echo "Artifacts available in:"
echo "  - ${CORE_JAR}"
echo "  - ${CLI_JAR}"
