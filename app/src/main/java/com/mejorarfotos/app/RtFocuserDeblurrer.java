package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Memory-bounded, on-device RT-Focuser inference with overlapping tiles. */
public final class RtFocuserDeblurrer {
    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static final String ASSET = "rtfocuser/rt_focuser_wint8_afp32.onnx";
    private static final String MODEL = "rt_focuser_wint8_afp32.onnx";
    private static final int TILE = 256;
    private static final int OVERLAP = 32;
    private static final int STRIDE = TILE - OVERLAP;
    private static final Object MODEL_LOCK = new Object();

    private RtFocuserDeblurrer() {}

    public static Bitmap restore(
            Context context, Bitmap source, int maxSide, ProgressListener listener) throws Exception {
        if (source == null || source.isRecycled()) throw new Exception("Imagen no disponible");
        Bitmap fitted = ProcessingMemory.fit(source, maxSide);
        int width = fitted.getWidth();
        int height = fitted.getHeight();
        int[] xs = starts(width);
        int[] ys = starts(height);
        int total = xs.length * ys.length;
        int pixels = width * height;
        float[] red = new float[pixels];
        float[] green = new float[pixels];
        float[] blue = new float[pixels];
        float[] weights = new float[pixels];

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Math.min(2,
                    Runtime.getRuntime().availableProcessors() - 1)));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setMemoryPatternOptimization(false);
            options.setCPUArenaAllocator(false);
            try (OrtSession session = environment.createSession(model(context).getAbsolutePath(), options)) {
                int completed = 0;
                for (int y : ys) {
                    for (int x : xs) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Procesado cancelado");
                        }
                        float[] input = tensor(fitted, x, y);
                        float[][][][] prediction;
                        try (OnnxTensor tensor = OnnxTensor.createTensor(
                                environment,
                                FloatBuffer.wrap(input),
                                new long[]{1, 3, TILE, TILE});
                             OrtSession.Result result = session.run(
                                     Collections.singletonMap("input", tensor))) {
                            prediction = (float[][][][]) result.get(0).getValue();
                        }
                        blend(prediction, red, green, blue, weights, width, height, x, y);
                        completed++;
                        if (listener != null) {
                            listener.onProgress(Math.min(100, completed * 100 / total));
                        }
                    }
                }
            }
        } catch (Exception | OutOfMemoryError error) {
            if (fitted != source && !fitted.isRecycled()) fitted.recycle();
            throw error;
        }

        int[] output = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            float weight = Math.max(.0001f, weights[i]);
            output[i] = Color.rgb(
                    channel(red[i] / weight),
                    channel(green[i] / weight),
                    channel(blue[i] / weight));
        }
        Bitmap restored = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        restored.setPixels(output, 0, width, 0, 0, width, height);
        if (fitted != source && !fitted.isRecycled()) fitted.recycle();
        return restored;
    }

    private static float[] tensor(Bitmap bitmap, int startX, int startY) {
        int plane = TILE * TILE;
        float[] values = new float[plane * 3];
        int[] row = new int[TILE];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        for (int y = 0; y < TILE; y++) {
            int sourceY = Math.min(height - 1, startY + y);
            int readable = Math.min(TILE, width - startX);
            bitmap.getPixels(row, 0, TILE, startX, sourceY, readable, 1);
            int edge = row[Math.max(0, readable - 1)];
            for (int x = readable; x < TILE; x++) row[x] = edge;
            int offset = y * TILE;
            for (int x = 0; x < TILE; x++) {
                int color = row[x];
                int index = offset + x;
                values[index] = Color.red(color) / 255f;
                values[plane + index] = Color.green(color) / 255f;
                values[plane * 2 + index] = Color.blue(color) / 255f;
            }
        }
        return values;
    }

    private static void blend(
            float[][][][] result,
            float[] red,
            float[] green,
            float[] blue,
            float[] weights,
            int width,
            int height,
            int startX,
            int startY) {
        int usableWidth = Math.min(TILE, width - startX);
        int usableHeight = Math.min(TILE, height - startY);
        for (int y = 0; y < usableHeight; y++) {
            float wy = feather(y, usableHeight, startY > 0, startY + TILE < height);
            int destination = (startY + y) * width + startX;
            for (int x = 0; x < usableWidth; x++) {
                float weight = wy * feather(x, usableWidth, startX > 0, startX + TILE < width);
                int index = destination + x;
                red[index] += clamp01(result[0][0][y][x]) * weight;
                green[index] += clamp01(result[0][1][y][x]) * weight;
                blue[index] += clamp01(result[0][2][y][x]) * weight;
                weights[index] += weight;
            }
        }
    }

    private static float feather(int value, int size, boolean atStart, boolean atEnd) {
        float weight = 1f;
        if (atStart && value < OVERLAP) weight = Math.min(weight, (value + 1f) / OVERLAP);
        if (atEnd && value >= size - OVERLAP) {
            weight = Math.min(weight, (size - value) / (float) OVERLAP);
        }
        return Math.max(.001f, weight);
    }

    private static int[] starts(int size) {
        if (size <= TILE) return new int[]{0};
        int count = 1 + (int) Math.ceil((size - TILE) / (double) STRIDE);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = Math.min(i * STRIDE, size - TILE);
        return values;
    }

    private static File model(Context context) throws Exception {
        File target = new File(context.getFilesDir(), MODEL);
        synchronized (MODEL_LOCK) {
            if (target.isFile() && target.length() > 23_000_000L) return target;
            File partial = new File(context.getFilesDir(), MODEL + ".partial");
            try (InputStream input = context.getAssets().open(ASSET);
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) throw new Exception("No se pudo actualizar RT-Focuser");
            if (!partial.renameTo(target)) throw new Exception("No se pudo instalar RT-Focuser");
            return target;
        }
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
