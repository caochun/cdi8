#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIZ_DIR="$ROOT_DIR/apps/icf-viz"
BRIDGE_HOST="${GXLF_BRIDGE_HOST:-127.0.0.1}"
BRIDGE_PORT="${GXLF_BRIDGE_PORT:-8765}"
VIZ_PORT="${VIZ_PORT:-3001}"
EVENT_URL="http://${BRIDGE_HOST}:${BRIDGE_PORT}/events"

bridge_pid=""
existing_viz_url=""
bridge_started=0

cleanup() {
  if [[ -n "$bridge_pid" ]] && kill -0 "$bridge_pid" 2>/dev/null; then
    kill "$bridge_pid" 2>/dev/null || true
    wait "$bridge_pid" 2>/dev/null || true
  fi
}

trap cleanup EXIT
trap 'cleanup; exit 0' INT TERM

port_is_open() {
  python3 - "$1" "$2" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.settimeout(0.2)
    raise SystemExit(0 if sock.connect_ex((host, port)) == 0 else 1)
PY
}

choose_viz_port() {
  local port="$1"
  while port_is_open 127.0.0.1 "$port"; do
    port=$((port + 1))
  done
  echo "$port"
}

read_existing_viz_url() {
  local lock_file="$VIZ_DIR/.next/dev/lock"
  [[ -f "$lock_file" ]] || return 1
  python3 - "$lock_file" <<'PY'
import json
import os
import signal
import sys

lock_file = sys.argv[1]

try:
    with open(lock_file, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    pid = int(data.get("pid", 0))
    url = data.get("appUrl")
    if pid and url:
        os.kill(pid, 0)
        print(url)
        raise SystemExit(0)
except (OSError, ValueError, json.JSONDecodeError):
    pass

raise SystemExit(1)
PY
}

if [[ ! -d "$VIZ_DIR/node_modules" ]]; then
  echo "Installing icf-viz dependencies..."
  (cd "$VIZ_DIR" && npm install)
fi

if port_is_open "$BRIDGE_HOST" "$BRIDGE_PORT"; then
  echo "Using existing GXLF event bridge: $EVENT_URL"
else
  echo "Starting GXLF event bridge: $EVENT_URL"
  (cd "$ROOT_DIR" && python3 -m gxlf_sim_system bridge --host "$BRIDGE_HOST" --port "$BRIDGE_PORT") &
  bridge_pid="$!"
  bridge_started=1
fi

sleep 1

existing_viz_url="$(read_existing_viz_url || true)"
if [[ -n "$existing_viz_url" ]]; then
  echo
  echo "Using existing ICF/GXLF visualization:"
  echo "  $existing_viz_url"
  echo
  echo "Event stream:"
  echo "  $EVENT_URL"
  echo "Engine controls:"
  echo "  UI panel: Start / Pause / Resume / Stop / Reset"
  echo "  curl -X POST http://${BRIDGE_HOST}:${BRIDGE_PORT}/commands -H 'Content-Type: application/json' -d '{\"command\":\"start\"}'"
  echo
  if [[ "$bridge_started" -eq 1 ]]; then
    echo "Event bridge was started by this command. Press Ctrl+C to stop it."
    wait "$bridge_pid"
  else
    echo "Both services are already running. Open the URL above."
  fi
  exit 0
fi

VIZ_PORT="$(choose_viz_port "$VIZ_PORT")"

echo
echo "Starting ICF/GXLF visualization:"
echo "  http://localhost:${VIZ_PORT}"
echo
echo "Engine starts idle. Use the UI Engine panel, or:"
echo "  curl -X POST http://${BRIDGE_HOST}:${BRIDGE_PORT}/commands -H 'Content-Type: application/json' -d '{\"command\":\"start\"}'"
echo

cd "$VIZ_DIR"
NEXT_PUBLIC_GXLF_EVENTS_URL="$EVENT_URL" npm run dev -- --port "$VIZ_PORT"
