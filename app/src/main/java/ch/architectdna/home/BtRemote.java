package ch.architectdna.home;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ARC Remote: one device (the phone in your hand) steers the music on another (the tablet with
 * the good speakers). A Bluetooth RFCOMM link stays open while the remote screen is up, so a tap is
 * one small frame, no internet and no cloud in between: typically 20 to 60 ms.
 *
 * The tablet side runs inside MediaListener (bound by the system for as long as notification access
 * is on), so nothing needs to be opened on the tablet. It drives whatever media session is active
 * there, YouTube Music first, and pushes the state back on every change.
 *
 * Frames: int length + UTF-8 text. Commands "NAME\targ"; state "STATE\t…", "ART\tbase64", "QUEUE\t…".
 */
final class BtRemote {

    static final UUID ID = UUID.fromString("b7e2c1a4-3d5f-4a8e-9c61-2f0d8e4b7a93");
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    private static final int MAX_FRAME = 512 * 1024;

    private BtRemote() { }

    static BluetoothAdapter adapter(Context c) {
        if (Build.VERSION.SDK_INT >= 31
            && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null;
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        return a != null && a.isEnabled() ? a : null;
    }

    /* ================= one open link, used on both sides ================= */

    interface Handler2 {
        void onFrame(String msg);
        void onClosed();
    }

    static final class Link {
        private final BluetoothSocket sock;
        private final DataOutputStream out;
        private final DataInputStream in;
        private final ExecutorService writer = Executors.newSingleThreadExecutor();
        private volatile boolean closed;
        final String peerName;

        Link(BluetoothSocket s) throws IOException {
            sock = s;
            out = new DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream(), 8192));
            in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 8192));
            String n;
            try { n = s.getRemoteDevice().getName(); } catch (SecurityException e) { n = null; }
            peerName = n == null ? "Gerät" : n;
        }

        void readLoop(Handler2 h) {
            Thread t = new Thread(() -> {
                try {
                    while (!closed) {
                        int len = in.readInt();
                        if (len < 0 || len > MAX_FRAME) throw new IOException("bad frame");
                        byte[] b = new byte[len];
                        in.readFully(b);
                        h.onFrame(new String(b, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                } finally {
                    close();
                    h.onClosed();
                }
            }, "arc-remote-read");
            t.setDaemon(true);
            t.start();
        }

        void send(String msg) {
            if (closed) return;
            try {
                writer.execute(() -> {
                    try {
                        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                        out.writeInt(b.length);
                        out.write(b);
                        out.flush();
                    } catch (IOException e) {
                        close();
                    }
                });
            } catch (RuntimeException ignored) { }
        }

        boolean isOpen() { return !closed; }

        void close() {
            if (closed) return;
            closed = true;
            writer.shutdownNow();
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    /* ================= client: the phone ================= */

    /** Connects to the given device, or tries the last partner, then other paired phones/tablets. Blocking. */
    static Link connect(Context c, String preferredAddr) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return null;
        try {
            try { if (a.isDiscovering()) a.cancelDiscovery(); } catch (SecurityException ignored) { }
            java.util.List<BluetoothDevice> order = new java.util.ArrayList<>(), later = new java.util.ArrayList<>();
            for (BluetoothDevice d : a.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                boolean accessory = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
                    || major == android.bluetooth.BluetoothClass.Device.Major.WEARABLE
                    || major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
                    || major == android.bluetooth.BluetoothClass.Device.Major.HEALTH;
                if (d.getAddress().equals(preferredAddr)) order.add(0, d);
                else if (!accessory) later.add(d);
            }
            order.addAll(later);
            for (BluetoothDevice d : order) {
                BluetoothSocket s = null;
                try {
                    s = d.createRfcommSocketToServiceRecord(ID);
                    s.connect();
                    c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                        .putString("remoteAddr", d.getAddress()).apply();
                    return new Link(s);
                } catch (IOException e) {
                    if (s != null) try { s.close(); } catch (IOException ignored) { }
                }
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    /* ================= server: the tablet ================= */

    private static Thread serverThread;
    private static volatile BluetoothServerSocket server;
    private static volatile Link current;
    private static Tablet tablet;

    static synchronized void startServer(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("remoteServer", true)) return;
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                BluetoothAdapter a = adapter(c);
                if (a == null) { if (!sleep(60_000)) return; continue; }
                try {
                    BluetoothServerSocket s = a.listenUsingRfcommWithServiceRecord("ARC Remote", ID);
                    server = s;
                    while (true) {
                        BluetoothSocket sock = s.accept();
                        Link old = current;
                        if (old != null) old.close();
                        Link l = new Link(sock);
                        current = l;
                        DiagLog.log("remote: " + l.peerName + " verbunden");
                        new Handler(Looper.getMainLooper()).post(() -> tablet(c).attach(l));
                    }
                } catch (IOException | SecurityException e) {
                    if (!sleep(15_000)) return;
                }
            }
        }, "arc-remote-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    static synchronized void stopServer() {
        if (serverThread != null) serverThread.interrupt();
        serverThread = null;
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        Link l = current;
        current = null;
        if (l != null) l.close();
        if (tablet != null) tablet.detach();
    }

    private static Tablet tablet(Context c) {
        if (tablet == null) tablet = new Tablet(c);
        return tablet;
    }

    private static boolean sleep(long ms) {
        try { Thread.sleep(ms); return true; } catch (InterruptedException e) { return false; }
    }

    /** The tablet side: runs commands on the active media session and reports its state. Main thread. */
    private static final class Tablet implements Handler2 {
        private final Context c;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final MediaSessionManager sessions;
        private final AudioManager audio;
        private final ComponentName me;
        private MediaController ctl;
        private Link link;
        private String artKey = "";

        private final MediaSessionManager.OnActiveSessionsChangedListener changed = l -> repick();
        private final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState s) { pushSoon(); }
            @Override public void onMetadataChanged(MediaMetadata m) { pushSoon(); }
            @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { pushQueue(); }
            @Override public void onSessionDestroyed() { repick(); }
        };
        private final Runnable push = this::pushState;
        private final Runnable heartbeat = new Runnable() {
            @Override public void run() {
                if (link == null || !link.isOpen()) return;
                pushState();
                main.postDelayed(this, 5_000);
            }
        };

        Tablet(Context c) {
            this.c = c;
            sessions = c.getSystemService(MediaSessionManager.class);
            audio = c.getSystemService(AudioManager.class);
            me = new ComponentName(c, MediaListener.class);
        }

        void attach(Link l) {
            link = l;
            try { sessions.addOnActiveSessionsChangedListener(changed, me, main); } catch (RuntimeException ignored) { }
            repick();
            l.readLoop(this);
            pushQueue();
            main.removeCallbacks(heartbeat);
            main.postDelayed(heartbeat, 5_000);
        }

        void detach() {
            main.removeCallbacks(heartbeat);
            try { sessions.removeOnActiveSessionsChangedListener(changed); } catch (RuntimeException ignored) { }
            if (ctl != null) try { ctl.unregisterCallback(cb); } catch (RuntimeException ignored) { }
            ctl = null;
            link = null;
            artKey = "";
        }

        @Override public void onFrame(String msg) { main.post(() -> run(msg)); }

        @Override public void onClosed() { main.post(() -> { if (link != null && !link.isOpen()) detach(); }); }

        private void repick() {
            MediaController best = null;
            try {
                List<MediaController> list = sessions.getActiveSessions(me);
                for (MediaController m : list) {
                    PlaybackState s = m.getPlaybackState();
                    boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
                    if (best == null || playing && !isPlaying(best) || !isPlaying(best) && YT_MUSIC.equals(m.getPackageName()) && !YT_MUSIC.equals(best.getPackageName()))
                        best = m;
                }
            } catch (SecurityException ignored) { }
            if (best != null && ctl != null && best.getSessionToken().equals(ctl.getSessionToken())) { pushSoon(); return; }
            if (ctl != null) try { ctl.unregisterCallback(cb); } catch (RuntimeException ignored) { }
            ctl = best;
            artKey = "";
            if (ctl != null) ctl.registerCallback(cb, main);
            pushSoon();
            pushQueue();
        }

        private static boolean isPlaying(MediaController m) {
            PlaybackState s = m.getPlaybackState();
            return s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }

        private void pushSoon() {
            main.removeCallbacks(push);
            main.postDelayed(push, 30);
        }

        private void pushState() {
            Link l = link;
            if (l == null) return;
            int vol = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audio == null ? 15 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (ctl == null) {
                l.send("STATE\t\t\t\t0\t0\t0\t1\t" + vol + "\t" + max + "\t");
                return;
            }
            MediaMetadata md = ctl.getMetadata();
            PlaybackState s = ctl.getPlaybackState();
            String title = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            long pos = 0;
            float speed = 1f;
            if (s != null) {
                pos = s.getPosition();
                speed = s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
                if (playing && s.getLastPositionUpdateTime() > 0)
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * speed);
                if (dur > 0) pos = Math.min(pos, dur);
            }
            String app;
            try {
                PackageManager pm = c.getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(ctl.getPackageName(), 0)).toString();
            } catch (Exception e) { app = ctl.getPackageName(); }
            l.send("STATE\t" + clean(title) + "\t" + clean(artist) + "\t" + clean(app) + "\t" + (playing ? 1 : 0)
                + "\t" + Math.max(0, pos) + "\t" + Math.max(0, dur) + "\t" + speed + "\t" + vol + "\t" + max + "\t" + ctl.getPackageName());
            String key = title + "|" + artist;
            if (!key.equals(artKey) && md != null) {
                artKey = key;
                Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (art != null) {
                    Bitmap b = art;
                    new Thread(() -> {
                        try {
                            Bitmap small = Bitmap.createScaledBitmap(b, 320, Math.max(1, 320 * b.getHeight() / Math.max(1, b.getWidth())), true);
                            ByteArrayOutputStream o = new ByteArrayOutputStream();
                            small.compress(Bitmap.CompressFormat.JPEG, 78, o);
                            l.send("ART\t" + key + "\t" + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP));
                        } catch (RuntimeException ignored) { }
                    }, "arc-remote-art").start();
                } else {
                    l.send("ART\t" + key + "\t");
                }
            }
        }

        private void pushQueue() {
            Link l = link;
            if (l == null) return;
            StringBuilder b = new StringBuilder("QUEUE");
            List<MediaSession.QueueItem> q = ctl == null ? null : ctl.getQueue();
            if (q != null) {
                int n = 0;
                for (MediaSession.QueueItem it : q) {
                    if (n++ >= 40) break;
                    CharSequence t = it.getDescription().getTitle(), sub = it.getDescription().getSubtitle();
                    b.append('\t').append(it.getQueueId()).append('|').append(clean(t == null ? "" : t.toString()).replace('|', '/'))
                        .append('|').append(clean(sub == null ? "" : sub.toString()).replace('|', '/'));
                }
            }
            l.send(b.toString());
        }

        private void run(String msg) {
            String[] p = msg.split("\t", 2);
            String cmd = p[0], arg = p.length > 1 ? p[1] : "";
            if (cmd.equals("HELLO")) { artKey = ""; repick(); return; }
            if (cmd.equals("VOL") || cmd.equals("VOLSTEP")) {
                if (audio == null) return;
                try {
                    if (cmd.equals("VOL")) audio.setStreamVolume(AudioManager.STREAM_MUSIC, Integer.parseInt(arg), 0);
                    else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, arg.startsWith("-") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE, 0);
                } catch (RuntimeException ignored) { }
                pushSoon();
                return;
            }
            if (cmd.equals("SEARCH")) { search(arg); return; }
            if (ctl == null) { repick(); if (ctl == null) { if (cmd.equals("PLAY") || cmd.equals("TOGGLE")) startApp(); return; } }
            MediaController.TransportControls t = ctl.getTransportControls();
            try {
                switch (cmd) {
                    case "TOGGLE": if (isPlaying(ctl)) t.pause(); else t.play(); break;
                    case "PLAY": t.play(); break;
                    case "PAUSE": t.pause(); break;
                    case "NEXT": t.skipToNext(); break;
                    case "PREV": t.skipToPrevious(); break;
                    case "SEEK": t.seekTo(Long.parseLong(arg)); break;
                    case "QITEM": t.skipToQueueItem(Long.parseLong(arg)); break;
                    default: break;
                }
            } catch (RuntimeException ignored) { }
            pushSoon();
        }

        /** Play a song by name, in the tablet's music app. Uses the open session when there is one. */
        private void search(String q) {
            if (q.isEmpty()) return;
            MediaController target = ctl;
            if (target != null && !YT_MUSIC.equals(target.getPackageName())) {
                try {
                    for (MediaController m : sessions.getActiveSessions(me))
                        if (YT_MUSIC.equals(m.getPackageName())) { target = m; break; }
                } catch (SecurityException ignored) { }
            }
            if (target != null) {
                try { target.getTransportControls().playFromSearch(q, new android.os.Bundle()); return; }
                catch (RuntimeException ignored) { }
            }
            Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .setPackage(YT_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); }
            catch (RuntimeException e) { if (link != null) link.send("NOTE\tYouTube Music einmal auf dem Tablet öffnen, dann klappt's."); }
        }

        private void startApp() {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(YT_MUSIC);
            try {
                if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else if (link != null) link.send("NOTE\tKeine Musik-App aktiv auf dem Tablet.");
            } catch (RuntimeException e) {
                if (link != null) link.send("NOTE\tYouTube Music einmal auf dem Tablet öffnen, dann klappt's.");
            }
        }

        private static String nz(String s) { return s == null ? "" : s; }
        private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' '); }
    }
}
