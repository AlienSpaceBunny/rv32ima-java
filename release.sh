#!/bin/bash
set -e

# RV32IMA Java Emulator Release Script
# This script automates the build, test, and packaging of a release.

echo "--- Starting Release Process for RV32IMA Java Emulator ---"

# 1. Clean and Run all tests (Core Unit Tests + CLI Integration Tests)
echo "Step 1: Running all tests..."
./mvnw clean test

# 2. Build the project artifacts
echo "Step 2: Building artifacts..."
./mvnw install -DskipTests

# 3. Compile the baremetal test binary using Clang
echo "Step 3: Compiling baremetal test binary..."
(cd baremetal && make clean && make)

# 4. Verify the built CLI runner with the fresh baremetal binary
echo "Step 4: Final validation run..."
java -cp cli/target/rv32emu-cli-0.1.0.jar:core/target/rv32emu-core-0.1.0.jar com.alienspacebunny.cli.Main -f baremetal/baremetal.bin -c 1000000

echo "--- Release build successful! ---"
echo "Artifacts available in:"
echo "  - core/target/rv32emu-core-0.1.0.jar"
echo "  - cli/target/rv32emu-cli-0.1.0.jar"
