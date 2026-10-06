#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/lib/junit-platform-console-standalone-1.10.2.jar"

echo "==> Compiling production sources..."
mkdir -p "$ROOT/bin"
javac -d "$ROOT/bin" "$ROOT/src/"*.java

echo "==> Compiling test sources..."
mkdir -p "$ROOT/bin/test"
javac -cp "$ROOT/bin:$JAR" -d "$ROOT/bin/test" \
    "$ROOT/test/unit/"*.java \
    "$ROOT/test/integration/"*.java

echo "==> Compilation successful."
