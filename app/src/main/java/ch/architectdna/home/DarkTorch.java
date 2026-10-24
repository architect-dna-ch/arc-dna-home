package ch.architectdna.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * The flashlight for dark rooms: a quick look through the back camera (about a second, small
 * frames, nothing stored) measures how bright the room really is, so a phone lying face down on
 * a lit table doesn't switch the torch on. Then the torch goes on or off via the system API.
 */
final class DarkTorch {

    interface Listener {
        /** The torch state changed, by us or by the user (quick settings). Main thread. */
        void onTorch(boolean on, boolean byUs);
    }

    private final CameraManager cameras;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private String backId;
    private boolean on, settingByUs, probing;

    DarkTorch(Context c, Listener listener) {
        this.listener = listener;
        cameras = c.getSystemService(CameraManager.class);
        if (cameras == null) return;
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics ch = cameras.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && Boolean.TRUE.equals(flash)) {
                    backId = id;
                    break;
                }
            }
            cameras.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (!id.equals(backId) || enabled == on) return;
                    on = enabled;
                    boolean ours = settingByUs;
                    settingByUs = false;
                    listener.onTorch(enabled, ours);
                }
            }, main);
        } catch (CameraAccessException | RuntimeException e) {
            backId = null;
        }
    }

    boolean available() {
        return backId != null;
    }

    boolean isOn() {
        return on;
    }

    boolean isProbing() {
        return probing;
    }

    void set(boolean enable) {
        if (backId == null || enable == on) return;
        try {
            settingByUs = true;
            cameras.setTorchMode(backId, enable);
            DiagLog.log("torch " + (enable ? "on" : "off"));
        } catch (CameraAccessException | RuntimeException e) {
            settingByUs = false;
            DiagLog.log("torch failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Opens the back camera for ~1 s with auto exposure and reports the average brightness
     * (0–255) of the last frame, or -1 if the camera couldn't be used. Needs the CAMERA permission.
     */
    @SuppressLint("MissingPermission")
    void probe(Consumer<Integer> result) {
        if (backId == null || probing) {
            result.accept(-1);
            return;
        }
        probing = true;
        HandlerThread thread = new HandlerThread("dark-probe");
        thread.start();
        Handler bg = new Handler(thread.getLooper());
        ImageReader reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 3);
        final CameraDevice[] device = new CameraDevice[1];
        final int[] frames = {0};
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Integer> deliver = luma -> {
            if (!done.compareAndSet(false, true)) return;
            try {
                if (device[0] != null) device[0].close();
            } catch (RuntimeException ignored) {
            }
            reader.close();
            thread.quitSafely();
            main.post(() -> {
                probing = false;
                DiagLog.log("back camera brightness " + luma);
                result.accept(luma);
            });
        };
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            int luma = -2;
            try {
                // Let auto exposure settle for ~12 frames, then average the luma plane.
                if (++frames[0] >= 12) {
                    ByteBuffer y = img.getPlanes()[0].getBuffer();
                    int rowStride = img.getPlanes()[0].getRowStride();
                    long sum = 0;
                    int n = 0;
                    for (int row = 0; row < img.getHeight(); row += 6) {
                        for (int col = 0; col < img.getWidth(); col += 6) {
                            sum += y.get(row * rowStride + col) & 0xFF;
                            n++;
                        }
                    }
                    luma = n == 0 ? -1 : (int) (sum / n);
                }
            } finally {
                img.close();
            }
            if (luma != -2) deliver.accept(luma);
        }, bg);
        bg.postDelayed(() -> deliver.accept(-1), 4000);
        try {
            cameras.openCamera(backId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    device[0] = cam;
                    if (done.get()) {
                        cam.close();
                        return;
                    }
                    try {
                        CaptureRequest.Builder req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        req.addTarget(reader.getSurface());
                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        cam.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession s) {
                                    try {
                                        s.setRepeatingRequest(req.build(), null, bg);
                                    } catch (CameraAccessException | RuntimeException e) {
                                        deliver.accept(-1);
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession s) {
                                    deliver.accept(-1);
                                }
                            }, bg);
                    } catch (CameraAccessException | RuntimeException e) {
                        deliver.accept(-1);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    deliver.accept(-1);
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    deliver.accept(-1);
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
            deliver.accept(-1);
        }
    }
}
