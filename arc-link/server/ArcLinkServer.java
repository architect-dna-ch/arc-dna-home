package ch.architectdna.arclink;

import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ARC Link v2 server — the low-latency path. Launched over USB with shell privileges (no root):
 *
 *   adb push arc-link-server.jar /data/local/tmp/
 *   adb forward tcp:7799 tcp:7799
 *   adb shell CLASSPATH=/data/local/tmp/arc-link-server.jar app_process / ch.architectdna.arclink.ArcLinkServer
 *
 * It opens a socket and injects real KeyEvent / MotionEvent through the hidden
 * InputManager.injectInputEvent — so there is no per-action `input` cost, a true moving pointer,
 * and real key chords. The Mac client connects to 127.0.0.1:7799 (tunnelled over the cable) and
 * speaks the tiny protocol below. Build notes: server/README.md.
 *
 * Protocol (big-endian), one message at a time:
 *   0x01 KEY   : int action(0=down,1=up), int keyCode, int metaState
 *   0x02 TOUCH : int action(0=down,1=up,2=move), int x, int y
 *   0x03 KEYEV : int keyCode            (convenience: down+up)
 */
public final class ArcLinkServer {

    private static final int PORT = 7799;
    private static final int ASYNC = 0; // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC

    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method setSource; // InputEvent#setSource(int)

    private static long downTime = 0;

    public static void main(String[] args) throws Exception {
        // InputManager.getInstance().injectInputEvent(event, mode) — all hidden, reached by reflection.
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        inputManager = imClass.getMethod("getInstance").invoke(null);
        injectInputEvent = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        setSource = InputEvent.class.getMethod("setSource", int.class);

        ServerSocket server = new ServerSocket(PORT, 1, java.net.InetAddress.getByName("127.0.0.1"));
        System.out.println("ARC Link server listening on 127.0.0.1:" + PORT);
        while (true) {
            try (Socket s = server.accept()) {
                System.out.println("client connected");
                serve(new DataInputStream(new java.io.BufferedInputStream(s.getInputStream())));
            } catch (Exception e) {
                System.out.println("client ended: " + e.getMessage());
            }
        }
    }

    private static void serve(DataInputStream in) throws Exception {
        while (true) {
            int op = in.read();
            if (op < 0) return;
            switch (op) {
                case 0x01: key(in.readInt(), in.readInt(), in.readInt()); break;
                case 0x02: touch(in.readInt(), in.readInt(), in.readInt()); break;
                case 0x03: { int c = in.readInt(); key(0, c, 0); key(1, c, 0); break; }
                default: return; // unknown op: drop the connection rather than desync
            }
        }
    }

    private static void key(int action, int keyCode, int meta) {
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now,
                action == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, meta, KeyEvent.ANY_HID_USAGE, 0,
                KeyEvent.FLAG_FROM_SYSTEM, 0x101 /* SOURCE_KEYBOARD */);
        inject(ev);
    }

    private static void touch(int action, int x, int y) {
        long now = SystemClock.uptimeMillis();
        int a;
        switch (action) {
            case 0: a = MotionEvent.ACTION_DOWN; downTime = now; break;
            case 1: a = MotionEvent.ACTION_UP; break;
            default: a = MotionEvent.ACTION_MOVE; break;
        }
        MotionEvent ev = MotionEvent.obtain(downTime == 0 ? now : downTime, now, a, x, y, 1f, 1f,
                0, 1f, 1f, 0, 0);
        try { setSource.invoke(ev, 0x1002 /* SOURCE_TOUCHSCREEN */); } catch (Exception ignored) {}
        inject(ev);
        ev.recycle();
    }

    private static void inject(InputEvent ev) {
        try { injectInputEvent.invoke(inputManager, ev, ASYNC); }
        catch (Exception e) { System.out.println("inject failed: " + e.getMessage()); }
    }

    private ArcLinkServer() {}
}
