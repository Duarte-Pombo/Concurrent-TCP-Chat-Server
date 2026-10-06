#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/lib/junit-platform-console-standalone-1.10.2.jar"

java -jar "$JAR" \
    --class-path "$ROOT/bin:$ROOT/bin/test" \
    --scan-class-path "$ROOT/bin/test" \
    "$@"
