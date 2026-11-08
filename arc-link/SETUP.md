# ARC Link — full setup (do this on the laptop)

Everything you need, in order. Apple-Silicon MacBook Air + Xiaomi Pad (HyperOS), one USB cable.
Have a monitor/TV plugged into the Mac for the one-time GUI toggles (Parts 0 & B).

---

## 0 · One-time Mac basics (monitor plugged in)

```bash
# install Homebrew if you don't have it
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# adb + python deps
brew install android-platform-tools
python3 -m pip install --user pynput
```

Put the two ARC Link files (`arclink.py`, `setup.sh`) in a folder, e.g. `~/arc-link/`.
(They're also in the repo at `arc-dna-home/arc-link/`.)

---

## A · Control the Pad FROM the Mac keyboard/trackpad  (Mode A)

**On the Pad (once):** Settings → About tablet → tap **Build number** 7× → back → Developer
options → turn on **USB debugging**. Plug into the Mac. Tap **Allow** on the popup.

**On the Mac:**
```bash
cd ~/arc-link
chmod +x setup.sh
./setup.sh
```
- First run, macOS asks for **Accessibility** and **Input Monitoring** →
  System Settings → Privacy & Security → turn ON for Terminal (or your shell). Re-run `./setup.sh`.
- Now your Mac keyboard types onto the Pad; trackpad moves/taps.
  **F10** pause/resume · **F1** back · **F2** home · **F3** recents · **Ctrl-C** quit.

That's Mode A fully working.

---

## B · See the Mac ON the Pad  (Mode B — the mirror)

So you can *use* the broken-screen MacBook, shown on the Pad.

**1. Virtual display** (so the headless Mac still draws a desktop):
```bash
brew install --cask betterdisplay
open -a BetterDisplay
```
In BetterDisplay's menu-bar icon → **Create New Virtual Screen** → pick the Pad's resolution
(e.g. 2560×1600). You can now unplug the real monitor later and the Mac keeps rendering this one.

**2. Turn on sharing** (System Settings → General → **Sharing**):
- **Screen Sharing** → ON
- **Remote Login** → ON (gives you SSH as a backup way in if the screen ever dies mid-setup)

**3. Tunnel the screen over USB** (Pad plugged in, USB debugging on):
```bash
adb reverse tcp:5900 tcp:5900
```

**4. On the Pad:** install a VNC viewer from Play Store (**bVNC Free** works). New connection →
address `127.0.0.1`, port `5900` → connect → log in with your **Mac account name + password**.
You now see and drive macOS on the Pad (the Mac's own keyboard/trackpad control it).

> Mode B over VNC is smooth enough for coding/browsing, not for video. That's a VNC limit;
> the custom low-latency pipe is a later upgrade.

---

## Pointing & the cursor (read this)

v1 has **no visible cursor on the tablet** — `adb input` can only tap, it can't draw a pointer.
So in Mode A: **use the Mac for the keyboard, and touch the Pad directly to point.** The trackpad
is suppressed (so it won't mess up macOS) but it's "blind" — your finger on the Pad is the mouse.
A real moving cursor + low latency arrives with the v2 server (next build).

In Mode B the Mac's own cursor can be hidden until it moves — turn on "show remote cursor" in the
VNC app, or just nudge it. Tablet-touch moving the Mac pointer confirms it's working.

## C · Autostart (so you never type the command again)

v1.1 only engages forwarding **while the Pad is plugged in**, so this is safe at login — if the
Pad isn't connected, your Mac keyboard/trackpad stay completely normal.

```bash
# 1. Run it once interactively first and grant Accessibility + Input Monitoring to python3.
# 2. Edit the plist: set USERNAME and (if needed) the python3 path.
cp arc-link/ch.architectdna.arclink.plist ~/Library/LaunchAgents/
nano ~/Library/LaunchAgents/ch.architectdna.arclink.plist    # replace USERNAME

# 3. Lint, then load into YOUR session (NO sudo — it's a user agent, not a root daemon):
plutil -lint ~/Library/LaunchAgents/ch.architectdna.arclink.plist           # must say OK
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/ch.architectdna.arclink.plist
launchctl kickstart -k gui/$(id -u)/ch.architectdna.arclink                 # start now

# logs:  cat /tmp/arclink.err.log
# stop:  launchctl bootout gui/$(id -u)/ch.architectdna.arclink
# NOTE: never `sudo launchctl load` a LaunchAgent — that targets the root domain and fails (I/O error).
```
Now: plug in the Pad → forwarding is live; unplug → Mac is yours. `F10` still hard-pauses.

## Quick fixes

| Problem | Fix |
|---|---|
| `adb: no devices` | Re-plug, use a **data** cable, tap Allow on the Pad, `adb devices` |
| Cursor drifts / wrong spot | Edit `MOUSE_SENS` and the size fallback at the top of `arclink.py` |
| Keyboard does nothing | Grant Accessibility **and** Input Monitoring, then re-run |
| VNC won't connect | Re-run `adb reverse tcp:5900 tcp:5900`; confirm Screen Sharing is ON |
| Locked out of the Mac (screen dies) | SSH in: `ssh you@<mac-ip>` (Remote Login from step B2) |

When both work: Mode A = Pad is the computer, Mac is the keyboard. Mode B = Mac is the computer,
Pad is the screen. The four-finger-swipe switch between them lands in a later ARC-DNA build.
