#!/usr/bin/env bash
# ARC Link — keep the VNC-over-USB tunnel alive. Re-applies `adb reverse` every few seconds (cheap,
# idempotent) so 127.0.0.1:5900 survives not just replugs but Mac sleep/wake and adb-server restarts,
# which silently drop the rule without a disconnect. Needs NO macOS permission → autostarts cleanly.
ADB="$(command -v adb || echo /opt/homebrew/bin/adb)"
PORT="${ARCLINK_VNC:-5900}"
last=""
while true; do
  st="$("$ADB" get-state 2>/dev/null)"
  if [ "$st" = "device" ]; then
    "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1
    [ "$last" != "up" ] && echo "$(date '+%H:%M:%S') tunnel up — VNC 127.0.0.1:$PORT (over USB)" && last="up"
  else
    [ "$last" != "down" ] && echo "$(date '+%H:%M:%S') no Pad — waiting for connect" && last="down"
  fi
  sleep 3
done
