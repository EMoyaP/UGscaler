package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Runs the converted NAFNet GoPro motion-deblurring model locally on Android. */
public final class NativeNafNet {
    private static final String ASSET = "nafnet/NAFNet-GoPro-width32.onnx";
    private static final String FILE_NAME = "NAFNet-GoPro-width32.onnx";
    private static final int TILE = 256, OVERLAP = 32;
    private static final Object LOCK = new Object();
    private static OrtEnvironment environment;
    private static OrtSession session;

    private NativeNafNet() {}

    public static Bitmap enhance(Context context, Bitmap input) throws Exception {
        Bitmap working = fit(input, 1400);
        try {
            OrtSession model = getSession(context);
            int width = working.getWidth(), height = working.getHeight();
            int[] source = new int[width * height]; working.getPixels(source, 0, width, 0, 0, width, height);
            float[] accum = new float[width * height * 3];
            float[] weights = new float[width * height];
            for (int y : positions(height)) for (int x : positions(width)) {
                int left = Math.min(x, Math.max(0, width - TILE));
                int top = Math.min(y, Math.max(0, height - TILE));
                float[] pixels = new float[3 * TILE * TILE];
                for (int py = 0; py < TILE; py++) for (int px = 0; px < TILE; px++) {
                    int color = source[(top + py) * width + left + px];
                    int index = py * TILE + px;
                    pixels[index] = android.graphics.Color.red(color) / 255f;
                    pixels[TILE * TILE + index] = android.graphics.Color.green(color) / 255f;
                    pixels[2 * TILE * TILE + index] = android.graphics.Color.blue(color) / 255f;
                }
                float[][][][] prediction;
                try (OnnxTensor tensor = OnnxTensor.createTensor(getEnvironment(), FloatBuffer.wrap(pixels), new long[]{1, 3, TILE, TILE});
                     OrtSession.Result result = model.run(Collections.singletonMap("input", tensor))) {
                    prediction = (float[][][][]) result.get(0).getValue();
                }
                boolean valid = isValid(prediction);
                for (int py = 0; py < TILE; py++) for (int px = 0; px < TILE; px++) {
                    int index = py * TILE + px;
                    float weight = blendWeight(px, py);
                    int sourceIndex = (top + py) * width + left + px;
                    int color = source[sourceIndex];
                    float r = valid ? prediction[0][0][py][px] : android.graphics.Color.red(color) / 255f;
                    float g = valid ? prediction[0][1][py][px] : android.graphics.Color.green(color) / 255f;
                    float b = valid ? prediction[0][2][py][px] : android.graphics.Color.blue(color) / 255f;
                    int outIndex = sourceIndex * 3;
                    accum[outIndex] += clamp01(r) * weight;
                    accum[outIndex + 1] += clamp01(g) * weight;
                    accum[outIndex + 2] += clamp01(b) * weight;
                    weights[sourceIndex] += weight;
                }
            }
            int[] resultPixels = new int[width * height];
            for (int i = 0; i < resultPixels.length; i++) {
                float weight = Math.max(.0001f, weights[i]);
                resultPixels[i] = android.graphics.Color.argb(255,
                        Math.round(clamp01(accum[i * 3] / weight) * 255f),
                        Math.round(clamp01(accum[i * 3 + 1] / weight) * 255f),
                        Math.round(clamp01(accum[i * 3 + 2] / weight) * 255f));
            }
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            result.setPixels(resultPixels, 0, width, 0, 0, width, height);
            return result;
        } finally {
            if (working != input && !working.isRecycled()) working.recycle();
        }
    }

    private static boolean isValid(float[][][][] value) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < 3; c++) for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            float v = value[0][c][y][x]; if (!Float.isFinite(v)) return false; min = Math.min(min, v); max = Math.max(max, v);
        }
        return min >= -.15f && max <= 1.15f;
    }

    private static float blendWeight(int x, int y) {
        float wx = x < OVERLAP ? (x + 1f) / (OVERLAP + 1f) : x >= TILE - OVERLAP ? (TILE - x) / (OVERLAP + 1f) : 1f;
        float wy = y < OVERLAP ? (y + 1f) / (OVERLAP + 1f) : y >= TILE - OVERLAP ? (TILE - y) / (OVERLAP + 1f) : 1f;
        return Math.max(.05f, wx * wy);
    }

    private static List<Integer> positions(int length) {
        List<Integer> result = new ArrayList<>();
        if (length <= TILE) { result.add(0); return result; }
        for (int p = 0; p <= length - TILE; p += TILE - OVERLAP) result.add(p);
        int last = length - TILE; if (!result.contains(last)) result.add(last);
        return result;
    }

    private static OrtEnvironment getEnvironment() { if (environment == null) environment = OrtEnvironment.getEnvironment(); return environment; }

    private static OrtSession getSession(Context context) throws Exception {
        synchronized (LOCK) {
            if (session != null) return session;
            File model = new File(context.getFilesDir(), FILE_NAME);
            if (!model.exists() || model.length() < 30_000_000L) copyAsset(context, model);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions(); options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            session = getEnvironment().createSession(model.getAbsolutePath(), options);
            return session;
        }
    }

    private static void copyAsset(Context context, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(ASSET); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static Bitmap fit(Bitmap source, int maxSide) {
        int longest = Math.max(source.getWidth(), source.getHeight()); if (longest <= maxSide) return source;
        float ratio = maxSide / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * ratio)), Math.max(1, Math.round(source.getHeight() * ratio)), true);
    }
}
