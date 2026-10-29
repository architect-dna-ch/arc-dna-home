package ch.architectdna.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * The anti-theft watch on the lock screen ("STEAL ME."): while the lock screen is showing, the
 * selfie camera's picture (security-cam grey), the phone's own GPS position and a live microphone
 * graph go onto the ARC-DNA Live wallpaper. Everything stays on the phone: nothing is saved or sent,
 * and it all stops as soon as the lock screen goes away. Runs inside the ARC-DNA Scroll service,
 * which may use the camera and microphone in the background (with its notification).
 */
final class TheftWatch {

    /** Latest camera picture (already turned upright), or null. */
    static volatile Bitmap frame;
    /** Microphone levels 0..1, a ring; head = next slot to write. */
    static final float[] levels = new float[96];
    static volatile int head;
    static volatile Location where;
    static volatile boolean running;
    static volatile String status = "";

    private static HandlerThread thread;
    private static Handler bg;
    private static CameraDevice camera;
    private static CameraCaptureSession session;
    private static ImageReader reader;
    private static AudioRecord mic;
    private static Thread micThread;
    private static LocationManager lm;
    private static int sensor = 270;
    private static int[] px;

    private static final LocationListener gps = loc -> where = loc;

    private TheftWatch() {
    }

    static void start(Context c) {
        if (running) return;
        running = true;
        status = "";
        thread = new HandlerThread("arc-theft");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(c);
        else status = "Kamera nicht erlaubt";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) openMic();
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm = c.getSystemService(LocationManager.class);
                Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) where = last;
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, gps, Looper.getMainLooper());
            } catch (RuntimeException e) {
                DiagLog.log("theft watch gps: " + e.getMessage());
            }
        }
        DiagLog.log("theft watch on");
    }

    static void stop() {
        if (!running) return;
        running = false;
        try {
            if (session != null) session.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (RuntimeException ignored) {
        }
        if (reader != null) reader.close();
        session = null;
        camera = null;
        reader = null;
        frame = null;
        if (mic != null) {
            try {
                mic.stop();
            } catch (RuntimeException ignored) {
            }
            mic.release();
            mic = null;
        }
        if (lm != null) {
            try {
                lm.removeUpdates(gps);
            } catch (RuntimeException ignored) {
            }
            lm = null;
        }
        if (thread != null) thread.quitSafely();
        thread = null;
        DiagLog.log("theft watch off");
    }

    private static void openCamera(Context c) {
        try {
            CameraManager cm = c.getSystemService(CameraManager.class);
            String front = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    front = id;
                    Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensor = o == null ? 270 : o;
                    break;
                }
            }
            if (front == null) {
                status = "keine Selfie-Kamera";
                return;
            }
            reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(TheftWatch::onImage, bg);
            cm.openCamera(front, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (!running) {
                        d.close();
                        return;
                    }
                    camera = d;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    s.setRepeatingRequest(b.build(), null, bg);
                                } catch (Exception e) {
                                    status = "Kamera: " + e.getMessage();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {
                                status = "Kamera besetzt";
                            }
                        }, bg);
                    } catch (Exception e) {
                        status = "Kamera: " + e.getMessage();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    camera = null;
                    status = "Kamera getrennt";
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    camera = null;
                    status = "Kamera blockiert (" + error + ")";
                    DiagLog.log("theft watch camera error " + error);
                }
            }, bg);
        } catch (Exception e) {
            status = "Kamera: " + e.getMessage();
            DiagLog.log("theft watch camera: " + e.getMessage());
        }
    }

    /** Luma only, tinted like a night security camera, turned upright and mirrored like a selfie. */
    private static void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), rs = y.getRowStride(), ps = y.getPixelStride();
            boolean turn = sensor == 90 || sensor == 270;
            int ow = turn ? h : w, oh = turn ? w : h;
            if (px == null || px.length != ow * oh) px = new int[ow * oh];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    int l = buf.get(j * rs + i * ps) & 0xFF;
                    int g = Math.min(255, l + 18);
                    int col = 0xFF000000 | ((l * 3 / 4) << 16) | (g << 8) | (l * 3 / 4);
                    int x, yy;
                    if (sensor == 270) {          // front camera, usual: rotate and mirror
                        x = j;
                        yy = w - 1 - i;
                        x = ow - 1 - x;
                    } else if (sensor == 90) {
                        x = h - 1 - j;
                        yy = i;
                        x = ow - 1 - x;
                    } else {
                        x = w - 1 - i;
                        yy = j;
                    }
                    px[yy * ow + x] = col;
                }
            }
            Bitmap b = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888);
            frame = b;
        } catch (RuntimeException ignored) {
        } finally {
            img.close();
        }
    }

    @SuppressWarnings("MissingPermission")
    private static void openMic() {
        try {
            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
            mic.startRecording();
            AudioRecord m = mic;
            micThread = new Thread(() -> {
                short[] chunk = new short[800];               // 50 ms
                while (running && m == mic) {
                    int n = m.read(chunk, 0, chunk.length);
                    if (n <= 0) continue;
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += chunk[i] * (double) chunk[i];
                    float rms = (float) Math.sqrt(sum / n) / 32768f;
                    // Log scale: a whisper shows, a shout fills it.
                    float lv = (float) Math.max(0, Math.min(1, (20 * Math.log10(Math.max(1e-5, rms)) + 60) / 55));
                    levels[head] = lv;
                    head = (head + 1) % levels.length;
                }
            }, "arc-theft-mic");
            micThread.start();
        } catch (RuntimeException e) {
            DiagLog.log("theft watch mic: " + e.getMessage());
        }
    }
}
