#!/usr/bin/env bash
set -euo pipefail

magnus_pid=""
receiver_pid=""

cleanup() {
    trap - INT TERM EXIT
    if [ -n "$receiver_pid" ]; then
        kill "$receiver_pid" 2>/dev/null || true
    fi
    if [ -n "$magnus_pid" ]; then
        kill "$magnus_pid" 2>/dev/null || true
    fi
    wait "$receiver_pid" "$magnus_pid" 2>/dev/null || true
}

trap cleanup INT TERM EXIT

DISPLAY=:2 xrandr --output VNC-0 --mode 1920x1080

./magnus --to-display=:2 --zoomlevel=2 &
magnus_pid="$!"

./bt300/receiver.py &
receiver_pid="$!"

while true; do
    if ! kill -0 "$magnus_pid" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$receiver_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done
