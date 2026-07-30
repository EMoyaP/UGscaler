package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Runs the bundled ARM64 Real-ESRGAN NCNN backend without a network connection. */
public final class NativeRealEsrgan {
    private static final String ASSET_ROOT = "realesrgan";
    private static final String MODEL = "models-Real-ESRGAN";
    private static final Object LOCK = new Object();

    private NativeRealEsrgan() {}

    public static boolean isSupportedDevice() {
        if (Build.VERSION.SDK_INT < 21) return false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }

    public static Bitmap enhance(Context context, Bitmap input, int outputScale) throws Exception {
        if (!isSupportedDevice()) throw new UnsupportedOperationException("Real-ESRGAN requiere ARM64");
        File root = prepare(context);
        File inputFile = File.createTempFile("realesrgan-in-", ".png", context.getCacheDir());
        File outputFile = File.createTempFile("realesrgan-out-", ".png", context.getCacheDir());
        Bitmap working = fit(input, outputScale >= 4 ? 1000 : 1250);
        try {
            try (OutputStream stream = new FileOutputStream(inputFile)) {
                if (!working.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new Exception("No se pudo preparar la imagen");
            }
            if (outputFile.exists()) outputFile.delete();
            File executable = new File(root, "realsr-ncnn");
            File model = new File(root, MODEL);
            String command = "cd " + quote(root.getAbsolutePath()) +
                    "; export LD_LIBRARY_PATH=" + quote(root.getAbsolutePath()) +
                    "; chmod 700 " + quote(executable.getAbsolutePath()) +
                    "; " + quote(executable.getAbsolutePath()) +
                    " -i " + quote(inputFile.getAbsolutePath()) +
                    " -o " + quote(outputFile.getAbsolutePath()) +
                    " -m " + quote(model.getAbsolutePath()) + " -s 4 -g 0";
            int exit = run(command);
            if (exit != 0 || !outputFile.exists()) {
                if (outputFile.exists()) outputFile.delete();
                // CPU fallback is slower but allows devices without a working Vulkan driver.
                exit = run(command.replace(" -g 0", " -g -1"));
            }
            if (exit != 0 || !outputFile.exists()) throw new Exception("Real-ESRGAN no pudo procesar la imagen");
            Bitmap result = BitmapFactory.decodeFile(outputFile.getAbsolutePath());
            if (result == null) throw new Exception("Salida Real-ESRGAN inválida");
            if (outputScale == 2) {
                Bitmap half = Bitmap.createScaledBitmap(result, working.getWidth() * 2, working.getHeight() * 2, true);
                if (half != result) result.recycle(); result = half;
            }
            return result;
        } finally {
            if (working != input && !working.isRecycled()) working.recycle();
            inputFile.delete(); outputFile.delete();
        }
    }

    private static int run(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        byte[] buffer = new byte[1024]; while (process.getInputStream().read(buffer) != -1) {}
        return process.waitFor();
    }

    private static File prepare(Context context) throws Exception {
        File root = new File(context.getFilesDir(), ASSET_ROOT);
        synchronized (LOCK) {
            File executable = new File(root, "realsr-ncnn");
            File model = new File(root, MODEL + "/x4.bin");
            if (executable.exists() && model.exists() && executable.length() > 7000000 && model.length() > 30000000) return root;
            copyTree(context, ASSET_ROOT, root);
            executable.setExecutable(true, true);
            return root;
        }
    }

    private static void copyTree(Context context, String assetPath, File destination) throws Exception {
        if (!destination.exists() && !destination.mkdirs()) throw new Exception("No se pudo preparar el motor IA");
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) { copyFile(context, assetPath, destination); return; }
        for (String child : children) copyTree(context, assetPath + "/" + child, new File(destination, child));
    }

    private static void copyFile(Context context, String assetPath, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(assetPath); OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static String quote(String path) { return "'" + path.replace("'", "'\\''") + "'"; }

    private static Bitmap fit(Bitmap source, int maxSide) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxSide) return source;
        float ratio = maxSide / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * ratio)),
                Math.max(1, Math.round(source.getHeight() * ratio)), true);
    }
}
