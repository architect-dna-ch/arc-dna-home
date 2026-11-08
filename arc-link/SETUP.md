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
