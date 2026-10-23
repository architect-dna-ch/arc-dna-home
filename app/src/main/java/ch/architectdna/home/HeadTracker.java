package ch.architectdna.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the head's up/down tilt from the front camera with on-device ML Kit face detection.
 * Frames never leave the phone and nothing is stored; the camera only runs between start() and stop().
 * It is its own LifecycleOwner so the launcher can stay a plain framework Activity.
 */
final class HeadTracker implements LifecycleOwner {

    interface Listener {
        /** Head pitch in degrees: positive = looking up. Called on the main thread. */
        void onTilt(float degrees);

        /** No face in view (e.g. phone put down). Called on the main thread. */
        void onLost();

        void onError(String message);
    }

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = main::post;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final FaceDetector detector;
    private ProcessCameraProvider provider;
    private boolean running;

    HeadTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.2f)
            .build());
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    void start() {
        if (running) return;
        running = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (!running) return;
            try {
                provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                provider.unbindAll();
                lifecycle.setCurrentState(Lifecycle.State.RESUMED);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            } catch (Exception e) {
                running = false;
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
        detector.close();
        analysisExecutor.shutdown();
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy proxy) {
        Image image = proxy.getImage();
        if (image == null || !running) {
            proxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees());
        detector.process(input)
            .addOnSuccessListener(faces -> {
                if (!running) return;
                if (faces.isEmpty()) listener.onLost();
                else listener.onTilt(faces.get(0).getHeadEulerAngleX());
            })
            .addOnCompleteListener(task -> proxy.close());
    }
}
