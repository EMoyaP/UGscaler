package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** On-device CodeFormer face restoration with a fidelity weight baked at 0.9. */
public final class CodeFormerFaceRestorer {
    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static final class Result {
        public final Bitmap bitmap;
        public final int restoredFaces;

        Result(Bitmap bitmap, int restoredFaces) {
            this.bitmap = bitmap;
            this.restoredFaces = restoredFaces;
        }
    }

    private static final String MODEL_NAME = "codeformer-w09.onnx";
    private static final String MODEL_URL =
            "https://github.com/EMoyaP/UGscaler/releases/download/v1.4.0/" + MODEL_NAME;
    private static final String MODEL_SHA256 =
            "abc9336c5b28b608c258a54813fb59054e5f6986446b54776349ea5f5e23e10e";
    private static final long MIN_MODEL_BYTES = 350_000_000L;
    private static final int FACE_SIZE = 512;

    private CodeFormerFaceRestorer() {}

    public static boolean isInstalled(Context context) {
        File model = modelFile(context);
        return model.isFile() && model.length() >= MIN_MODEL_BYTES;
    }

    public static File ensureModel(Context context, ProgressListener listener) throws Exception {
        File model = modelFile(context);
        if (isInstalled(context)) return model;
        File partial = new File(context.getFilesDir(), MODEL_NAME + ".download");
        if (partial.exists() && !partial.delete()) {
            throw new Exception("No se pudo reiniciar la descarga del modelo");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "UGscaler-Android/1.4");
        connection.connect();
        int response = connection.getResponseCode();
        if (response < 200 || response >= 300) {
            connection.disconnect();
            throw new Exception("Descarga CodeFormer: HTTP " + response);
        }
        long expected = connection.getContentLengthLong();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        int lastPercent = -1;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
                int percent = expected > 0 ? (int) Math.min(100, total * 100L / expected) : -1;
                if (listener != null && percent != lastPercent) {
                    listener.onProgress(percent);
                    lastPercent = percent;
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        String actual = hex(digest.digest());
        if (total < MIN_MODEL_BYTES || !MODEL_SHA256.equals(actual)) {
            partial.delete();
            throw new Exception("El modelo descargado no supera la verificacion SHA-256");
        }
        if (model.exists() && !model.delete()) {
            partial.delete();
            throw new Exception("No se pudo actualizar el modelo");
        }
        if (!partial.renameTo(model)) {
            partial.delete();
            throw new Exception("No se pudo instalar el modelo");
        }
        return model;
    }

    public static Result restore(
            Context context, Bitmap input, ProgressListener listener) throws Exception {
        Detection detection = detectFaces(input);
        if (detection.faces.isEmpty()) {
            recycleDetection(detection, input);
            return new Result(input, 0);
        }
        if (!ProcessingMemory.canRunCodeFormer(context, input)) {
            recycleDetection(detection, input);
            throw new Exception(
                    "CodeFormer omitido: "
                            + ProcessingMemory.codeFormerUnavailableReason(context, input));
        }

        File model;
        try {
            model = ensureModel(context, listener);
        } catch (Exception error) {
            recycleDetection(detection, input);
            throw error;
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        Bitmap output = null;
        int restored = 0;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.setMemoryPatternOptimization(false);
            options.setCPUArenaAllocator(false);
            try (OrtSession session =
                         environment.createSession(model.getAbsolutePath(), options)) {
                output = input.copy(Bitmap.Config.ARGB_8888, true);
                if (output == null) throw new Exception("No hay memoria para restaurar el rostro");
                detection.faces.sort(Comparator.comparingInt(
                        face -> -face.getBoundingBox().width()
                                * face.getBoundingBox().height()));
                for (Face face : detection.faces) {
                    if (restored >= 2 || Thread.currentThread().isInterrupted()) break;
                    Matrix sourceToFace = alignment(face, detection.scale);
                    if (sourceToFace == null) continue;
                    Bitmap aligned = Bitmap.createBitmap(
                            FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
                    Bitmap restoredFace = null;
                    try {
                        Canvas alignedCanvas = new Canvas(aligned);
                        Paint filter =
                                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        alignedCanvas.drawBitmap(input, sourceToFace, filter);
                        restoredFace = run(session, environment, aligned);
                        paste(output, restoredFace, sourceToFace);
                        restored++;
                    } finally {
                        aligned.recycle();
                        if (restoredFace != null && !restoredFace.isRecycled()) {
                            restoredFace.recycle();
                        }
                    }
                }
            }
        } catch (Exception | OutOfMemoryError error) {
            if (output != null && !output.isRecycled()) output.recycle();
            throw error;
        } finally {
            recycleDetection(detection, input);
        }
        if (restored == 0) {
            if (output != null && !output.isRecycled()) output.recycle();
            return new Result(input, 0);
        }
        return new Result(output, restored);
    }

    private static Detection detectFaces(Bitmap input) throws Exception {
        float scale = Math.min(1f, 1600f / Math.max(input.getWidth(), input.getHeight()));
        Bitmap detectionBitmap = scale < 1f
                ? Bitmap.createScaledBitmap(
                        input,
                        Math.max(1, Math.round(input.getWidth() * scale)),
                        Math.max(1, Math.round(input.getHeight() * scale)),
                        true)
                : input;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(.04f)
                .build();
        FaceDetector detector = FaceDetection.getClient(options);
        try {
            List<Face> faces = Tasks.await(
                    detector.process(InputImage.fromBitmap(detectionBitmap, 0)));
            return new Detection(detectionBitmap, scale, faces);
        } finally {
            detector.close();
        }
    }

    private static Matrix alignment(Face face, float detectionScale) {
        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
        FaceLandmark mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT);
        FaceLandmark mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT);
        if (leftEye == null || rightEye == null || mouthLeft == null || mouthRight == null) {
            return null;
        }
        PointF le = original(leftEye.getPosition(), detectionScale);
        PointF re = original(rightEye.getPosition(), detectionScale);
        PointF ml = original(mouthLeft.getPosition(), detectionScale);
        PointF mr = original(mouthRight.getPosition(), detectionScale);
        float[] source = {
                le.x, le.y,
                re.x, re.y,
                (ml.x + mr.x) * .5f, (ml.y + mr.y) * .5f
        };
        float[] target = {
                192.981f, 239.947f,
                318.903f, 240.194f,
                257.175f, 371.281f
        };
        Matrix matrix = new Matrix();
        return matrix.setPolyToPoly(source, 0, target, 0, 3) ? matrix : null;
    }

    private static Bitmap run(
            OrtSession session, OrtEnvironment environment, Bitmap aligned) throws Exception {
        int[] colors = new int[FACE_SIZE * FACE_SIZE];
        aligned.getPixels(colors, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        float[] values = new float[3 * FACE_SIZE * FACE_SIZE];
        int plane = FACE_SIZE * FACE_SIZE;
        for (int index = 0; index < colors.length; index++) {
            int color = colors[index];
            values[index] = android.graphics.Color.red(color) / 127.5f - 1f;
            values[plane + index] = android.graphics.Color.green(color) / 127.5f - 1f;
            values[plane * 2 + index] = android.graphics.Color.blue(color) / 127.5f - 1f;
        }
        float[][][][] prediction;
        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(values), new long[]{1, 3, FACE_SIZE, FACE_SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap("face", tensor))) {
            prediction = (float[][][][]) result.get(0).getValue();
        }
        int[] output = new int[plane];
        for (int index = 0; index < plane; index++) {
            int y = index / FACE_SIZE;
            int x = index % FACE_SIZE;
            output[index] = android.graphics.Color.rgb(
                    channel(prediction[0][0][y][x]),
                    channel(prediction[0][1][y][x]),
                    channel(prediction[0][2][y][x]));
        }
        Bitmap bitmap = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(output, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        return bitmap;
    }

    private static void paste(Bitmap destination, Bitmap restored, Matrix sourceToFace) {
        int[] colors = new int[FACE_SIZE * FACE_SIZE];
        restored.getPixels(colors, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        for (int index = 0; index < colors.length; index++) {
            int x = index % FACE_SIZE;
            int y = index / FACE_SIZE;
            double dx = (x - 256.0) / 222.0;
            double dy = (y - 278.0) / 248.0;
            double radius = Math.sqrt(dx * dx + dy * dy);
            double edge = clamp01((1.0 - radius) / .13);
            double smooth = edge * edge * (3.0 - 2.0 * edge);
            int alpha = (int) Math.round(225.0 * smooth);
            colors[index] = (colors[index] & 0x00ffffff) | (alpha << 24);
        }
        Bitmap overlay = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
        overlay.setPixels(colors, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        Matrix faceToSource = new Matrix();
        if (sourceToFace.invert(faceToSource)) {
            Canvas canvas = new Canvas(destination);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(overlay, faceToSource, paint);
        }
        overlay.recycle();
    }

    private static PointF original(PointF point, float scale) {
        return new PointF(point.x / scale, point.y / scale);
    }

    private static int channel(float value) {
        if (!Float.isFinite(value)) value = 0;
        return Math.max(0, Math.min(255, Math.round((value + 1f) * 127.5f)));
    }

    private static void recycleDetection(Detection detection, Bitmap input) {
        if (detection.bitmap != input && !detection.bitmap.isRecycled()) detection.bitmap.recycle();
    }

    private static File modelFile(Context context) {
        return new File(context.getFilesDir(), MODEL_NAME);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class Detection {
        final Bitmap bitmap;
        final float scale;
        final List<Face> faces;

        Detection(Bitmap bitmap, float scale, List<Face> faces) {
            this.bitmap = bitmap;
            this.scale = scale;
            this.faces = faces;
        }
    }
}
