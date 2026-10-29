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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
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

        /** Debug mode only: a small copy of the camera frame plus what was found in it. Main thread. */
        default void onDebug(Debug d) {
        }

        /** NOD: the whole head pose (pitch up / down, yaw left / right in degrees). Main thread. */
        default void onPose(float pitch, float yaw) {
        }
    }

    /** What the tracker sees and computes, for the debug corner. Points are 0..1 of the frame. */
    static final class Debug {
        Bitmap frame;
        boolean face;
        android.graphics.RectF box;
        final List<float[]> contours = new java.util.ArrayList<>();   // ML Kit outline, eyes, brows, nose, lips
        float[] eyes;      // MediaPipe: lids and corners of both eyes
        float[] iris;      // MediaPipe: the two iris centres
        float pitch, yaw;  // head angles (degrees)
        float gazeV = Float.NaN, gazeH = Float.NaN;   // eyes: down−up, right−left (−1 … 1)
    }

    private volatile boolean debug;
    private long lastDebug;
    private FaceLandmarker debugLandmarker;

    /** Turn the debug corner on / off; takes effect from the next start(). */
    void setDebug(boolean on) {
        if (on == debug) return;
        debug = on;
        // The head angle always comes from the plain detector (ML Kit reports no Euler angles with
        // contours on, which made calibration in debug mode read 0.0°); contours get their own.
        FaceDetector old = contourDetector;
        contourDetector = null;
        if (old != null) analysisExecutor.execute(old::close);
    }

    private static final String GAZE_MODEL = "face_landmarker.task";

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = main::post;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private FaceDetector detector, contourDetector, accurate;
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
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setMinFaceSize(0.2f)
                    .build());
            }
            if (m == Mode.NOD && debug && contourDetector == null) {
                contourDetector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
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
                // The picture is turned for the screen's rotation now and on every turn after (in
                // landscape the faces came sideways and none were found: "81 readings, 81 lost").
                ImageAnalysis a = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(displayRotation())
                    .build();
                a.setAnalyzer(analysisExecutor, this::analyze);
                analysis = a;
                provider.unbindAll();
                lifecycle.setCurrentState(Lifecycle.State.RESUMED);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a);
                android.hardware.display.DisplayManager dm = context.getSystemService(android.hardware.display.DisplayManager.class);
                if (dm != null && turns == null) {
                    turns = new android.hardware.display.DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(int id) {
                        }

                        @Override
                        public void onDisplayRemoved(int id) {
                        }

                        @Override
                        public void onDisplayChanged(int id) {
                            ImageAnalysis now = analysis;
                            if (now != null) now.setTargetRotation(displayRotation());
                        }
                    };
                    dm.registerDisplayListener(turns, main);
                }
            } catch (Exception e) {
                running = false;
                listener.onError("Kamera nicht verfügbar.");
            }
        }, mainExecutor);
    }

    private ImageAnalysis analysis;
    private android.hardware.display.DisplayManager.DisplayListener turns;

    private int displayRotation() {
        android.hardware.display.DisplayManager dm = context.getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        return d == null ? android.view.Surface.ROTATION_0 : d.getRotation();
    }

    void stop() {
        running = false;
        analysis = null;
        if (turns != null) {
            android.hardware.display.DisplayManager dm = context.getSystemService(android.hardware.display.DisplayManager.class);
            if (dm != null) dm.unregisterDisplayListener(turns);
            turns = null;
        }
        if (provider != null) provider.unbindAll();
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    void close() {
        stop();
        analysisExecutor.execute(() -> {
            if (detector != null) detector.close();
            if (contourDetector != null) contourDetector.close();
            if (accurate != null) accurate.close();
            if (landmarker != null) landmarker.close();
            if (debugLandmarker != null) debugLandmarker.close();
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
                // The fast detector misses a face that's very close, tilted or cut off at the frame edge
                // ("KEIN GESICHT" with the face clearly there). The accurate one gives the same angles.
                if (accurate == null) accurate = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setMinFaceSize(0.15f)
                    .build());
                faces = Tasks.await(accurate.process(InputImage.fromBitmap(frame, 0)));
            }
            if (debug && SystemClock.uptimeMillis() - lastDebug > 120) {
                lastDebug = SystemClock.uptimeMillis();
                List<Face> outline = faces;
                if (contourDetector != null) {
                    try {
                        outline = Tasks.await(contourDetector.process(InputImage.fromBitmap(frame, 0)));
                    } catch (Exception ignored) {
                    }
                }
                Debug d = debugInfo(frame, faces, outline);
                main.post(() -> {
                    if (running) listener.onDebug(d);
                });
            }
            if (faces.isEmpty()) {
                main.post(() -> {
                    if (running) listener.onLost();
                });
            } else {
                float pitch = faces.get(0).getHeadEulerAngleX(), yaw = faces.get(0).getHeadEulerAngleY();
                main.post(() -> {
                    if (running) listener.onPose(pitch, yaw);
                    if (running) listener.onTilt(pitch);
                });
            }
        } catch (Exception ignored) {
            // A dropped frame is fine; the next one follows in ~30 ms.
        }
    }

    /** Debug corner: shrink the frame, collect ML Kit's contours and MediaPipe's eye points. */
    private Debug debugInfo(Bitmap frame, List<Face> faces, List<Face> outline) {
        Debug d = new Debug();
        float fw = frame.getWidth(), fh = frame.getHeight();
        d.frame = Bitmap.createScaledBitmap(frame, 240, Math.max(1, Math.round(240 * fh / fw)), true);
        if (!faces.isEmpty()) {
            Face f = faces.get(0);
            d.face = true;
            d.pitch = f.getHeadEulerAngleX();
            d.yaw = f.getHeadEulerAngleY();
            android.graphics.Rect b = f.getBoundingBox();
            d.box = new android.graphics.RectF(b.left / fw, b.top / fh, b.right / fw, b.bottom / fh);
            for (FaceContour c : outline.isEmpty() ? f.getAllContours() : outline.get(0).getAllContours()) {
                List<android.graphics.PointF> pts = c.getPoints();
                float[] line = new float[pts.size() * 2];
                for (int i = 0; i < pts.size(); i++) {
                    line[2 * i] = pts.get(i).x / fw;
                    line[2 * i + 1] = pts.get(i).y / fh;
                }
                d.contours.add(line);
            }
        }
        // The eyes, from the detailed face mesh (the model BLICK used), to compare.
        try {
            if (debugLandmarker == null) {
                debugLandmarker = FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(GAZE_MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .build());
            }
            FaceLandmarkerResult r = debugLandmarker.detect(new BitmapImageBuilder(frame).build());
            if (!r.faceLandmarks().isEmpty()) {
                List<NormalizedLandmark> lm = r.faceLandmarks().get(0);
                int[] eyeIdx = {33, 159, 133, 145, 362, 386, 263, 374};
                d.eyes = new float[eyeIdx.length * 2];
                for (int i = 0; i < eyeIdx.length; i++) {
                    d.eyes[2 * i] = lm.get(eyeIdx[i]).x();
                    d.eyes[2 * i + 1] = lm.get(eyeIdx[i]).y();
                }
                if (lm.size() > 473) d.iris = new float[]{lm.get(468).x(), lm.get(468).y(), lm.get(473).x(), lm.get(473).y()};
            }
            Optional<List<List<Category>>> shapes = r.faceBlendshapes();
            if (shapes.isPresent() && !shapes.get().isEmpty()) {
                float down = 0, up = 0, inL = 0, outL = 0, inR = 0, outR = 0;
                for (Category c : shapes.get().get(0)) {
                    String n = c.categoryName();
                    if (n.startsWith("eyeLookDown")) down += c.score();
                    else if (n.startsWith("eyeLookUp")) up += c.score();
                    else if (n.equals("eyeLookInLeft")) inL = c.score();
                    else if (n.equals("eyeLookOutLeft")) outL = c.score();
                    else if (n.equals("eyeLookInRight")) inR = c.score();
                    else if (n.equals("eyeLookOutRight")) outR = c.score();
                }
                d.gazeV = (down - up) / 2f;
                d.gazeH = ((outL + inR) - (inL + outR)) / 2f;
            }
        } catch (RuntimeException e) {
            // No eye model: the corner shows the head part only.
        }
        return d;
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
