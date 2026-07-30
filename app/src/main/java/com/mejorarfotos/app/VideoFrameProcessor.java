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

    public static final class Selection {
        public final Bitmap bitmap;
        public final long timeUs;
        Selection(Bitmap bitmap, long timeUs) { this.bitmap = bitmap; this.timeUs = timeUs; }
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
            // A narrow 7-frame window missed the sharp moment in short, fast phone videos.
            // Search a wider temporal neighbourhood while keeping memory bounded.
            long span = Math.min(160_000L, Math.max(40_000L, durationUs / 40L));
            for (int i = -10; i <= 10; i++) {
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

    /** Finds a good starting frame by scanning the complete source video. */
    public static Selection bestFrameAcrossVideo(Context context, Uri uri, long durationUs) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap best = null; double bestScore = -1;
        long bestTimeUs = 0;
        try {
            retriever.setDataSource(context, uri);
            int samples = 32;
            for (int i = 0; i < samples; i++) {
                long time = durationUs <= 1 ? 0 : (durationUs - 1) * i / (samples - 1L);
                Bitmap candidate = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                if (candidate == null) continue;
                double score = sharpness(candidate);
                if (score > bestScore) { if (best != null) best.recycle(); best = candidate; bestScore = score; bestTimeUs = time; }
                else candidate.recycle();
            }
        } finally { retriever.release(); }
        if (best == null) throw new Exception("El video no contiene fotogramas legibles");
        return new Selection(best, bestTimeUs);
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
