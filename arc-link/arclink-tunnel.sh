#!/usr/bin/env bash
# ARC Link — keep the VNC-over-USB tunnel alive. Re-applies `adb reverse` on every reconnect so the
# Pad's 127.0.0.1:5900 (which rides the USB cable, not WiFi) always works. Needs NO macOS permission,
# so unlike the keyboard client this autostarts cleanly via launchd.
ADB="$(command -v adb || echo /opt/homebrew/bin/adb)"
PORT="${ARCLINK_VNC:-5900}"
while true; do
  "$ADB" wait-for-device 2>/dev/null
  if "$ADB" reverse "tcp:$PORT" "tcp:$PORT" 2>/dev/null; then
    echo "$(date '+%H:%M:%S') tunnel up — VNC to 127.0.0.1:$PORT (over USB)"
  fi
  # hold until the Pad is unplugged, then loop to re-apply on the next connect
  while [ "$("$ADB" get-state 2>/dev/null)" = "device" ]; do sleep 2; done
  echo "$(date '+%H:%M:%S') Pad unplugged — waiting for reconnect"
done
