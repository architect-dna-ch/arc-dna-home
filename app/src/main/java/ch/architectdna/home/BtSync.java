package ch.architectdna.home;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Music stats shared between the user's own devices (phone ↔ tablet) over Bluetooth, no internet:
 * every device with ARC-DNA listens on a secure RFCOMM channel (paired devices only); now and then
 * one connects to the other and both swap a snapshot of their last 30 days (minutes, titles,
 * artists, played tracks). The partner's snapshot is kept apart ("peer") and added on display.
 */
final class BtSync {

    private static final UUID ID = UUID.fromString("6a1c7d2e-4f0b-4e7a-9b3c-a7d9c0f1e5b2");
    private static final int MAX_BYTES = 2_000_000;
    private static BluetoothServerSocket server;
    private static Thread serverThread;
    private static volatile boolean busy;
    private static long lastTry;
    /** One line for the diagnosis header: what the sync is doing / last did. */
    static volatile String status = "noch nicht gestartet";

    /** ARC·Tap: a theme id and/or buddy index to hand the partner on the next exchange (one-shot). */
    private static volatile String pendingShare;

    static void queueShare(String themeId, int buddyIdx) {
        pendingShare = (themeId == null ? "" : themeId) + "|" + buddyIdx;
    }

    private static void log(String s) {
        status = s;
        DiagLog.log("bt sync: " + s);
    }

    private BtSync() {
    }

    static SharedPreferences peer(Context c) {
        return c.getSharedPreferences("peer", Context.MODE_PRIVATE);
    }

    static File peerHistory(Context c) {
        return new File(c.getFilesDir(), "tracks_peer.log");
    }

    /** The partner's name if its data is from the last two days, else null. */
    static String peerName(Context c) {
        SharedPreferences p = peer(c);
        return System.currentTimeMillis() - p.getLong("at", 0) < 2 * 86_400_000L ? p.getString("name", null) : null;
    }

    private static String why = "";

    private static BluetoothAdapter adapter(Context c) {
        if (Build.VERSION.SDK_INT >= 31
            && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            why = "keine Bluetooth-Berechtigung";
            return null;
        }
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        if (a == null || !a.isEnabled()) {
            why = "Bluetooth aus";
            return null;
        }
        return a;
    }

    /* ---------------- server: always on while the media listener runs ---------------- */

    static synchronized void startServer(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                BluetoothAdapter a = adapter(c);
                if (a == null) {
                    log("Empfang wartet: " + why);
                    if (!sleep(60_000)) return;
                    continue;
                }
                try {
                    BluetoothServerSocket s = a.listenUsingRfcommWithServiceRecord("ARC-DNA Sync", ID);
                    synchronized (BtSync.class) {
                        server = s;
                    }
                    log("Empfang bereit (wartet auf Partner)");
                    while (true) {
                        BluetoothSocket sock = s.accept();
                        try {
                            busy = true;
                            DataInputStream in = new DataInputStream(sock.getInputStream());
                            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                            String theirs = read(in);
                            write(out, snapshot(c));
                            store(c, theirs, sock.getRemoteDevice());
                            log("Abgleich empfangen von " + safeName(sock.getRemoteDevice()) + " (" + theirs.length() / 1024 + " kB)");
                        } catch (IOException | SecurityException e) {
                            log("Empfang abgebrochen: " + e.getMessage());
                        } finally {
                            busy = false;
                            closeQuietly(sock);
                        }
                    }
                } catch (IOException | SecurityException e) {
                    log("Empfang gestoppt: " + e.getMessage() + " – neuer Versuch in 30 s");
                    if (!sleep(30_000)) return;    // adapter off or socket closed: try again later
                }
            }
        }, "arc-bt-sync");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    static synchronized void stopServer() {
        if (serverThread != null) serverThread.interrupt();
        serverThread = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        server = null;
    }

    /* ---------------- client: try the paired devices, the last partner first ---------------- */

    /** Syncs in the background unless one ran in the last few minutes; done(name or null) on the main thread. */
    static void syncSoon(Context ctx, long minGapMs, java.util.function.Consumer<String> done) {
        Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (busy || now - lastTry < minGapMs) return;
        lastTry = now;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String got = null;
            BluetoothAdapter a = adapter(c);
            if (a == null) log("Abgleich nicht möglich: " + why);
            if (a != null) {
                try {
                    // Only a speed-up (a running scan slows connecting); needs BLUETOOTH_SCAN since Android 12,
                    // which ARC-DNA doesn't hold: it threw and stopped every sync before it started.
                    try {
                        if (a.isDiscovering()) a.cancelDiscovery();
                    } catch (SecurityException ignored) {
                    }
                    // Every paired device that could run ARC-DNA: the last partner first, then phones and
                    // computers/tablets, then the uncategorised ones; headphones, watches, keyboards … left out.
                    List<BluetoothDevice> first = new ArrayList<>(), later = new ArrayList<>();
                    String last = peer(c).getString("addr", "");
                    StringBuilder seen = new StringBuilder();
                    for (BluetoothDevice d : a.getBondedDevices()) {
                        int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                        seen.append(safeName(d)).append('(').append(Integer.toHexString(major)).append(") ");
                        boolean phoneOrPc = major == android.bluetooth.BluetoothClass.Device.Major.PHONE
                            || major == android.bluetooth.BluetoothClass.Device.Major.COMPUTER;
                        boolean accessory = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
                            || major == android.bluetooth.BluetoothClass.Device.Major.WEARABLE
                            || major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
                            || major == android.bluetooth.BluetoothClass.Device.Major.HEALTH
                            || major == android.bluetooth.BluetoothClass.Device.Major.TOY
                            || major == android.bluetooth.BluetoothClass.Device.Major.IMAGING;
                        if (d.getAddress().equals(last)) first.add(0, d);
                        else if (phoneOrPc) first.add(d);
                        else if (!accessory) later.add(d);
                    }
                    first.addAll(later);
                    log("gekoppelt: " + seen.toString().trim() + " → probiere " + first.size());
                    for (BluetoothDevice d : first) {
                        if (exchange(c, d)) {
                            got = peer(c).getString("name", safeName(d));
                            break;
                        }
                    }
                    if (got == null && !first.isEmpty()) log("kein Partner antwortet – läuft ARC-DNA auf dem anderen Gerät (Home geöffnet)?");
                } catch (SecurityException e) {
                    log("Abgleich verweigert: " + e.getMessage());
                }
            }
            String result = got;
            if (done != null) main.post(() -> done.accept(result));
        }, "arc-bt-sync-client").start();
    }

    private static boolean exchange(Context c, BluetoothDevice d) {
        BluetoothSocket sock = null;
        busy = true;
        try {
            long t0 = System.currentTimeMillis();
            sock = d.createRfcommSocketToServiceRecord(ID);
            sock.connect();
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());
            write(out, snapshot(c));
            store(c, read(in), d);
            log("abgeglichen mit " + safeName(d) + " in " + (System.currentTimeMillis() - t0) + " ms");
            return true;
        } catch (IOException | SecurityException e) {
            log(safeName(d) + ": " + e.getMessage());
            return false;
        } finally {
            busy = false;
            closeQuietly(sock);
        }
    }

    /* ---------------- snapshot format: tab-separated lines ---------------- */

    private static String snapshot(Context c) {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        StringBuilder b = new StringBuilder();
        b.append("N\t").append(Build.MODEL.replace('\t', ' ')).append('\n');
        Calendar cal = Calendar.getInstance();
        long running = p.getLong("msPlayStart", 0);
        String today = MusicStats.day(System.currentTimeMillis());
        for (int i = 0; i < 30; i++) {
            String d = MusicStats.day(cal.getTimeInMillis());
            long ms = p.getLong("musicMs:" + d, 0);
            if (d.equals(today) && running > 0) ms += Math.max(0, System.currentTimeMillis() - running);
            int tracks = p.getInt("musicTracks:" + d, 0);
            if (ms > 0 || tracks > 0) b.append("D\t").append(d).append('\t').append(ms).append('\t').append(tracks).append('\n');
            String artists = p.getString("musicArtists:" + d, "");
            if (i < 7 && !artists.isEmpty()) {
                for (String line : artists.split("\n")) if (!line.isEmpty()) b.append("A\t").append(d).append('\t').append(line).append('\n');
            }
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        long since = System.currentTimeMillis() - 30L * 86_400_000L;
        for (String[] h : MusicStats.ownHistory(c)) {
            try {
                if (Long.parseLong(h[0]) < since) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            b.append("H\t").append(String.join("\t", h)).append('\n');
        }
        String share = pendingShare;
        if (share != null) {               // ARC·Tap payload, sent once then cleared
            b.append("S\t").append(share.replace('\t', ' ').replace('\n', ' ')).append('\n');
            pendingShare = null;
        }
        return b.toString();
    }

    private static synchronized void store(Context c, String snap, BluetoothDevice from) {
        SharedPreferences.Editor e = peer(c).edit().clear();
        StringBuilder hist = new StringBuilder();
        java.util.Map<String, StringBuilder> artists = new java.util.HashMap<>();
        String name = null;
        for (String line : snap.split("\n")) {
            String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("N") && f.length >= 2) name = f[1];
                else if (f[0].equals("D") && f.length >= 4) {
                    e.putLong("musicMs:" + f[1], Long.parseLong(f[2]));
                    e.putInt("musicTracks:" + f[1], Integer.parseInt(f[3]));
                } else if (f[0].equals("A") && f.length >= 4) {
                    artists.computeIfAbsent(f[1], k -> new StringBuilder()).append(f[2]).append('\t').append(f[3]).append('\n');
                } else if (f[0].equals("H") && f.length >= 5) {
                    hist.append(f[1]).append('\t').append(f[2]).append('\t').append(f[3]).append('\t').append(f[4]).append('\n');
                } else if (f[0].equals("S") && f.length >= 2) {        // ARC·Tap: partner shared a theme/buddy
                    c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                        .putString("arctapIn", f[1]).putLong("arctapInAt", System.currentTimeMillis()).apply();
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (java.util.Map.Entry<String, StringBuilder> en : artists.entrySet()) e.putString("musicArtists:" + en.getKey(), en.getValue().toString());
        String devName;
        try {
            devName = from.getName();
        } catch (SecurityException ex) {
            devName = null;
        }
        e.putString("name", name != null ? name : devName != null ? devName : "Partner");
        e.putString("addr", from.getAddress());
        e.putLong("at", System.currentTimeMillis());
        e.apply();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(peerHistory(c), false)) {
            out.write(hist.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static void write(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    private static String read(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_BYTES) throw new IOException("bad size");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return "?";
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
