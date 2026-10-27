#!/usr/bin/env bash
# Compiles the Java transport core into ./out. Uses javac if present, else the JDK compiler module.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
SRC=$(find src/main/java -name '*.java')
if command -v javac >/dev/null 2>&1; then
  javac -Xlint:all -d out $SRC
else
  java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -d out $SRC
fi
echo "built -> out/"
