package com.mejorarfotos.app;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Debug;

/** Centralised memory limits for bitmap, OpenCV and neural processing. */
public final class ProcessingMemory {
    private static final long MIB = 1024L * 1024L;

    private ProcessingMemory() {}

    public static int photoDecodeMaxSide(Context context) {
        int heap = heapClassMb(context);
        if (heap < 320) return 2560;
        if (heap < 512) return 3200;
        return 4096;
    }

    public static int videoFusionMaxSide(Context context) {
        int heap = heapClassMb(context);
        if (heap < 320) return 1080;
        if (heap < 512) return 1280;
        return 1440;
    }

    public static int realEsrganInputMaxSide(Context context, int outputScale) {
        int heap = heapClassMb(context);
        if (outputScale >= 4) {
            if (heap < 320) return 640;
            if (heap < 512) return 768;
            return 896;
        }
        if (heap < 320) return 960;
        if (heap < 512) return 1152;
        return 1280;
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

    public static boolean canRunCodeFormer(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        ActivityManager.MemoryInfo info = memoryInfo(context);
        long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
        return heapClassMb(context) >= 512
                && info.totalMem >= 6L * 1024L * MIB
                && info.availMem >= 1400L * MIB
                && pixels <= 8_000_000L
                && javaHeapHeadroom() >= 160L * MIB
                && Debug.getPss() < 900_000;
    }

    public static String codeFormerUnavailableReason(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return "imagen no disponible";
        long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
        if (pixels > 8_000_000L) return "resultado demasiado grande";
        if (heapClassMb(context) < 512) return "memoria de aplicación insuficiente";
        ActivityManager.MemoryInfo info = memoryInfo(context);
        if (info.totalMem < 6L * 1024L * MIB) return "el dispositivo necesita al menos 6 GB de RAM";
        if (info.availMem < 1400L * MIB) return "cierra otras aplicaciones para liberar memoria";
        return "memoria disponible insuficiente";
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

    public static long javaHeapHeadroom() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
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

    private static ActivityManager.MemoryInfo memoryInfo(Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(info);
        return info;
    }
}
