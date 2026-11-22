package ch.architectdna.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ARC Treasure, device to device. On its own Bluetooth channel (separate from the stats sync, so it
 * can never disturb it) each paired phone/computer keeps the OTHER's encrypted vault — the phone
 * backs up to the tablet and the tablet to the phone, no single device to lose. Only ciphertext
 * crosses the link; neither side can open what it holds without the passphrase. Best-effort: it runs
 * when both devices are reachable, and stays quiet otherwise.
 *   PUSH (op 1): "store my encrypted vault"   ·   PULL (op 2): "send me back the vault you hold of me"
 */
final class TreasureSync {
    private TreasureSync() { }

    private static final UUID ID = UUID.fromString("c3f9a71d-8e24-4b60-9a0f-2d7e5b1c6a40");
    private static final int MAX = 64 * 1024 * 1024;
    private static Thread serverThread;
    private static BluetoothServerSocket server;
    static volatile long lastPush;

    static File peerBak(Context c) { return new File(c.getFilesDir(), "treasure-peer.bak"); }

    private static BluetoothAdapter adapter(Context c) {
        try {
            if (c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && android.os.Build.VERSION.SDK_INT >= 31) return null;
        } catch (Throwable ignored) { }
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        return a != null && a.isEnabled() ? a : null;
    }

    private static boolean phoneOrPc(BluetoothDevice d) {
        int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
        return major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER;
    }

    // ---- server: always listening while ARC-DNA runs ----

    static synchronized void startServer(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                BluetoothAdapter a = adapter(c);
                if (a == null) { if (!sleep(60_000)) return; continue; }
                try {
                    BluetoothServerSocket s = a.listenUsingRfcommWithServiceRecord("ARC Treasure", ID);
                    synchronized (TreasureSync.class) { server = s; }
                    while (true) {
                        BluetoothSocket sock = s.accept();
                        try {
                            DataInputStream in = new DataInputStream(sock.getInputStream());
                            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                            int op = in.read();
                            if (op == 1) {                       // they push their vault → we keep it
                                byte[] blob = readBlob(in);
                                if (blob != null && blob.length > 0) writeFile(peerBak(c), blob);
                            } else if (op == 2) {                // they ask for the vault we hold of them
                                writeBlob(out, readFile(peerBak(c)));
                                out.flush();
                            }
                        } catch (Exception ignored) {
                        } finally { close(sock); }
                    }
                } catch (IOException | SecurityException e) {
                    if (!sleep(30_000)) return;
                }
            }
        }, "arc-treasure-sync");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    static synchronized void stopServer() {
        if (serverThread != null) serverThread.interrupt();
        serverThread = null;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        server = null;
    }

    // ---- push my encrypted vault to the paired devices (quiet, throttled, non-empty only) ----

    static void push(Context ctx) {
        final Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - lastPush < 20_000) return;
        lastPush = now;
        new Thread(() -> {
            byte[] mine = exportBytes(c);
            if (mine == null || mine.length == 0) return;          // never clobber a peer's copy with nothing
            BluetoothAdapter a = adapter(c);
            if (a == null) return;
            try {
                for (BluetoothDevice d : a.getBondedDevices()) {
                    if (!phoneOrPc(d)) continue;
                    BluetoothSocket sock = null;
                    try {
                        sock = d.createRfcommSocketToServiceRecord(ID);
                        sock.connect();
                        DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                        out.write(1);
                        writeBlob(out, mine);
                        out.flush();
                    } catch (Exception ignored) {
                    } finally { close(sock); }
                }
            } catch (SecurityException ignored) { }
        }, "arc-treasure-push").start();
    }

    // ---- pull my vault back from a nearby device that holds it (restore) ----

    static void pull(Context ctx, Consumer<byte[]> cb) {
        final Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            byte[] got = null;
            BluetoothAdapter a = adapter(c);
            if (a != null) {
                try {
                    for (BluetoothDevice d : a.getBondedDevices()) {
                        if (!phoneOrPc(d)) continue;
                        BluetoothSocket sock = null;
                        try {
                            sock = d.createRfcommSocketToServiceRecord(ID);
                            sock.connect();
                            DataInputStream in = new DataInputStream(sock.getInputStream());
                            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                            out.write(2);
                            out.flush();
                            byte[] blob = readBlob(in);
                            if (blob != null && blob.length > 0) { got = blob; break; }
                        } catch (Exception ignored) {
                        } finally { close(sock); }
                    }
                } catch (SecurityException ignored) { }
            }
            final byte[] r = got;
            main.post(() -> cb.accept(r));
        }, "arc-treasure-pull").start();
    }

    // ---- helpers ----

    private static byte[] exportBytes(Context c) {
        if (!Treasure.exists(c)) return null;
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Treasure.exportTo(c, o);
            return o.toByteArray();
        } catch (Exception e) { return null; }
    }

    private static void writeBlob(DataOutputStream out, byte[] b) throws IOException {
        if (b == null) b = new byte[0];
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX) return null;
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static void writeFile(File f, byte[] b) {
        try (OutputStream o = new FileOutputStream(f)) { o.write(b); } catch (Exception ignored) { }
    }

    private static byte[] readFile(File f) {
        if (!f.exists()) return new byte[0];
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) != -1; ) o.write(buf, 0, r);
            return o.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }

    static InputStream stream(byte[] b) { return new ByteArrayInputStream(b); }

    private static void close(BluetoothSocket s) { if (s != null) try { s.close(); } catch (IOException ignored) { } }

    private static boolean sleep(long ms) {
        try { Thread.sleep(ms); return true; } catch (InterruptedException e) { return false; }
    }
}
