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
            peerName = n == null ? L.t("Gerät") : n;
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

    /** From the ARC Headphones screen: ask the paired ARC device to connect (or free) an audio device
     *  to itself. The target must already be paired with those headphones — Android can't make another
     *  device discover or pair a new one remotely. Returns false if no ARC device answered. Blocking. */
    static boolean sendAudio(Context c, boolean on, String mac) {
        Link l = connect(c, null);
        if (l == null) return false;
        try { l.send("AUDIO\t" + (on ? "connect" : "disconnect") + "\t" + mac); Thread.sleep(700); }
        catch (Exception ignored) { }
        l.close();
        return true;
    }

    /** ARC Finder: ring the paired ARC device so you can hear where it is. Returns false if none answered. */
    static boolean sendFind(Context c) { return sendFind(c, null); }

    /** Ring a specific device (the one picked in ARC Finder) — falls back to the usual order if it doesn't answer. */
    static boolean sendFind(Context c, String addr) {
        Link l = connect(c, addr);
        if (l == null) return false;
        try { l.send("FIND\t"); Thread.sleep(500); } catch (Exception ignored) { }
        l.close();
        return true;
    }

    /** ARC Flow: ask the paired ARC device what it's playing. If it IS playing and {@code pausePeer},
     *  pause it there; either way return {title, artist, positionMs} so this device can continue the same
     *  track. null if nothing to pull (no peer, or the peer isn't playing). Blocking — off the main thread. */
    static String[] pullFromPeer(Context c) { return pullFromPeer(c, true); }

    static String[] pullFromPeer(Context c, boolean pausePeer) {
        Link l = connect(c, null);
        if (l == null) return null;
        final String[] reply = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        l.readLoop(new Handler2() {
            @Override public void onFrame(String m) { if (m.startsWith("NP")) { reply[0] = m; latch.countDown(); } }
            @Override public void onClosed() { latch.countDown(); }
        });
        l.send("NP?\t");
        try { latch.await(1800, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        String np = reply[0];
        if (np == null) { l.close(); return null; }
        String[] f = np.split("\t", -1);                 // NP \t title \t artist \t pos \t playing
        if (f.length >= 5 && "1".equals(f[4]) && !f[1].isEmpty()) {
            if (pausePeer) {
                l.send("PAUSE\t");
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            }
            l.close();
            return new String[]{f[1], f[2], f[3]};
        }
        l.close();
        return null;
    }

    /** ARC Sync: fire-and-forget frames at the paired ARC device over a one-shot link (position nudges,
     *  duck commands, a track to start). Blocking — call off the main thread. */
    static void sendToPeer(Context c, String... frames) {
        Link l = connect(c, null);
        if (l == null) return;
        for (String f : frames) l.send(f);
        try { Thread.sleep(180); } catch (InterruptedException ignored) { }
        l.close();
    }

    /** Connects to the given device, or the last partner, then other paired phones/tablets.
     *  RFCOMM connect is flaky by nature, so we put the remembered ARC device first, try a secure then an
     *  insecure socket for each, and make two passes with a short backoff before giving up. Blocking. */
    static Link connect(Context c, String preferredAddr) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return null;
        String last = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("remoteAddr", null);
        try {
            try { if (a.isDiscovering()) a.cancelDiscovery(); } catch (SecurityException ignored) { }
            java.util.List<BluetoothDevice> order = new java.util.ArrayList<>(), later = new java.util.ArrayList<>();
            for (BluetoothDevice d : a.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                boolean accessory = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
                    || major == android.bluetooth.BluetoothClass.Device.Major.WEARABLE
                    || major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
                    || major == android.bluetooth.BluetoothClass.Device.Major.HEALTH;
                String addr = d.getAddress();
                if (addr.equals(preferredAddr)) order.add(0, d);          // explicit pick wins
                else if (addr.equals(last)) order.add(0, d);              // then the last ARC partner
                else if (!accessory) later.add(d);
            }
            order.addAll(later);
            for (int pass = 0; pass < 2; pass++) {
                for (BluetoothDevice d : order) {
                    Link l = tryConnect(c, a, d);
                    if (l != null) {
                        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("remoteAddr", d.getAddress()).apply();
                        return l;
                    }
                }
                try { Thread.sleep(350); } catch (InterruptedException ignored) { return null; }
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    /** One device: a secure RFCOMM socket, then an insecure one (often connects when the secure one won't). */
    private static Link tryConnect(Context c, BluetoothAdapter a, BluetoothDevice d) {
        try { if (a.isDiscovering()) a.cancelDiscovery(); } catch (SecurityException ignored) { }
        BluetoothSocket s = null;
        try {
            s = d.createRfcommSocketToServiceRecord(ID);
            s.connect();
            return new Link(s);
        } catch (IOException | SecurityException e) {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }
        s = null;
        try {
            s = d.createInsecureRfcommSocketToServiceRecord(ID);
            s.connect();
            return new Link(s);
        } catch (IOException | SecurityException e) {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }
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

        /** The other ARC device asked us to connect/free a headphone to THIS device. Only works if it
         *  is already paired here — we can't discover or pair a new one on command. */
        private void audioCmd(String arg) {
            String[] q = arg.split("\t", 2);
            final boolean on = "connect".equals(q[0]);
            final String mac = q.length > 1 ? q[1] : "";
            if (mac.isEmpty()) return;
            final BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return;
            final BluetoothDevice dev;
            try { dev = a.getRemoteDevice(mac); } catch (Exception e) { return; }
            try {
                a.getProfileProxy(c, new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile p) {
                        try {
                            java.lang.reflect.Method m = p.getClass().getMethod(on ? "connect" : "disconnect", BluetoothDevice.class);
                            m.setAccessible(true); m.invoke(p, dev);
                        } catch (Exception ignored) { }
                        try { a.closeProfileProxy(profile, p); } catch (Exception ignored) { }
                        if (link != null) link.send("NOTE\t" + (on ? L.t("Kopfhörer hierher geholt.") : L.t("Kopfhörer freigegeben.")));
                    }
                    @Override public void onServiceDisconnected(int profile) { }
                }, android.bluetooth.BluetoothProfile.A2DP);
            } catch (Exception ignored) { }
        }

        /** ARC Flow: the other device (being picked up) asked what we're playing. Report this device's
         *  current track so it can continue there; it pauses us with a follow-up PAUSE if it takes over. */
        private void replyNowPlaying() {
            if (link == null) return;
            String[] n = LocalMedia.nowPlaying(c);
            if (n == null) { link.send("NP\t\t\t0\t0"); return; }
            link.send("NP\t" + clean(n[0]) + "\t" + clean(n[1]) + "\t" + n[2] + "\t" + n[3]);
        }

        /** The other ARC device asked us to make noise so it can be found. Loud alarm + a buzz, ~20 s. */
        private void findMe() {
            try {
                android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
                if (am != null) try { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0); } catch (Exception ignored) { }
                android.net.Uri u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
                if (u == null) u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
                final android.media.Ringtone rt = u == null ? null : android.media.RingtoneManager.getRingtone(c, u);
                if (rt != null) {
                    try { rt.setStreamType(android.media.AudioManager.STREAM_ALARM); } catch (Exception ignored) { }
                    rt.play();
                    main.postDelayed(() -> { try { rt.stop(); } catch (Exception ignored) { } }, 20000);
                }
                android.os.Vibrator vb = c.getSystemService(android.os.Vibrator.class);
                if (vb != null && vb.hasVibrator()) {
                    long[] pat = {0, 500, 300, 500, 300, 500, 300, 500};
                    if (android.os.Build.VERSION.SDK_INT >= 26) vb.vibrate(android.os.VibrationEffect.createWaveform(pat, -1));
                    else vb.vibrate(pat, -1);
                }
            } catch (Exception ignored) { }
            if (link != null) link.send("NOTE\t" + L.t("Klingelt – hörst du mich?"));
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
            if (cmd.equals("AUDIO")) { audioCmd(arg); return; }
            if (cmd.equals("FIND")) { findMe(); return; }
            if (cmd.equals("GOAL")) { GoalRelay.deliver(c, arg); return; }   // ARC One goal mirror
            if (cmd.equals("NP?")) { replyNowPlaying(); return; }
            if (cmd.equals("SYNC")) {                      // play the same track here (ARC Sync follower)
                String[] q = arg.split("\t", -1);
                if (q.length >= 3) LocalMedia.playHere(c, q[0], q[1], plong(q[2]));
                FlowService.notePeerAssert(); FlowService.followSync(c); return;
            }
            if (cmd.equals("SYNCOFF")) { FlowService.stopSyncLocal(c); return; }
            if (cmd.equals("SYNCJOIN")) { FlowService.notePeerAssert(); FlowService.followSync(c); return; }
            if (cmd.equals("SYNCPOS")) { LocalMedia.alignTo(c, plong(arg)); FlowService.notePeerAssert(); return; }
            if (cmd.equals("DUCK")) { LocalMedia.duck(c, "1".equals(arg)); FlowService.notePeerAssert(); return; }
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
                    case "SEEKBY": MediaExtras.seekBy(ctl, Long.parseLong(arg)); break;
                    case "SPEEDNEXT": MediaExtras.nextSpeed(ctl); break;
                    case "SLEEP": {
                        int min = Integer.parseInt(arg);
                        MediaExtras.sleepIn(ctl, min);
                        if (link != null) link.send("NOTE\t" + (min == 0 ? L.t("Sleep-Timer aus.") : L.t("Pausiert in ") + min + " min."));
                        break;
                    }
                    default: break;
                }
            } catch (RuntimeException ignored) { }
            pushSoon();
        }

        /** Play a song by name in the tablet's music app — and CHECK that it really switched.
         *  A media session that doesn't support play-from-search drops the request silently, so: use the
         *  session only if it advertises that action, verify the title changed, and otherwise fall back to
         *  the play-from-search intent (the voice-assistant route). The phone gets a NOTE with the real
         *  outcome instead of an optimistic "done". */
        private void search(String arg) {
            // "query" or "query\tpositionMs" (handover: continue at the phone's spot once the song is in)
            long pos = 0;
            int tab = arg.lastIndexOf('\t');
            if (tab > 0) { pos = plong(arg.substring(tab + 1)); arg = arg.substring(0, tab); }
            final String q = arg.trim();
            final long seekTo = pos, t0 = SystemClock.elapsedRealtime();
            if (q.isEmpty()) return;
            final String before = nowTitle();
            MediaController target = ctl;
            if (target == null || !YT_MUSIC.equals(target.getPackageName())) {
                try {
                    for (MediaController m : sessions.getActiveSessions(me))
                        if (YT_MUSIC.equals(m.getPackageName())) { target = m; break; }
                } catch (SecurityException ignored) { }
            }
            boolean viaSession = false;
            if (target != null && supports(target, PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
                try { target.getTransportControls().playFromSearch(q, new android.os.Bundle()); viaSession = true; }
                catch (RuntimeException ignored) { }
            }
            if (!viaSession && !searchIntent(q)) return;
            final boolean triedSession = viaSession;
            final Runnable ok = () -> {
                if (seekTo > 8000 && ctl != null && supports(ctl, PlaybackState.ACTION_SEEK_TO))
                    try { ctl.getTransportControls().seekTo(seekTo + (SystemClock.elapsedRealtime() - t0)); } catch (RuntimeException ignored) { }
                confirm();
            };
            main.postDelayed(() -> {
                if (landed(before, q)) { ok.run(); return; }
                if (triedSession && searchIntent(q))
                    main.postDelayed(() -> { if (landed(before, q)) ok.run(); else failNote(); }, 4500);
                else failNote();
            }, 4000);
        }

        private static boolean supports(MediaController m, long action) {
            PlaybackState s = m.getPlaybackState();
            return s != null && (s.getActions() & action) != 0;
        }

        private String nowTitle() {
            repick();
            MediaMetadata md = ctl == null ? null : ctl.getMetadata();
            return md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        }

        /** Switched = a different title than before, or (same song re-sent) the query names what's playing. */
        private boolean landed(String before, String q) {
            String now = nowTitle();
            if (now.isEmpty()) return false;
            return !now.equals(before) || q.toLowerCase(java.util.Locale.ROOT).contains(now.toLowerCase(java.util.Locale.ROOT));
        }

        private boolean searchIntent(String q) {
            Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .setPackage(YT_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); return true; }
            catch (RuntimeException e) { failNote(); return false; }
        }

        private void confirm() {
            if (link != null) link.send("NOTE\t" + L.t("Tablet spielt jetzt: ") + clean(nowTitle()));
            pushSoon();
        }

        private void failNote() {
            if (link != null) link.send("NOTE\t" + L.t("Das Tablet hat nicht gewechselt. Auf dem Tablet ARC „Über anderen Apps einblenden“ erlauben (oder YouTube Music einmal öffnen), dann nochmals."));
        }

        private void startApp() {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(YT_MUSIC);
            try {
                if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else if (link != null) link.send(L.t("NOTE\tKeine Musik-App aktiv auf dem Tablet."));
            } catch (RuntimeException e) {
                if (link != null) link.send(L.t("NOTE\tYouTube Music einmal auf dem Tablet öffnen, dann klappt's."));
            }
        }

        private static String nz(String s) { return s == null ? "" : s; }
        private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' '); }
        private static long plong(String s) { try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; } }
    }
}
