# ARC Link

Use the MacBook's keyboard + trackpad to drive the Xiaomi Pad — over the USB cable, no root,
nothing to install on the tablet. Part of the ARC-DNA system.

```
 MacBook (keyboard + trackpad)  ──USB──►  Pad (HyperOS / Android)
         arclink.py                         adb `input`  →  taps, keys, swipes
```

## v1 — works today (this folder)

No on-device app. A persistent `adb shell` is fed Android's `input` command, so there is no
per-keystroke adb reconnection. Handles typing, navigation, tap, scroll and drag.

**On the tablet:** Settings → About → tap *Build number* 7× to unlock Developer options →
turn on **USB debugging**. Plug into the Mac with a data cable. Tap **Allow** on the prompt.

**On the Mac:**
```bash
cd arc-link
./setup.sh          # installs adb + pynput if missing, then launches
```
First run, macOS asks for **Accessibility** and **Input Monitoring** permission
(System Settings → Privacy & Security) — grant both so it can read the keyboard/trackpad.

**While running:** `F10` toggle forwarding · `F1` back · `F2` home · `F3` recents.
Everything else types / points onto the tablet. `Ctrl-C` to quit.

If the cursor moves too fast/slow or lands off, tweak `MOUSE_SENS` / the tablet size fallback at
the top of `arclink.py`.

## Honest limits of v1

- The mouse is a *virtual* cursor (adb has no real pointer), so taps/drags are placed, not hovered
  — no true hover, and fast dragging is approximate.
- `input` spawns per action (~30–80 ms), fine for typing & tapping, not for silky scrolling.
- Keyboard **chords** (⌘C, ⌘-tab…) are limited — plain typing + the mapped keys are solid.

## v2 — low latency (server/)

`server/ArcLinkServer.java` is a scrcpy-style server: launched over USB via `app_process` with
shell privileges (still no root), it opens a socket and injects real `KeyEvent` / `MotionEvent`
through the hidden `InputManager.injectInputEvent`. That removes the per-action `input` cost and
gives a true moving pointer and real key chords. It needs a one-time dex build (notes in
`server/README.md`); the Mac client then speaks its small binary protocol instead of `input`.

## Roadmap (the full vision)

- [x] **v1 injector** — Mac keyboard/trackpad → Android, over USB.
- [ ] **v2 server** — low-latency injection, real pointer, key chords.
- [ ] **Mode B — see the Mac on the Pad.** Mirror the (screen-broken) MacBook onto the tablet
      via a virtual display (BetterDisplay) + screen sharing over `adb reverse`. The Mac drives
      itself; the Pad is the monitor. (Off-the-shelf; see the chat setup notes.)
- [ ] **Four-finger-swipe switch** in ARC-DNA — flip the Pad between *being* Android (Mode A)
      and *showing* macOS (Mode B), signalling the Mac client where to send input.
