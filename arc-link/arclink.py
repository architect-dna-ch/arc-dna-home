#!/usr/bin/env python3
"""
ARC Link v1.1 — drive an Android tablet from this Mac's keyboard & trackpad, over the USB cable.

No root, no app on the tablet. One persistent `adb shell` fed Android's `input`.

What's new in v1.1 (from on-device testing):
  • CAPTURE: while forwarding, your keyboard/trackpad are consumed — they do NOT also act on macOS
    (no more Finder getting clicks). Uses pynput's darwin_intercept to swallow the events.
  • AUTO-ENGAGE: forwarding turns on only while the Pad is actually connected over USB, and off the
    instant it's unplugged — so at login / autostart the Mac is never hijacked when the Pad is away.
  • Key auto-repeat passes through (hold backspace / spam !); only F10 is de-bounced.
  • ROTATION-AWARE size, plus an ARCLINK_SIZE override if a tap still lands off.

What's new in v1.2:
  • ⌥/⌘ + Backspace deletes the whole word (like macOS), not one letter at a time.
  • Keyboard battery: this Mac's charge is pushed to the tablet, where the ARC-DNA launcher
    floats a small "⌨ xx%" chip — so you can see how much "keyboard" is left over USB.

Controls:
  F10  — hard pause / resume (overrides auto-engage; when paused the Mac is fully yours)
  F1 BACK · F2 HOME · F3 RECENTS · ⌥/⌘-Backspace delete word
  Ctrl-C to quit.

Env overrides:  ARCLINK_SIZE=2560x1600   ARCLINK_SENS=1.6

Note on autocomplete: typing goes through `adb input`, which bypasses the tablet's keyboard,
so Gboard's word-prediction does NOT light up here. Native prediction needs the Mac to act as a
real USB-HID keyboard — that's `scrcpy --keyboard=uhid --otg` (a separate tool). See SETUP.md.
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

def _find_adb():
    # launchd agents run with a bare PATH, so `which` alone fails — check the usual spots too.
    return shutil.which("adb") or next(
        (p for p in ("/opt/homebrew/bin/adb", "/usr/local/bin/adb",
                     "/opt/homebrew/opt/android-platform-tools/bin/adb") if os.path.exists(p)),
        None)

ADB = _find_adb()
if not ADB:
    sys.exit("`adb` not found. Install it:  brew install android-platform-tools")

MOUSE       = os.environ.get("ARCLINK_MOUSE", "1") != "0"   # trackpad ON by default; ARCLINK_MOUSE=0 to disable
MOUSE_SENS  = float(os.environ.get("ARCLINK_SENS", "1.6"))
DRAG_THRESH = 14
SCROLL_STEP = 90
FLUSH_MS    = 45

KEYEVENT = {
    keyboard.Key.enter: 66, keyboard.Key.backspace: 67, keyboard.Key.tab: 61,
    keyboard.Key.esc: 111, keyboard.Key.delete: 112,
    keyboard.Key.up: 19, keyboard.Key.down: 20, keyboard.Key.left: 21, keyboard.Key.right: 22,
    keyboard.Key.home: 122, keyboard.Key.end: 123, keyboard.Key.page_up: 92, keyboard.Key.page_down: 93,
    keyboard.Key.space: 62,
    keyboard.Key.f1: 4, keyboard.Key.f2: 3, keyboard.Key.f3: 187,   # BACK / HOME / RECENTS
}

# Option or Command held + Backspace = delete the whole word (like macOS).
MODS = {keyboard.Key.alt, keyboard.Key.alt_l, keyboard.Key.alt_r,
        keyboard.Key.cmd, keyboard.Key.cmd_l, keyboard.Key.cmd_r}


def adb_state():
    try:
        return subprocess.run([ADB, "get-state"], capture_output=True, text=True, timeout=3).stdout.strip()
    except Exception:
        return ""


class Device:
    def __init__(self):
        self.lock = threading.Lock()
        self.proc = subprocess.Popen([ADB, "shell"], stdin=subprocess.PIPE,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, bufsize=0)
        self.w, self.h = self._size()
        self.cx, self.cy = self.w / 2, self.h / 2

    def _size(self):
        ov = os.environ.get("ARCLINK_SIZE")
        if ov and "x" in ov:
            w, h = ov.lower().split("x"); return int(w), int(h)
        try:
            out = subprocess.check_output([ADB, "shell", "wm", "size"], text=True, timeout=5)
            line = [l for l in out.splitlines() if l.strip()][-1]
            w, h = (int(v) for v in line.split(":")[1].strip().split("x"))
            rot = subprocess.check_output([ADB, "shell", "settings", "get", "system", "user_rotation"],
                                          text=True, timeout=5).strip()
            if rot in ("1", "3") and h > w:      # landscape: physical is reported portrait
                w, h = h, w
            return w, h
        except Exception:
            return 2560, 1600

    def send(self, cmd):
        with self.lock:
            try:
                self.proc.stdin.write((cmd + "\n").encode()); self.proc.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass

    def tap(self, x, y):            self.send(f"input tap {int(x)} {int(y)}")
    def swipe(self, x1, y1, x2, y2, ms=90): self.send(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {ms}")
    def keyevent(self, c):         self.send(f"input keyevent {c}")
    def text(self, s):             self.send("input text " + shlex.quote(s))
    def clamp(self, x, y):         return max(0, min(self.w - 1, x)), max(0, min(self.h - 1, y))
    def center(self):             self.cx, self.cy = self.w / 2, self.h / 2


class ArcLink:
    def __init__(self):
        self.dev = Device()
        self.paused = False          # F10 hard pause
        self.connected = True        # updated by the USB poller
        self.buf, self.buf_lock = [], threading.Lock()
        self.down = set()            # keys currently held (de-dupe auto-repeat)
        self.mods = set()            # alt/cmd currently held → word ops
        self.word = ""               # the word being typed, for ⌥/⌘-backspace
        self.down_pos = None
        self.last_move = None
        threading.Thread(target=self._flusher, daemon=True).start()
        threading.Thread(target=self._usb_poll, daemon=True).start()
        threading.Thread(target=self._batt_poll, daemon=True).start()
        mode = "keyboard + trackpad" if MOUSE else "keyboard only"
        print(f"ARC Link · tablet {self.dev.w}x{self.dev.h} · {mode} · engages when the Pad is plugged in")
        print("F10 pause · F1 back · F2 home · F3 recents · 3-finger swipe = recents · ⌥/⌘-⌫ delete word · Ctrl-C quit"
              + ("" if MOUSE else "  (ARCLINK_MOUSE=0 disables the trackpad)"))
        print("keyboard battery → tablet chip · every 60 s while plugged in")

    def active(self):
        return self.connected and not self.paused

    # forwarding follows the USB cable
    def _usb_poll(self):
        while True:
            c = (adb_state() == "device")
            if c != self.connected:
                self.connected = c
                self.dev.center()
                print("Pad connected — keyboard ON" if c else "Pad unplugged — Mac is yours")
            time.sleep(1.5)

    # Push this Mac's battery to the tablet, so a little chip there shows the "keyboard" charge.
    # The ARC-DNA launcher listens for this broadcast and floats a ⌨ xx% overlay.
    def _batt_poll(self):
        import re
        while True:
            if self.connected:
                try:
                    out = subprocess.run(["pmset", "-g", "batt"], capture_output=True, text=True, timeout=4).stdout
                    m = re.search(r"(\d+)%", out)
                    if m:
                        pct = int(m.group(1))
                        charging = ("AC Power" in out) or (" charging" in out and "discharging" not in out)
                        subprocess.run([ADB, "shell", "am", "broadcast", "-a", "ch.architectdna.home.KBD_BATT",
                                        "--ei", "level", str(pct), "--ez", "charging", "true" if charging else "false"],
                                       capture_output=True, timeout=5)
                except Exception:
                    pass
            time.sleep(60)

    # The VNC tunnel is NOT managed here — arclink-tunnel.sh (its own LaunchAgent) owns it, so
    # updating or restarting the keyboard never drops 127.0.0.1:5900.

    # ── macOS capture: swallow events while active so they don't also hit the Mac ──
    def intercept(self, event_type, event):
        return None if self.active() else event

    # ── text buffering ──
    def _flusher(self):
        while True:
            time.sleep(FLUSH_MS / 1000); self.flush()

    def flush(self):
        with self.buf_lock:
            if not self.buf: return
            s = "".join(self.buf); self.buf.clear()
        self.dev.text(s)

    # ── keyboard ──
    def on_press(self, key):
        if key == keyboard.Key.f10:
            if key in self.down: return
            self.down.add(key)
            self.paused = not self.paused
            self.flush()
            print("PAUSED (Mac is yours)" if self.paused else "RESUMED")
            return
        if key in MODS: self.mods.add(key)
        if not self.active(): return
        # auto-repeat IS allowed now (hold backspace = many deletes, spam ! = many !) —
        # the darwin_intercept suppression already stops the old double-typing.
        ch = getattr(key, "char", None)
        if ch and ch not in ("\t", "\n", "\r"):
            if ch == " ": self.flush(); self.dev.keyevent(62); self.word = ""
            else: self.buf_append(ch); self.word += ch
            return
        code = KEYEVENT.get(key)
        if code is not None:
            if key == keyboard.Key.backspace:
                if self.mods:                       # ⌥/⌘ + backspace → delete the whole word
                    n = max(1, len(self.word)); self.flush()
                    for _ in range(n): self.dev.keyevent(67)
                    self.word = ""
                    return
                self.word = self.word[:-1]
            else:
                self.word = ""                      # enter / tab / arrows end the word
            self.flush(); self.dev.keyevent(code)

    def on_release(self, key):
        self.down.discard(key)
        self.mods.discard(key)

    def buf_append(self, ch):
        with self.buf_lock: self.buf.append(ch)

    # ── trackpad ──
    def on_move(self, x, y):
        if not self.active():
            self.last_move = (x, y); return
        if self.last_move is not None:
            dx = (x - self.last_move[0]) * MOUSE_SENS
            dy = (y - self.last_move[1]) * MOUSE_SENS
            self.dev.cx, self.dev.cy = self.dev.clamp(self.dev.cx + dx, self.dev.cy + dy)
        self.last_move = (x, y)

    def on_click(self, x, y, button, pressed):
        if not self.active(): return
        if pressed:
            self.down_pos = (self.dev.cx, self.dev.cy, time.time())
        elif self.down_pos:
            sx, sy, t0 = self.down_pos; ex, ey = self.dev.cx, self.dev.cy; self.down_pos = None
            if abs(ex - sx) + abs(ey - sy) > DRAG_THRESH:
                self.dev.swipe(sx, sy, ex, ey, max(60, int((time.time() - t0) * 1000)))
            else:
                self.dev.tap(ex, ey)

    def on_scroll(self, x, y, dx, dy):
        if not self.active(): return
        ex, ey = self.dev.cx, self.dev.cy
        if abs(dx) > abs(dy):
            self.dev.swipe(ex, ey, self.dev.clamp(ex + dx * SCROLL_STEP, ey)[0], ey, 80)
        else:
            self.dev.swipe(ex, ey, ex, self.dev.clamp(ex, ey + dy * SCROLL_STEP)[1], 80)

    def run(self):
        if MOUSE:
            mouse.Listener(on_move=self.on_move, on_click=self.on_click, on_scroll=self.on_scroll,
                           darwin_intercept=self.intercept).start()
        kl = keyboard.Listener(on_press=self.on_press, on_release=self.on_release,
                               darwin_intercept=self.intercept)
        kl.start()
        try:
            self._gestures(kl)
        except KeyboardInterrupt:
            pass
        finally:
            self.flush(); print("\nARC Link stopped.")

    def _gestures(self, kl):
        # Trackpad swipe → recents. macOS reserves 4-finger (Mission Control) and never delivers it
        # to a background monitor, so we use the 3-finger swipe, which it does deliver. Needs PyObjC;
        # without it the keyboard/mouse still run, just no gesture. Global monitors can't be consumed,
        # so the swipe also does its normal macOS thing — harmless.
        try:
            from AppKit import NSEvent, NSEventMaskSwipe
            from PyObjCTools import AppHelper
        except Exception:
            print("(3-finger-swipe→recents needs PyObjC: pip3 install pyobjc-framework-Cocoa)")
            kl.join()
            return
        last = [0.0]
        def on_swipe(e):
            if not self.active():
                return
            now = time.time()
            if now - last[0] < 0.5:
                return
            last[0] = now
            self.flush(); self.dev.keyevent(187)      # APP_SWITCH / recents
            print("gesture → recents")
        NSEvent.addGlobalMonitorForEventsMatchingMask_handler_(NSEventMaskSwipe, on_swipe)
        print("gestures on: 3-finger swipe → recents")
        AppHelper.runEventLoop()


if __name__ == "__main__":
    ArcLink().run()
