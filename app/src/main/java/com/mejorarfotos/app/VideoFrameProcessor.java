package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

/** Extracts a small temporal window and selects the sharpest frame before restoration. */
public final class VideoFrameProcessor {
    public static final class Info {
        public final long durationUs;
        public final String durationLabel;
        Info(long durationUs) { this.durationUs = durationUs; this.durationLabel = format(durationUs); }
    }

    private VideoFrameProcessor() {}

    public static Info inspect(Context context, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationUs = Math.max(1L, Long.parseLong(raw == null ? "1" : raw) * 1000L);
            return new Info(durationUs);
        } finally { retriever.release(); }
    }

    public static Bitmap sharpestFrame(Context context, Uri uri, long targetUs, long durationUs) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap best = null; double bestScore = -1;
        try {
            retriever.setDataSource(context, uri);
            long span = Math.min(220_000L, Math.max(33_000L, durationUs / 80L));
            for (int i = -3; i <= 3; i++) {
                long time = Math.max(0, Math.min(durationUs - 1, targetUs + i * span));
                Bitmap candidate = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                if (candidate == null) continue;
                double score = sharpness(candidate);
                if (score > bestScore) { if (best != null) best.recycle(); best = candidate; bestScore = score; }
                else candidate.recycle();
            }
        } finally { retriever.release(); }
        if (best == null) throw new Exception("El video no contiene fotogramas legibles");
        return best;
    }

    /** Variance of a small Laplacian approximation; higher means more local detail. */
    private static double sharpness(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int step = Math.max(2, Math.max(w, h) / 320);
        double sum = 0, sum2 = 0; int count = 0;
        for (int y = step; y < h - step; y += step) for (int x = step; x < w - step; x += step) {
            int c = gray(bitmap.getPixel(x, y));
            int lap = 4 * c - gray(bitmap.getPixel(x - step, y)) - gray(bitmap.getPixel(x + step, y)) - gray(bitmap.getPixel(x, y - step)) - gray(bitmap.getPixel(x, y + step));
            sum += lap; sum2 += lap * lap; count++;
        }
        if (count == 0) return 0; double mean = sum / count; return sum2 / count - mean * mean;
    }

    private static int gray(int color) { return (android.graphics.Color.red(color) * 30 + android.graphics.Color.green(color) * 59 + android.graphics.Color.blue(color) * 11) / 100; }
    private static String format(long us) { long total = us / 1_000_000L; return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60); }
}
