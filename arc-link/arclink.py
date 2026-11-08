#!/usr/bin/env python3
"""
ARC Link — drive an Android tablet from this Mac's keyboard & trackpad, over the USB cable.

v1 design goals: works the moment USB debugging is on, with NO root and NO app to install on
the tablet. It holds one persistent `adb shell` and feeds it Android's `input` command, so there
is no per-keystroke adb reconnection. Good enough for typing, navigation, tap, scroll and drag.
For sub-10ms, pixel-perfect mouse, see server/ArcLinkServer.java (the v2 upgrade path).

Setup (see README.md):
    1. On the tablet: enable Developer options → USB debugging. Plug into the Mac.
    2. On the Mac:  brew install android-platform-tools   (gives `adb`)
                    pip3 install pynput
                    python3 arclink.py
    macOS will ask for Accessibility + Input Monitoring permission the first time — grant both
    (System Settings → Privacy & Security). Then this program can read the keyboard/trackpad.

Controls while running:
    F10  — toggle forwarding on/off (so your Mac keyboard is yours again when you need it)
    F1   — Android BACK        F2 — HOME        F3 — RECENTS
    everything else            — typed / pointed straight onto the tablet

Nothing here leaves the machine: it is Mac → USB cable → tablet only.
"""

import os
import shlex
import shutil
import subprocess
import sys
import threading
import time

try:
    from pynput import keyboard, mouse
except ImportError:
    sys.exit("Missing dependency. Run:  pip3 install pynput")

ADB = shutil.which("adb")
if not ADB:
    sys.exit("`adb` not found. Install it:  brew install android-platform-tools")

# ── tunables ────────────────────────────────────────────────────────────────
MOUSE_SENS   = 1.6      # trackpad → tablet cursor speed
DRAG_THRESH  = 14       # px of movement that turns a click into a drag
SCROLL_STEP  = 90       # px per scroll notch
FLUSH_MS     = 45       # how often buffered text is sent (lower = snappier, more commands)

# ── Android keycodes we map explicitly (everything printable goes via `input text`) ──
KEYEVENT = {
    keyboard.Key.enter: 66, keyboard.Key.backspace: 67, keyboard.Key.tab: 61,
    keyboard.Key.esc: 111, keyboard.Key.delete: 112,
    keyboard.Key.up: 19, keyboard.Key.down: 20, keyboard.Key.left: 21, keyboard.Key.right: 22,
    keyboard.Key.home: 122, keyboard.Key.end: 123, keyboard.Key.page_up: 92, keyboard.Key.page_down: 93,
    keyboard.Key.space: 62,
    # function keys do double duty as Android navigation
    keyboard.Key.f1: 4,    # BACK
    keyboard.Key.f2: 3,    # HOME
    keyboard.Key.f3: 187,  # APP_SWITCH (recents)
}


class Device:
    """One persistent adb shell; we write `input ...` lines to its stdin."""

    def __init__(self):
        self.lock = threading.Lock()
        self.proc = subprocess.Popen(
            [ADB, "shell"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, bufsize=0,
        )
        self.w, self.h = self._screen_size()
        self.cx, self.cy = self.w / 2, self.h / 2  # virtual cursor

    def _screen_size(self):
        try:
            out = subprocess.check_output([ADB, "shell", "wm", "size"], text=True, timeout=5)
            # "Override size: 1600x2560" wins over "Physical size: ..."
            line = [l for l in out.splitlines() if l.strip()][-1]
            w, h = line.split(":")[1].strip().split("x")
            return int(w), int(h)
        except Exception:
            return 1600, 2560  # a sane tablet default; adjust in tunables if cursor feels off

    def send(self, cmd):
        with self.lock:
            try:
                self.proc.stdin.write((cmd + "\n").encode())
                self.proc.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass

    # input helpers
    def tap(self, x, y):              self.send(f"input tap {int(x)} {int(y)}")
    def swipe(self, x1, y1, x2, y2, ms=90):
        self.send(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {ms}")
    def keyevent(self, code):         self.send(f"input keyevent {code}")
    def text(self, s):
        # single-quote for the shell; escape embedded quotes the POSIX way
        self.send("input text " + shlex.quote(s))

    def clamp(self, x, y):
        return max(0, min(self.w - 1, x)), max(0, min(self.h - 1, y))


class ArcLink:
    def __init__(self):
        self.dev = Device()
        self.on = True
        self.buf = []                  # pending printable characters
        self.buf_lock = threading.Lock()
        self.down_pos = None           # where the mouse button went down (for drag vs tap)
        self.last_move = None          # last absolute trackpad position, to get deltas
        threading.Thread(target=self._flusher, daemon=True).start()
        print(f"ARC Link ready · tablet {self.dev.w}x{self.dev.h} · forwarding ON")
        print("F10 toggle · F1 back · F2 home · F3 recents")

    # ── text buffering: coalesce fast typing into one `input text` ──
    def _flusher(self):
        while True:
            time.sleep(FLUSH_MS / 1000)
            self.flush()

    def flush(self):
        with self.buf_lock:
            if not self.buf:
                return
            s = "".join(self.buf)
            self.buf.clear()
        self.dev.text(s)

    def push_char(self, ch):
        with self.buf_lock:
            self.buf.append(ch)

    # ── keyboard ──
    def on_press(self, key):
        if key == keyboard.Key.f10:
            self.on = not self.on
            self.flush()
            print("forwarding", "ON" if self.on else "OFF")
            return
        if not self.on:
            return
        # a printable character?
        ch = getattr(key, "char", None)
        if ch is not None and ch != "" and ch not in ("\t", "\n", "\r"):
            if ch == " ":
                self.flush(); self.dev.keyevent(62)       # space is reliable as a keyevent
            else:
                self.push_char(ch)
            return
        # a mapped special key?
        code = KEYEVENT.get(key)
        if code is not None:
            self.flush(); self.dev.keyevent(code)

    # ── mouse / trackpad ──
    def on_move(self, x, y):
        if not self.on:
            self.last_move = (x, y); return
        if self.last_move is not None:
            dx = (x - self.last_move[0]) * MOUSE_SENS
            dy = (y - self.last_move[1]) * MOUSE_SENS
            self.dev.cx, self.dev.cy = self.dev.clamp(self.dev.cx + dx, self.dev.cy + dy)
        self.last_move = (x, y)

    def on_click(self, x, y, button, pressed):
        if not self.on:
            return
        if pressed:
            self.down_pos = (self.dev.cx, self.dev.cy, time.time())
        elif self.down_pos is not None:
            sx, sy, t0 = self.down_pos
            ex, ey = self.dev.cx, self.dev.cy
            self.down_pos = None
            moved = abs(ex - sx) + abs(ey - sy)
            if moved > DRAG_THRESH:
                self.dev.swipe(sx, sy, ex, ey, max(60, int((time.time() - t0) * 1000)))
            else:
                self.dev.tap(ex, ey)

    def on_scroll(self, x, y, dx, dy):
        if not self.on:
            return
        ex = self.dev.cx
        ey1 = self.dev.cy
        ey2 = self.dev.clamp(ex, self.dev.cy + dy * SCROLL_STEP)[1]
        # horizontal if that dominates
        if abs(dx) > abs(dy):
            ex2 = self.dev.clamp(self.dev.cx + dx * SCROLL_STEP, ey1)[0]
            self.dev.swipe(ex, ey1, ex2, ey1, 80)
        else:
            self.dev.swipe(ex, ey1, ex, ey2, 80)

    def run(self):
        ml = mouse.Listener(on_move=self.on_move, on_click=self.on_click, on_scroll=self.on_scroll)
        kl = keyboard.Listener(on_press=self.on_press)
        ml.start(); kl.start()
        try:
            kl.join()
        except KeyboardInterrupt:
            pass
        finally:
            self.flush()
            print("\nARC Link stopped.")


if __name__ == "__main__":
    # one device only, for now
    n = subprocess.run([ADB, "get-state"], capture_output=True, text=True)
    if "device" not in n.stdout:
        sys.exit("No device. Plug in the tablet, enable USB debugging, accept the prompt, then: adb devices")
    ArcLink().run()
