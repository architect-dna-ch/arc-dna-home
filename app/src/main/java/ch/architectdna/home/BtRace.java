package ch.architectdna.home;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A live, persistent Bluetooth link for the fox race (separate from BtSync's one-shot exchange).
 * One device hosts (listens), the other joins (connects). Then both stream newline-delimited
 * messages — "START <seed>", "D <dist>", "X <dist>", "BYE" — over the open RFCOMM socket, so
 * the race is real-time and local, no internet. Callbacks arrive on the main thread.
 */
final class BtRace {

    private static final UUID ID = UUID.fromString("5b2e0d4a-7c91-4f3b-8a1e-6d0c9f2a3b77");

    interface Listener {
        void onConnected(String partnerName);
        void onMessage(String line);
        void onClosed(String why);
    }

    private static volatile BluetoothSocket socket;
    private static volatile BluetoothServerSocket serverSocket;
    private static volatile OutputStream out;
    private static Thread thread;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BtRace() {
    }

    private static BluetoothAdapter adapter(Context c) {
        if (Build.VERSION.SDK_INT >= 31
            && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null;
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        return (a != null && a.isEnabled()) ? a : null;
    }

    /** Paired phones / computers / tablets the partner could be on, best-first. */
    static List<BluetoothDevice> partners(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        if (a == null) return out;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                if (major == android.bluetooth.BluetoothClass.Device.Major.PHONE
                    || major == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
                    || major == android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED) out.add(d);
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return "?";
        }
    }

    static void host(Context ctx, Listener l) {
        close();
        Context c = ctx.getApplicationContext();
        thread = new Thread(() -> {
            try {
                BluetoothAdapter a = adapter(c);
                if (a == null) { fail(l, "Bluetooth aus"); return; }
                serverSocket = a.listenUsingRfcommWithServiceRecord("ARC-DNA Race", ID);
                BluetoothSocket s = serverSocket.accept();
                try { serverSocket.close(); } catch (IOException ignored) {}
                serverSocket = null;
                run(s, l);
            } catch (IOException | SecurityException e) {
                fail(l, e.getMessage());
            }
        }, "arc-bt-race-host");
        thread.setDaemon(true);
        thread.start();
    }

    static void join(Context ctx, BluetoothDevice d, Listener l) {
        close();
        thread = new Thread(() -> {
            try {
                BluetoothSocket s = d.createRfcommSocketToServiceRecord(ID);
                s.connect();
                run(s, l);
            } catch (IOException | SecurityException e) {
                fail(l, e.getMessage());
            }
        }, "arc-bt-race-join");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(BluetoothSocket s, Listener l) {
        socket = s;
        String name;
        try { name = safeName(s.getRemoteDevice()); } catch (Exception e) { name = "Partner"; }
        final String n = name;
        try {
            out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            MAIN.post(() -> l.onConnected(n));
            String line;
            while ((line = in.readLine()) != null) {
                final String ln = line;
                MAIN.post(() -> l.onMessage(ln));
            }
            fail(l, "Verbindung beendet");
        } catch (IOException | SecurityException e) {
            fail(l, e.getMessage());
        } finally {
            close();
        }
    }

    static synchronized void send(String line) {
        OutputStream o = out;
        if (o == null) return;
        try {
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException ignored) {
        }
    }

    private static void fail(Listener l, String why) {
        MAIN.post(() -> l.onClosed(why == null ? "getrennt" : why));
    }

    static synchronized void close() {
        OutputStream o = out; out = null;
        if (o != null) try { o.close(); } catch (IOException ignored) {}
        BluetoothSocket s = socket; socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        BluetoothServerSocket ss = serverSocket; serverSocket = null;
        if (ss != null) try { ss.close(); } catch (IOException ignored) {}
    }
}
