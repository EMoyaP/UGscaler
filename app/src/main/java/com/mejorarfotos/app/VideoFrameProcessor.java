package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.util.Arrays;

/** Selects exact video frames using a robust detail score that ignores flat borders. */
public final class VideoFrameProcessor {
    public static final class Info {
        public final long durationUs;
        public final int frameCount;
        public final String durationLabel;

        Info(long durationUs, int frameCount) {
            this.durationUs = durationUs;
            this.frameCount = frameCount;
            this.durationLabel = format(durationUs);
        }
    }

    public static final class Selection {
        public final Bitmap bitmap;
        public final long timeUs;
        public final int frameIndex;

        Selection(Bitmap bitmap, long timeUs, int frameIndex) {
            this.bitmap = bitmap;
            this.timeUs = timeUs;
            this.frameIndex = frameIndex;
        }
    }

    private VideoFrameProcessor() {}

    public static Info inspect(Context context, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String rawDuration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationUs = Math.max(
                    1L, Long.parseLong(rawDuration == null ? "1" : rawDuration) * 1000L);
            int frameCount = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                String rawFrames = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                try {
                    frameCount = Math.max(0, Integer.parseInt(
                            rawFrames == null ? "0" : rawFrames));
                } catch (NumberFormatException ignored) {}
            }
            return new Info(durationUs, frameCount);
        } finally {
            retriever.release();
        }
    }

    /**
     * Searches exact neighbouring frames. A mild distance penalty keeps the chosen
     * composition close to the user's requested moment while still escaping blur.
     */
    public static Selection sharpestFrame(
            Context context, Uri uri, long targetUs, Info info) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap best = null;
        double bestScore = -1;
        long bestTimeUs = targetUs;
        int bestFrameIndex = -1;
        try {
            retriever.setDataSource(context, uri);
            if (supportsExactFrames(info)) {
                int targetIndex = indexForTime(targetUs, info);
                int radius = Math.min(10, Math.max(5, Math.round(info.frameCount * .11f)));
                for (int offset = -radius; offset <= radius; offset++) {
                    int index = clamp(targetIndex + offset, 0, info.frameCount - 1);
                    Bitmap candidate = readFrame(
                            retriever, index, timeForIndex(index, info), info);
                    if (candidate == null) continue;
                    double score = sharpness(candidate) * Math.max(.62, 1.0 - Math.abs(offset) * .035);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = candidate;
                        bestScore = score;
                        bestFrameIndex = index;
                        bestTimeUs = timeForIndex(index, info);
                    } else {
                        candidate.recycle();
                    }
                }
            } else {
                long span = Math.min(80_000L, Math.max(35_000L, info.durationUs / 55L));
                for (int offset = -8; offset <= 8; offset++) {
                    long time = clamp(
                            targetUs + offset * span, 0L, Math.max(0L, info.durationUs - 1L));
                    Bitmap candidate = retriever.getFrameAtTime(
                            time, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (candidate == null) continue;
                    double score = sharpness(candidate) * Math.max(.68, 1.0 - Math.abs(offset) * .04);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = candidate;
                        bestScore = score;
                        bestTimeUs = time;
                    } else {
                        candidate.recycle();
                    }
                }
            }
        } finally {
            retriever.release();
        }
        if (best == null) throw new Exception("El video no contiene fotogramas legibles");
        return new Selection(best, bestTimeUs, bestFrameIndex);
    }

    /**
     * Scans the source with exact frame indices when Android exposes them.
     * The centre bias avoids selecting sharp end cards or a frame after the subject left.
     */
    public static Selection bestFrameAcrossVideo(
            Context context, Uri uri, Info info) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap best = null;
        double bestScore = -1;
        long bestTimeUs = 0;
        int bestFrameIndex = -1;
        try {
            retriever.setDataSource(context, uri);
            if (supportsExactFrames(info)) {
                int samples = Math.min(72, info.frameCount);
                for (int sample = 0; sample < samples; sample++) {
                    int index = samples == 1
                            ? 0
                            : (int) ((info.frameCount - 1L) * sample / (samples - 1L));
                    Bitmap candidate = readFrame(
                            retriever, index, timeForIndex(index, info), info);
                    if (candidate == null) continue;
                    double centreDistance = Math.abs(
                            index - (info.frameCount - 1) / 2.0) /
                            Math.max(1.0, (info.frameCount - 1) / 2.0);
                    double score = sharpness(candidate) * (1.0 - .50 * centreDistance);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = candidate;
                        bestScore = score;
                        bestFrameIndex = index;
                        bestTimeUs = timeForIndex(index, info);
                    } else {
                        candidate.recycle();
                    }
                }
            } else {
                int samples = 40;
                for (int sample = 0; sample < samples; sample++) {
                    long time = info.durationUs <= 1
                            ? 0
                            : (info.durationUs - 1) * sample / (samples - 1L);
                    Bitmap candidate = retriever.getFrameAtTime(
                            time, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (candidate == null) continue;
                    double centreDistance = Math.abs(sample - (samples - 1) / 2.0) /
                            Math.max(1.0, (samples - 1) / 2.0);
                    double score = sharpness(candidate) * (1.0 - .50 * centreDistance);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = candidate;
                        bestScore = score;
                        bestTimeUs = time;
                    } else {
                        candidate.recycle();
                    }
                }
            }
        } finally {
            retriever.release();
        }
        if (best == null) throw new Exception("El video no contiene fotogramas legibles");
        return new Selection(best, bestTimeUs, bestFrameIndex);
    }

    /**
     * Robust focus measure: 90th-percentile Laplacian in the best informative tiles.
     * It excludes the lower border, which in phone videos is often a sharp dashboard
     * that otherwise wins over a blurred person or object.
     */
    private static double sharpness(Bitmap bitmap) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap scoring = bitmap;
        if (longest > 720) {
            float ratio = 720f / longest;
            scoring = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)),
                    true);
        }
        try {
            return sharpnessPixels(scoring);
        } finally {
            if (scoring != bitmap && !scoring.isRecycled()) scoring.recycle();
        }
    }

    private static double sharpnessPixels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 12 || height < 12) return 0;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int step = Math.max(2, Math.max(width, height) / 360);
        int left = Math.max(step, Math.round(width * .03f));
        int right = Math.min(width - step, Math.round(width * .97f));
        int top = Math.max(step, Math.round(height * .10f));
        int bottom = Math.min(height - step, Math.round(height * .76f));
        int columns = 5;
        int rows = 6;
        double[] tileScores = new double[columns * rows];
        int scoreCount = 0;

        for (int row = 0; row < rows; row++) {
            int y0 = top + (bottom - top) * row / rows;
            int y1 = top + (bottom - top) * (row + 1) / rows;
            for (int column = 0; column < columns; column++) {
                int x0 = left + (right - left) * column / columns;
                int x1 = left + (right - left) * (column + 1) / columns;
                int capacity = Math.max(1,
                        ((x1 - x0) / step + 1) * ((y1 - y0) / step + 1));
                int[] responses = new int[capacity];
                int count = 0;
                double luminance = 0;
                double luminance2 = 0;

                for (int y = Math.max(top, y0 + step); y < Math.min(bottom, y1 - step); y += step) {
                    for (int x = Math.max(left, x0 + step); x < Math.min(right, x1 - step); x += step) {
                        int center = gray(pixels[y * width + x]);
                        int laplacian = 4 * center
                                - gray(pixels[y * width + x - step])
                                - gray(pixels[y * width + x + step])
                                - gray(pixels[(y - step) * width + x])
                                - gray(pixels[(y + step) * width + x]);
                        if (count < responses.length) responses[count++] = Math.min(1020, Math.abs(laplacian));
                        luminance += center;
                        luminance2 += center * center;
                    }
                }
                if (count < 16) continue;
                double mean = luminance / count;
                double variance = luminance2 / count - mean * mean;
                if (variance < 36.0) continue;
                Arrays.sort(responses, 0, count);
                tileScores[scoreCount++] = responses[Math.min(
                        count - 1, Math.max(0, Math.round((count - 1) * .90f)))];
            }
        }

        if (scoreCount == 0) return 0;
        Arrays.sort(tileScores, 0, scoreCount);
        int chosen = Math.min(8, scoreCount);
        double sum = 0;
        for (int i = scoreCount - chosen; i < scoreCount; i++) sum += tileScores[i];
        return sum / chosen;
    }

    private static boolean supportsExactFrames(Info info) {
        return Build.VERSION.SDK_INT >= 28 && info.frameCount > 0;
    }

    private static Bitmap readFrame(
            MediaMetadataRetriever retriever, int index, long timeUs, Info info) {
        if (supportsExactFrames(info)) {
            try {
                Bitmap exact = retriever.getFrameAtIndex(index);
                if (exact != null) return exact;
            } catch (RuntimeException ignored) {
                // Some vendor codecs report a frame count but reject indexed reads.
            }
        }
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private static int indexForTime(long timeUs, Info info) {
        if (info.frameCount <= 1 || info.durationUs <= 1) return 0;
        return clamp((int) Math.round(
                timeUs * (info.frameCount - 1.0) / (info.durationUs - 1.0)),
                0, info.frameCount - 1);
    }

    private static long timeForIndex(int index, Info info) {
        if (info.frameCount <= 1) return 0;
        return Math.max(0L, (info.durationUs - 1L) * index / (info.frameCount - 1L));
    }

    private static int gray(int color) {
        return (android.graphics.Color.red(color) * 30
                + android.graphics.Color.green(color) * 59
                + android.graphics.Color.blue(color) * 11) / 100;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(long us) {
        long total = us / 1_000_000L;
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
