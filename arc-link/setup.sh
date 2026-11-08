#!/usr/bin/env bash
# ARC Link — one-command setup & launch (macOS). Run from the arc-link/ folder:  ./setup.sh
set -euo pipefail

echo "— ARC LINK —"

# 1. adb
if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found."
  if command -v brew >/dev/null 2>&1; then
    echo "Installing android-platform-tools via Homebrew…"
    brew install android-platform-tools
  else
    echo "Install Homebrew first (https://brew.sh), then:  brew install android-platform-tools"
    exit 1
  fi
fi

# 2. python + pynput
PY=python3
if ! command -v "$PY" >/dev/null 2>&1; then echo "python3 not found. Install Python 3."; exit 1; fi
if ! "$PY" -c "import pynput" >/dev/null 2>&1; then
  echo "Installing pynput…"
  "$PY" -m pip install --user pynput
fi

# 3. device
echo "Waiting for the tablet over USB (enable Developer options → USB debugging, accept the prompt)…"
adb wait-for-device
STATE=$(adb get-state 2>/dev/null || true)
if [ "$STATE" != "device" ]; then
  echo "Tablet not authorised yet. On the tablet, tap 'Allow USB debugging', then re-run ./setup.sh"
  adb devices
  exit 1
fi
echo "Tablet connected: $(adb shell getprop ro.product.model | tr -d '\r')"

# 4. go
echo "Starting ARC Link.  (F10 toggles forwarding · F1 back · F2 home · F3 recents · Ctrl-C to quit)"
exec "$PY" "$(dirname "$0")/arclink.py"
