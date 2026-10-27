#!/usr/bin/env bash
# Usage: ./run_demo.sh [sw|gbn|sr] [loss%] [reorder%]
set -euo pipefail
cd "$(dirname "$0")"
PROTO=${1:-sr}; LOSS=${2:-10}; REORDER=${3:-0}
[ -d out ] || ./build.sh
T=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$T"' EXIT
java -cp out rdt.Main genfile --out "$T/in.bin" --bytes 2000000 --seed 5
java -cp out rdt.Main receive --port 0 --out "$T/out.bin" > "$T/r.log" &
while ! grep -q READY "$T/r.log" 2>/dev/null; do sleep 0.1; done
RP=$(awk '/READY/{print $2}' "$T/r.log")
java -cp out rdt.Main emulate --listen 0 --target 127.0.0.1:$RP --loss "$LOSS" --reorder "$REORDER" --seed 7 > "$T/e.log" &
while ! grep -q READY "$T/e.log" 2>/dev/null; do sleep 0.1; done
EP=$(awk '/READY/{print $2}' "$T/e.log")
java -cp out rdt.Main send --to 127.0.0.1:$EP --file "$T/in.bin" --proto "$PROTO" --window 16
sleep 0.5
echo "source  sha256: $(java -cp out rdt.Main hash "$T/in.bin")"
echo "received sha256: $(java -cp out rdt.Main hash "$T/out.bin")"
cmp "$T/in.bin" "$T/out.bin" && echo "BYTE-FOR-BYTE IDENTICAL"
