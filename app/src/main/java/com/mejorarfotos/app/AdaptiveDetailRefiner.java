package com.mejorarfotos.app;

import android.graphics.Bitmap;

/**
 * Bounded, low-memory detail recovery applied only while the candidate is softer
 * than the user's source. It never changes a channel by more than 12 levels per pass.
 */
final class AdaptiveDetailRefiner {
    private static final int MAX_PASSES = 3;
    private static final int MAX_DELTA = 12;
    private static final int THRESHOLD = 2;
    private static final float AMOUNT = 1.25f;

    private AdaptiveDetailRefiner() {}

    static Bitmap refine(Bitmap candidate, Bitmap reference) {
        if (candidate == null || reference == null
                || candidate.isRecycled() || reference.isRecycled()) {
            throw new IllegalArgumentException("Imagen no disponible");
        }
        if (!candidate.isMutable()) {
            Bitmap mutable = candidate.copy(Bitmap.Config.ARGB_8888, true);
            candidate.recycle();
            candidate = mutable;
        }
        float target = ImageQualityGuard.focusScore(reference) * 1.005f;
        float score = ImageQualityGuard.focusScore(candidate);
        for (int pass = 0; pass < MAX_PASSES && score < target; pass++) {
            sharpenPass(candidate);
            score = ImageQualityGuard.focusScore(candidate);
        }
        return candidate;
    }

    private static void sharpenPass(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 3 || height < 3) return;
        int[] previous = new int[width];
        int[] current = new int[width];
        int[] next = new int[width];
        int[] output = new int[width];
        bitmap.getPixels(previous, 0, width, 0, 0, width, 1);
        bitmap.getPixels(current, 0, width, 0, 1, width, 1);
        bitmap.getPixels(next, 0, width, 0, 2, width, 1);
        for (int y = 1; y < height - 1; y++) {
            System.arraycopy(current, 0, output, 0, width);
            for (int x = 1; x < width - 1; x++) {
                int center = current[x];
                int alpha = (center >>> 24) & 0xff;
                int red = refineChannel(
                        (center >>> 16) & 0xff,
                        (previous[x] >>> 16) & 0xff,
                        (next[x] >>> 16) & 0xff,
                        (current[x - 1] >>> 16) & 0xff,
                        (current[x + 1] >>> 16) & 0xff);
                int green = refineChannel(
                        (center >>> 8) & 0xff,
                        (previous[x] >>> 8) & 0xff,
                        (next[x] >>> 8) & 0xff,
                        (current[x - 1] >>> 8) & 0xff,
                        (current[x + 1] >>> 8) & 0xff);
                int blue = refineChannel(
                        center & 0xff,
                        previous[x] & 0xff,
                        next[x] & 0xff,
                        current[x - 1] & 0xff,
                        current[x + 1] & 0xff);
                output[x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1);
            int[] reuse = previous;
            previous = current;
            current = next;
            next = reuse;
            if (y + 2 < height) {
                bitmap.getPixels(next, 0, width, 0, y + 2, width, 1);
            }
        }
    }

    static int refineChannel(int center, int up, int down, int left, int right) {
        int average = (up + down + left + right + 2) / 4;
        int detail = center - average;
        if (Math.abs(detail) <= THRESHOLD) return center;
        int delta = Math.round(detail * AMOUNT);
        delta = Math.max(-MAX_DELTA, Math.min(MAX_DELTA, delta));
        return Math.max(0, Math.min(255, center + delta));
    }
}
