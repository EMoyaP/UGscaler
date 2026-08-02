package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Runs BSRGAN through a 16 KB-compatible NCNN JNI bridge. */
public final class NativeRealEsrgan {
    private static final String ASSET_ROOT = "realesrgan";
    private static final String MODEL = "models-ESRGAN-BSRGAN";
    private static final Object LOCK = new Object();
    private static final boolean NATIVE_READY;

    static {
        boolean ready;
        try {
            System.loadLibrary("ugscaler_ncnn");
            ready = true;
        } catch (Throwable error) {
            ready = false;
        }
        NATIVE_READY = ready;
    }

    private NativeRealEsrgan() {}

    public static boolean isSupportedDevice() {
        if (!NATIVE_READY) return false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static Bitmap enhance(Context context, Bitmap input, int outputScale) throws Exception {
        if (!isSupportedDevice()) {
            throw new UnsupportedOperationException("BSRGAN requiere ARM64");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Procesado cancelado");
        }

        File root = prepare(context);
        File downloaded = ModelRepository.activeModelDirectory(context);
        File model = downloaded != null ? downloaded : new File(root, MODEL);
        File param = new File(model, "x4.param");
        File weights = new File(model, "x4.bin");
        if (!param.isFile() || !weights.isFile()) {
            throw new Exception("El modelo BSRGAN está incompleto");
        }

        Bitmap prepared = input.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap working = ProcessingMemory.fit(
                prepared, ProcessingMemory.realEsrganInputMaxSide(context, outputScale));
        try {
            Bitmap result = nativeEnhance(
                    working, param.getAbsolutePath(), weights.getAbsolutePath(),
                    outputScale, 192, true);
            if (Thread.currentThread().isInterrupted()) {
                recycle(result);
                throw new InterruptedException("Procesado cancelado");
            }
            if (result == null) {
                // Some Android Vulkan drivers are incomplete. NCNN's CPU backend
                // keeps the same model and quality as a deterministic fallback.
                result = nativeEnhance(
                        working, param.getAbsolutePath(), weights.getAbsolutePath(),
                        outputScale, 128, false);
            }
            if (result == null) throw new Exception("BSRGAN no pudo procesar la imagen");
            return result;
        } finally {
            if (working != prepared) recycle(working);
            recycle(prepared);
        }
    }

    public static void cancelActive() {
        if (NATIVE_READY) nativeCancel();
    }

    private static File prepare(Context context) throws Exception {
        File root = new File(context.getFilesDir(), ASSET_ROOT);
        synchronized (LOCK) {
            removeLegacyExecutables(root);
            removeLegacyModels(context, root);
            if (!NATIVE_READY) {
                throw new Exception("El motor BSRGAN no está instalado correctamente");
            }
            File model = new File(root, MODEL + "/x4.bin");
            if (model.exists() && model.length() > 30_000_000L) return root;
            copyTree(context, ASSET_ROOT, root);
            if (!model.exists() || model.length() < 30_000_000L) {
                throw new Exception("No se pudo instalar el modelo BSRGAN");
            }
            return root;
        }
    }

    private static void removeLegacyExecutables(File root) {
        File[] obsolete = {
                new File(root, "realsr-ncnn"),
                new File(root, "libncnn.so"),
                new File(root, "last-run.log")
        };
        for (File file : obsolete) if (file.isFile()) file.delete();
    }

    private static void removeLegacyModels(Context context, File root) {
        File oldModelDirectory = new File(root, "models-Real-ESRGAN");
        File[] obsolete = {
                new File(oldModelDirectory, "x4.bin"),
                new File(oldModelDirectory, "x4.param"),
                new File(context.getFilesDir(), "rt_focuser_wint8_afp32.onnx"),
                new File(context.getFilesDir(), "rt_focuser_wint8_afp32.onnx.partial")
        };
        for (File file : obsolete) if (file.isFile()) file.delete();
        if (oldModelDirectory.isDirectory()) oldModelDirectory.delete();
    }

    private static void copyTree(Context context, String assetPath, File destination)
            throws Exception {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new Exception("No se pudo preparar el motor IA");
        }
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(context, assetPath, destination);
            return;
        }
        for (String child : children) {
            copyTree(context, assetPath + "/" + child, new File(destination, child));
        }
    }

    private static void copyFile(Context context, String assetPath, File destination)
            throws Exception {
        try (InputStream input = context.getAssets().open(assetPath);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static native Bitmap nativeEnhance(
            Bitmap input, String paramPath, String binPath,
            int outputScale, int tileSize, boolean useGpu);
    private static native void nativeCancel();
}
