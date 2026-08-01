package com.mejorarfotos.app;

import android.graphics.Bitmap;

/**
 * Decides when deblurring is useful and limits neural output to plausible detail.
 * The guard transfers mostly luminance detail, preserving the colour of the source.
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

    /** Protects the candidate in place while keeping memory bounded. */
    public static Bitmap protectInPlace(Bitmap candidate, Bitmap reference, int outputScale,
                                        float neuralStrength, int maxLumaDelta) {
        if (candidate == null || reference == null || candidate.isRecycled() || reference.isRecycled()) {
            throw new IllegalArgumentException("Imagen no disponible");
        }
        if (!candidate.isMutable()) {
            Bitmap mutable = candidate.copy(Bitmap.Config.ARGB_8888, true);
            candidate.recycle();
            candidate = mutable;
        }

        int refWidth = Math.max(1, Math.round(candidate.getWidth() / (float) outputScale));
        int refHeight = Math.max(1, Math.round(candidate.getHeight() / (float) outputScale));
        Bitmap compactReference = Bitmap.createScaledBitmap(reference, refWidth, refHeight, true);
        int[] referencePixels = new int[refWidth * refHeight];
        compactReference.getPixels(referencePixels, 0, refWidth, 0, 0, refWidth, refHeight);
        if (compactReference != reference) compactReference.recycle();

        int width = candidate.getWidth();
        int height = candidate.getHeight();
        int[] outputRow = new int[width];
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
            candidate.getPixels(outputRow, 0, width, 0, y, width, 1);
            float sy = height <= 1 ? 0f : y * (refHeight - 1f) / (height - 1f);
            int y0 = Math.max(0, Math.min(refHeight - 1, (int) sy));
            int y1 = Math.min(refHeight - 1, y0 + 1);
            float yf = sy - y0;
            int row0 = y0 * refWidth;
            int row1 = y1 * refWidth;
            for (int x = 0; x < width; x++) {
                int top = interpolate(referencePixels[row0 + x0[x]], referencePixels[row0 + x1[x]], xf[x]);
                int bottom = interpolate(referencePixels[row1 + x0[x]], referencePixels[row1 + x1[x]], xf[x]);
                int baseline = interpolate(top, bottom, yf);
                outputRow[x] = protectPixel(baseline, outputRow[x], neuralStrength, maxLumaDelta);
            }
            candidate.setPixels(outputRow, 0, width, 0, y, width, 1);
        }
        return candidate;
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

    static int protectPixel(int baseline, int neural, float strength, int maxLumaDelta) {
        int br = (baseline >>> 16) & 0xff;
        int bg = (baseline >>> 8) & 0xff;
        int bb = baseline & 0xff;
        int nr = (neural >>> 16) & 0xff;
        int ng = (neural >>> 8) & 0xff;
        int nb = neural & 0xff;
        int baseY = luminance(br, bg, bb);
        int neuralY = luminance(nr, ng, nb);
        int lumaDelta = clamp(neuralY - baseY, -maxLumaDelta, maxLumaDelta);
        int appliedLuma = Math.round(lumaDelta * clamp01(strength));
        float chromaStrength = Math.min(.14f, clamp01(strength) * .20f);
        int redChroma = clamp((nr - neuralY) - (br - baseY), -12, 12);
        int greenChroma = clamp((ng - neuralY) - (bg - baseY), -12, 12);
        int blueChroma = clamp((nb - neuralY) - (bb - baseY), -12, 12);
        int r = protectedChannel(br, appliedLuma + Math.round(redChroma * chromaStrength));
        int g = protectedChannel(bg, appliedLuma + Math.round(greenChroma * chromaStrength));
        int b = protectedChannel(bb, appliedLuma + Math.round(blueChroma * chromaStrength));
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
