package com.mejorarfotos.app;

import android.graphics.Bitmap;

/**
 * Measures source/candidate quality and limits neural output to plausible detail.
 * The original remains the geometric and colour reference for every result.
 */
public final class ImageQualityGuard {
    private static final int ANALYSIS_MAX_SIDE = 512;
    private static final float DEBLUR_THRESHOLD = 3.45f;

    private ImageQualityGuard() {}

    public static float focusScore(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        Bitmap sample = source;
        if (longest > ANALYSIS_MAX_SIDE) {
            float ratio = ANALYSIS_MAX_SIDE / (float) longest;
            sample = Bitmap.createScaledBitmap(source,
                    Math.max(3, Math.round(source.getWidth() * ratio)),
                    Math.max(3, Math.round(source.getHeight() * ratio)), true);
        }
        int width = sample.getWidth();
        int height = sample.getHeight();
        int[] pixels = new int[width * height];
        sample.getPixels(pixels, 0, width, 0, 0, width, height);
        if (sample != source) sample.recycle();
        int[] luma = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            luma[i] = luminance((color >>> 16) & 0xff, (color >>> 8) & 0xff, color & 0xff);
        }
        return focusScoreForLuma(luma, width, height);
    }

    public static boolean shouldDeblur(Bitmap source) {
        return shouldDeblur(focusScore(source));
    }

    public static boolean shouldDeblur(float focusScore) {
        return focusScore < DEBLUR_THRESHOLD;
    }

    /**
     * Fraction of sampled pixels where the neural candidate strongly diverges from
     * a bicubic reconstruction of the source. It is intentionally resolution
     * independent and bounded to roughly 256 x 256 samples.
     */
    public static float artifactRisk(Bitmap candidate, Bitmap reference) {
        if (candidate == null || reference == null || candidate.isRecycled() || reference.isRecycled()) {
            throw new IllegalArgumentException("Imagen no disponible");
        }
        int width = candidate.getWidth();
        int height = candidate.getHeight();
        int refWidth = reference.getWidth();
        int refHeight = reference.getHeight();
        int stepX = Math.max(1, width / 256);
        int stepY = Math.max(1, height / 256);
        long risky = 0;
        long samples = 0;
        for (int y = 0; y < height; y += stepY) {
            float sy = height <= 1 ? 0f : y * (refHeight - 1f) / (height - 1f);
            int y0 = Math.max(0, Math.min(refHeight - 1, (int) sy));
            int y1 = Math.min(refHeight - 1, y0 + 1);
            float yf = sy - y0;
            for (int x = 0; x < width; x += stepX) {
                float sx = width <= 1 ? 0f : x * (refWidth - 1f) / (width - 1f);
                int x0 = Math.max(0, Math.min(refWidth - 1, (int) sx));
                int x1 = Math.min(refWidth - 1, x0 + 1);
                float xf = sx - x0;
                int top = interpolate(reference.getPixel(x0, y0), reference.getPixel(x1, y0), xf);
                int bottom = interpolate(reference.getPixel(x0, y1), reference.getPixel(x1, y1), xf);
                int baseline = interpolate(top, bottom, yf);
                int neural = candidate.getPixel(x, y);
                int maximumDelta = Math.max(
                        Math.abs(((baseline >>> 16) & 0xff) - ((neural >>> 16) & 0xff)),
                        Math.max(
                                Math.abs(((baseline >>> 8) & 0xff) - ((neural >>> 8) & 0xff)),
                                Math.abs((baseline & 0xff) - (neural & 0xff))));
                if (maximumDelta > 28) risky++;
                samples++;
            }
        }
        return samples == 0 ? 1f : risky / (float) samples;
    }

    /** Protects the candidate in place while keeping memory bounded. */
    public static Bitmap protectInPlace(Bitmap candidate, Bitmap reference,
                                        float neuralStrength, int maxChannelDelta) {
        if (candidate == null || reference == null || candidate.isRecycled() || reference.isRecycled()) {
            throw new IllegalArgumentException("Imagen no disponible");
        }
        if (!candidate.isMutable()) {
            Bitmap mutable = candidate.copy(Bitmap.Config.ARGB_8888, true);
            candidate.recycle();
            candidate = mutable;
        }

        int refWidth = reference.getWidth();
        int refHeight = reference.getHeight();

        int width = candidate.getWidth();
        int height = candidate.getHeight();
        int[] outputRow = new int[width];
        int[] neuralCenter = new int[width];
        int[] referenceRow0 = new int[refWidth];
        int[] referenceRow1 = new int[refWidth];
        int[] x0 = new int[width];
        int[] x1 = new int[width];
        float[] xf = new float[width];
        for (int x = 0; x < width; x++) {
            float sx = width <= 1 ? 0f : x * (refWidth - 1f) / (width - 1f);
            x0[x] = Math.max(0, Math.min(refWidth - 1, (int) sx));
            x1[x] = Math.min(refWidth - 1, x0[x] + 1);
            xf[x] = sx - x0[x];
        }

        for (int y = 0; y < height; y++) {
            candidate.getPixels(neuralCenter, 0, width, 0, y, width, 1);
            float sy = height <= 1 ? 0f : y * (refHeight - 1f) / (height - 1f);
            int y0 = Math.max(0, Math.min(refHeight - 1, (int) sy));
            int y1 = Math.min(refHeight - 1, y0 + 1);
            float yf = sy - y0;
            reference.getPixels(referenceRow0, 0, refWidth, 0, y0, refWidth, 1);
            if (y1 == y0) {
                System.arraycopy(referenceRow0, 0, referenceRow1, 0, refWidth);
            } else {
                reference.getPixels(referenceRow1, 0, refWidth, 0, y1, refWidth, 1);
            }
            for (int x = 0; x < width; x++) {
                int top = interpolate(referenceRow0[x0[x]], referenceRow0[x1[x]], xf[x]);
                int bottom = interpolate(referenceRow1[x0[x]], referenceRow1[x1[x]], xf[x]);
                int baseline = interpolate(top, bottom, yf);
                outputRow[x] = blendNeuralCandidate(
                        baseline, neuralCenter[x], neuralStrength, maxChannelDelta);
            }
            candidate.setPixels(outputRow, 0, width, 0, y, width, 1);
        }
        return candidate;
    }

    /** Never return fewer pixels than the source selected by the user. */
    public static Bitmap ensureMinimumDimensions(Bitmap candidate, Bitmap reference) {
        if (candidate.getWidth() >= reference.getWidth()
                && candidate.getHeight() >= reference.getHeight()) return candidate;
        float factor = Math.max(
                reference.getWidth() / (float) candidate.getWidth(),
                reference.getHeight() / (float) candidate.getHeight());
        Bitmap expanded = Bitmap.createScaledBitmap(
                candidate,
                Math.max(reference.getWidth(), Math.round(candidate.getWidth() * factor)),
                Math.max(reference.getHeight(), Math.round(candidate.getHeight() * factor)),
                true);
        if (expanded != candidate) candidate.recycle();
        return expanded;
    }

    static float focusScoreForLuma(int[] luma, int width, int height) {
        if (width < 3 || height < 3 || luma.length < width * height) return 0f;
        long absoluteLaplacian = 0L;
        int samples = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int center = luma[row + x];
                int laplacian = luma[row + x - 1] + luma[row + x + 1]
                        + luma[row - width + x] + luma[row + width + x] - center * 4;
                absoluteLaplacian += Math.abs(laplacian);
                samples++;
            }
        }
        return samples == 0 ? 0f : absoluteLaplacian / (float) samples;
    }

    static int blendNeuralCandidate(int baseline, int neural,
                                    float strength, int maxChannelDelta) {
        int br = (baseline >>> 16) & 0xff;
        int bg = (baseline >>> 8) & 0xff;
        int bb = baseline & 0xff;
        int nr = (neural >>> 16) & 0xff;
        int ng = (neural >>> 8) & 0xff;
        int nb = neural & 0xff;
        float amount = clamp01(strength);
        int r = protectedChannel(br, Math.round(clamp(nr - br,
                -maxChannelDelta, maxChannelDelta) * amount));
        int g = protectedChannel(bg, Math.round(clamp(ng - bg,
                -maxChannelDelta, maxChannelDelta) * amount));
        int b = protectedChannel(bb, Math.round(clamp(nb - bb,
                -maxChannelDelta, maxChannelDelta) * amount));
        int alpha = (baseline >>> 24) & 0xff;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int protectedChannel(int baseline, int delta) {
        if (baseline <= 1 && delta < 0) return baseline;
        if (baseline >= 254 && delta > 0) return baseline;
        return clamp(baseline + delta, 1, 254);
    }

    private static int interpolate(int left, int right, float amount) {
        int a = Math.round(((left >>> 24) & 0xff) * (1f - amount) + ((right >>> 24) & 0xff) * amount);
        int r = Math.round(((left >>> 16) & 0xff) * (1f - amount) + ((right >>> 16) & 0xff) * amount);
        int g = Math.round(((left >>> 8) & 0xff) * (1f - amount) + ((right >>> 8) & 0xff) * amount);
        int b = Math.round((left & 0xff) * (1f - amount) + (right & 0xff) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int luminance(int r, int g, int b) {
        return (77 * r + 150 * g + 29 * b + 128) >>> 8;
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
