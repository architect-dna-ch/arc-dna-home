package ch.architectdna.home;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ARC-DNA camera's engine: preview, photo capture (into Pictures/ARC-DNA, so the Galerie globe
 * shows it at once) and a look at the scene about three times a second, all on the phone: ML Kit
 * object detection for the main subject's box and image labeling for what it is ("Dog", "Flower").
 * Its own LifecycleOwner, so the camera screen can stay a plain framework Activity.
 */
final class CameraEngine implements LifecycleOwner {

    interface Listener {
        void onScene(Scene s);

        void onError(String message);
    }

    /** What the camera sees right now; the box is normalized to the upright frame (0…1). */
    static final class Scene {
        boolean box;
        float l, t, r, b;
        /** Best labels, most confident first (English, from ML Kit). */
        final List<String> labels = new ArrayList<>();
        float brightness;
        /** Frame width / height, upright. */
        float aspect = 0.75f;
    }

    private final Context context;
    private final Listener listener;
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = main::post;
    private final PreviewView view;
    private ProcessCameraProvider provider;
    private ImageCapture capture;
    private ObjectDetector objects;
    private ImageLabeler labeler;
    private volatile boolean running;
    private long lastRun;

    CameraEngine(Context c, Listener l) {
        context = c;
        listener = l;
        view = new PreviewView(c);
        view.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        view.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    View preview() {
        return view;
    }

    /** rotation: Surface.ROTATION_* of the display. */
    void start(boolean front, int rotation) {
        try {
            if (objects == null) {
                objects = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                    .enableClassification()
                    .build());
                labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build());
            }
        } catch (RuntimeException e) {
            DiagLog.log("camera ml init failed: " + e.getMessage());
        }
        running = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (!running) return;
            try {
                provider = future.get();
                Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
                preview.setSurfaceProvider(view.getSurfaceProvider());
                capture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                    .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(rotation)
                    .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                provider.unbindAll();
                lifecycle.setCurrentState(Lifecycle.State.RESUMED);
                provider.bindToLifecycle(this, front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis);
            } catch (Exception e) {
                running = false;
                DiagLog.log("camera start failed: " + e.getMessage());
                listener.onError("Kamera nicht verfügbar.");
            }
        }, mainExecutor);
    }

    void stop() {
        running = false;
        if (provider != null) provider.unbindAll();
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    void close() {
        stop();
        analysisExecutor.execute(() -> {
            if (objects != null) objects.close();
            if (labeler != null) labeler.close();
        });
        analysisExecutor.shutdown();
    }

    /** Saves a photo; done(uri or null) on the main thread. */
    void take(int rotation, java.util.function.Consumer<Uri> done) {
        if (capture == null) {
            done.accept(null);
            return;
        }
        capture.setTargetRotation(rotation);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "ARC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()));
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ARC-DNA");
        ImageCapture.OutputFileOptions out = new ImageCapture.OutputFileOptions.Builder(
            context.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v).build();
        capture.takePicture(out, mainExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults r) {
                done.accept(r.getSavedUri());
            }

            @Override
            public void onError(ImageCaptureException e) {
                DiagLog.log("camera capture failed: " + e.getMessage());
                done.accept(null);
            }
        });
    }

    /** Analysis thread: a small upright copy of the frame, then subject box, labels and brightness. */
    private void analyze(ImageProxy proxy) {
        long now = SystemClock.uptimeMillis();
        if (!running || now - lastRun < 330) {
            proxy.close();
            return;
        }
        lastRun = now;
        int rotation = proxy.getImageInfo().getRotationDegrees();
        Bitmap frame;
        try {
            frame = proxy.toBitmap();
        } finally {
            proxy.close();
        }
        float k = Math.min(1f, 480f / Math.max(frame.getWidth(), frame.getHeight()));
        Matrix m = new Matrix();
        m.postScale(k, k);
        m.postRotate(rotation);
        Bitmap up = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);
        Scene s = new Scene();
        int w = up.getWidth(), h = up.getHeight();
        s.aspect = w / (float) h;
        s.brightness = brightness(up);
        try {
            if (objects != null) {
                List<DetectedObject> found = Tasks.await(objects.process(InputImage.fromBitmap(up, 0)));
                DetectedObject best = null;
                long bestArea = 0;
                for (DetectedObject o : found) {
                    Rect r = o.getBoundingBox();
                    long a = (long) r.width() * r.height();
                    if (a > bestArea) {
                        bestArea = a;
                        best = o;
                    }
                }
                if (best != null) {
                    Rect r = best.getBoundingBox();
                    s.box = true;
                    s.l = Math.max(0, r.left / (float) w);
                    s.t = Math.max(0, r.top / (float) h);
                    s.r = Math.min(1, r.right / (float) w);
                    s.b = Math.min(1, r.bottom / (float) h);
                }
            }
            if (labeler != null) {
                List<ImageLabel> labels = Tasks.await(labeler.process(InputImage.fromBitmap(up, 0)));
                for (ImageLabel l : labels) {
                    if (s.labels.size() >= 5) break;
                    s.labels.add(l.getText());
                }
            }
        } catch (Exception ignored) {
        }
        main.post(() -> {
            if (running) listener.onScene(s);
        });
    }

    private static float brightness(Bitmap b) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = b.getPixel(x * (b.getWidth() - 1) / 15, y * (b.getHeight() - 1) / 15);
                sum += ((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255);
                n++;
            }
        }
        return sum / (n * 10f * 255f);
    }
}
