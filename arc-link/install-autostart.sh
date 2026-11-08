#!/usr/bin/env bash
# Build ArcLink.app (wraps arclink.py), set it to launch at login, and keep it alive.
# A .app has a stable identity macOS can remember the Input-Monitoring/Accessibility grant for —
# which a bare launchd python does not. arclink itself auto-reconnects when the Pad is plugged in.
set -e
PY="$(command -v python3 || echo /opt/homebrew/bin/python3)"
APP="$HOME/Applications/ArcLink.app"

mkdir -p "$APP/Contents/MacOS"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>ArcLink</string>
  <key>CFBundleIdentifier</key><string>ch.architectdna.arclink.app</string>
  <key>CFBundleExecutable</key><string>ArcLink</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSUIElement</key><true/>
</dict></plist>
PLIST

cat > "$APP/Contents/MacOS/ArcLink" <<RUN
#!/bin/bash
cd "\$HOME/arc-link"
while true; do
  "$PY" arclink.py >> /tmp/arclink.out.log 2>&1
  sleep 3
done
RUN
chmod +x "$APP/Contents/MacOS/ArcLink"

# launch at login (hidden background app)
osascript -e "tell application \"System Events\" to delete (every login item whose name is \"ArcLink\")" >/dev/null 2>&1 || true
osascript -e "tell application \"System Events\" to make login item at end with properties {path:\"$APP\", hidden:true}" >/dev/null 2>&1 || true

echo "Built: $APP  (added to Login Items)"
echo
echo "NEXT — one time only:"
echo "  1) open \"$APP\"        # starts it; triggers the permission prompts"
echo "  2) System Settings -> Privacy & Security -> Accessibility   : enable ArcLink (or python3)"
echo "     System Settings -> Privacy & Security -> Input Monitoring : enable ArcLink (or python3)"
echo "  3) open \"$APP\"        # run again now that it's trusted"
echo
echo "From then on it starts at every login and reconnects whenever the Pad is plugged in."
echo "Log: /tmp/arclink.out.log    Stop now: pkill -f arclink.py"
