package com.mejorarfotos.app;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;

/** Centralised memory limits for bitmap and neural processing. */
public final class ProcessingMemory {
    private static final long MIB = 1024L * 1024L;

    private ProcessingMemory() {}

    public static int photoDecodeMaxSide(Context context) {
        int heap = heapClassMb(context);
        if (heap < 320) return 2400;
        if (heap < 512) return 3200;
        return 4096;
    }

    public static int deblurInputMaxSide(Context context) {
        return deblurInputMaxSideFor(heapClassMb(context));
    }

    static int deblurInputMaxSideFor(int heap) {
        if (heap < 320) return 960;
        if (heap < 512) return 1280;
        return 1600;
    }

    public static int recommendedUpscale(Context context, Bitmap bitmap) {
        return recommendedUpscaleFor(
                heapClassMb(context), bitmap.getWidth(), bitmap.getHeight());
    }

    static int recommendedUpscaleFor(int heap, int width, int height) {
        long pixels = (long) width * height;
        int longest = Math.max(width, height);
        long outputLimit;
        if (heap < 320) outputLimit = 8_000_000L;
        else if (heap < 512) outputLimit = 12_000_000L;
        else outputLimit = 20_000_000L;
        return longest <= 1000 && pixels * 16L <= outputLimit ? 4 : 2;
    }

    public static int realEsrganInputMaxSide(Context context, int outputScale) {
        return realEsrganInputMaxSideFor(heapClassMb(context), outputScale);
    }

    static int realEsrganInputMaxSideFor(int heap, int outputScale) {
        if (outputScale >= 4) {
            if (heap < 320) return 768;
            if (heap < 512) return 896;
            return 1024;
        }
        if (heap < 320) return 1024;
        if (heap < 512) return 1280;
        return 1536;
    }

    public static int fallbackInputMaxSide(Context context, int outputScale) {
        int heap = heapClassMb(context);
        if (outputScale >= 4) {
            if (heap < 320) return 560;
            if (heap < 512) return 704;
            return 832;
        }
        if (heap < 320) return 900;
        if (heap < 512) return 1100;
        return 1280;
    }

    public static int heapClassMb(Context context) {
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int configured = manager == null
                ? 256
                : Math.max(manager.getMemoryClass(), manager.getLargeMemoryClass());
        long runtime = Runtime.getRuntime().maxMemory() / MIB;
        return (int) Math.max(configured, runtime);
    }

    public static Bitmap fit(Bitmap source, int maxSide) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Bitmap no disponible");
        }
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxSide) return source;
        float ratio = maxSide / (float) longest;
        return Bitmap.createScaledBitmap(
                source,
                Math.max(1, Math.round(source.getWidth() * ratio)),
                Math.max(1, Math.round(source.getHeight() * ratio)),
                true);
    }
}
