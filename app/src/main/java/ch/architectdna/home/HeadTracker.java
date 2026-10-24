package ch.architectdna.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads where the user is looking from the front camera, entirely on the phone:
 * NOD  – head up/down tilt via ML Kit face detection;
 * GAZE – eyes looking up/down via MediaPipe Face Landmarker blendshapes.
 * Frames never leave the phone and nothing is stored; the camera only runs between start() and stop().
 * It is its own LifecycleOwner so the launcher can stay a plain framework Activity.
 */
final class HeadTracker implements LifecycleOwner {

    enum Mode { NOD, GAZE }

    interface Listener {
        /** NOD: head pitch in degrees, positive = looking up. Main thread. */
        void onTilt(float degrees);

        /** GAZE: eyes from -1 (looking up) to +1 (looking down). Main thread. */
        void onGaze(float vertical);

        /** No face in view (e.g. phone put down). Main thread. */
        void onLost();

        void onError(String message);
    }

    private static final String GAZE_MODEL = "face_landmarker.task";

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = main::post;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private FaceDetector detector;
    private FaceLandmarker landmarker;
    private ProcessCameraProvider provider;
    private volatile boolean running;
    private volatile Mode mode = Mode.NOD;
    private long lastTimestamp;

    HeadTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    void start(Mode m) {
        if (running && m == mode) return;
        if (running) stop();
        mode = m;
        try {
            if (m == Mode.GAZE && landmarker == null) landmarker = createLandmarker();
            if (m == Mode.NOD && detector == null) {
                detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.2f)
                    .build());
            }
        } catch (RuntimeException e) {
            listener.onError(m == Mode.GAZE ? "Blick-Erkennung nicht verfügbar." : "Gesichtserkennung nicht verfügbar.");
            return;
        }
        running = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (!running) return;
            try {
                provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
        analysisExecutor.execute(() -> {
            if (detector != null) detector.close();
            if (landmarker != null) landmarker.close();
        });
        analysisExecutor.shutdown();
    }

    private FaceLandmarker createLandmarker() {
        BaseOptions base = BaseOptions.builder().setModelAssetPath(GAZE_MODEL).build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setResultListener(this::onLandmarks)
            .setErrorListener(e -> main.post(() -> listener.onError("Blick-Erkennung: " + e.getMessage())))
            .build();
        return FaceLandmarker.createFromOptions(context, options);
    }

    /** Runs on the analysis thread; the frame is copied to an upright bitmap and released at once. */
    private void analyze(ImageProxy proxy) {
        if (!running) {
            proxy.close();
            return;
        }
        int rotation = proxy.getImageInfo().getRotationDegrees();
        Bitmap frame;
        try {
            frame = proxy.toBitmap();
        } finally {
            proxy.close();
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            frame = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);
        }

        if (mode == Mode.GAZE) {
            if (landmarker == null) return;
            long ts = Math.max(SystemClock.uptimeMillis(), lastTimestamp + 1);
            lastTimestamp = ts;
            landmarker.detectAsync(new BitmapImageBuilder(frame).build(), ts);
            return;
        }

        if (detector == null) return;
        try {
            List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)));
            if (faces.isEmpty()) {
                main.post(() -> {
                    if (running) listener.onLost();
                });
            } else {
                float pitch = faces.get(0).getHeadEulerAngleX();
                main.post(() -> {
                    if (running) listener.onTilt(pitch);
                });
            }
        } catch (Exception ignored) {
            // A dropped frame is fine; the next one follows in ~30 ms.
        }
    }

    /** MediaPipe result (background thread): eyeLookDown minus eyeLookUp, averaged over both eyes. */
    private void onLandmarks(FaceLandmarkerResult result, MPImage input) {
        if (!running) return;
        Optional<List<List<Category>>> shapes = result.faceBlendshapes();
        if (!shapes.isPresent() || shapes.get().isEmpty()) {
            main.post(() -> {
                if (running) listener.onLost();
            });
            return;
        }
        float down = 0, up = 0, blink = 0;
        for (Category c : shapes.get().get(0)) {
            String name = c.categoryName();
            if (name.startsWith("eyeLookDown")) down += c.score();
            else if (name.startsWith("eyeLookUp")) up += c.score();
            else if (name.startsWith("eyeBlink")) blink += c.score();
        }
        // A blink reads as "looking down"; skip those frames instead of scrolling.
        if (blink / 2f > 0.45f) return;
        float vertical = (down - up) / 2f;
        main.post(() -> {
            if (running) listener.onGaze(vertical);
        });
    }
}
