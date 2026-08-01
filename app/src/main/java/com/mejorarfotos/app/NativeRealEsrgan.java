package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Runs the bundled ARM64 Real-ESRGAN NCNN backend without a network connection. */
public final class NativeRealEsrgan {
    private static final String ASSET_ROOT = "realesrgan";
    private static final String MODEL = "models-Real-ESRGAN";
    private static final Object LOCK = new Object();
    private static volatile Process activeProcess;

    private NativeRealEsrgan() {}

    public static boolean isSupportedDevice() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static Bitmap enhance(
            Context context,
            Bitmap input,
            int outputScale,
            int profile,
            int noise,
            int detail,
            int sharpen) throws Exception {
        if (!isSupportedDevice()) {
            throw new UnsupportedOperationException("Real-ESRGAN requiere ARM64");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Procesado cancelado");
        }

        File root = prepare(context);
        File inputFile = File.createTempFile("realesrgan-in-", ".png", context.getCacheDir());
        File outputFile = File.createTempFile("realesrgan-out-", ".png", context.getCacheDir());
        Bitmap prepared = ImageEnhancer.prepareForAi(input, profile, noise, detail, sharpen);
        Bitmap working = ProcessingMemory.fit(
                prepared, ProcessingMemory.realEsrganInputMaxSide(context, outputScale));
        try {
            try (OutputStream stream = new FileOutputStream(inputFile)) {
                if (!working.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new Exception("No se pudo preparar la imagen");
                }
            }
            if (outputFile.exists() && !outputFile.delete()) {
                throw new Exception("No se pudo preparar la salida temporal");
            }

            File executable = new File(root, "realsr-ncnn");
            File model = new File(root, MODEL);
            int exit = run(root, executable, model, inputFile, outputFile, false);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Procesado cancelado");
            }
            if (exit != 0 || !outputFile.exists()) {
                if (outputFile.exists()) outputFile.delete();
                // CPU fallback is slower but works without a compatible Vulkan driver.
                exit = run(root, executable, model, inputFile, outputFile, true);
            }
            if (exit != 0 || !outputFile.exists()) {
                throw new Exception("Real-ESRGAN no pudo procesar la imagen");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Procesado cancelado");
            }

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // NCNN always emits x4. Decode directly at half size for a requested x2
            // result to avoid holding both the x4 and x2 bitmaps in memory.
            decode.inSampleSize = outputScale == 2 ? 2 : 1;
            Bitmap result = BitmapFactory.decodeFile(outputFile.getAbsolutePath(), decode);
            if (result == null) throw new Exception("Salida Real-ESRGAN inválida");
            return result;
        } finally {
            if (working != prepared && !working.isRecycled()) working.recycle();
            if (!prepared.isRecycled()) prepared.recycle();
            inputFile.delete();
            outputFile.delete();
        }
    }

    public static void cancelActive() {
        Process process = activeProcess;
        if (process != null) process.destroy();
    }

    private static int run(
            File root,
            File executable,
            File model,
            File input,
            File output,
            boolean cpu) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("-i"); command.add(input.getAbsolutePath());
        command.add("-o"); command.add(output.getAbsolutePath());
        command.add("-m"); command.add(model.getAbsolutePath());
        command.add("-s"); command.add("4");
        command.add("-t"); command.add("192");
        command.add("-j"); command.add("1:1:1");
        command.add("-g"); command.add(cpu ? "-1" : "0");

        File log = new File(root, "last-run.log");
        Process process = new ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        activeProcess = process;
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroy();
            throw interrupted;
        } finally {
            activeProcess = null;
        }
    }

    private static File prepare(Context context) throws Exception {
        File root = new File(context.getFilesDir(), ASSET_ROOT);
        synchronized (LOCK) {
            File executable = new File(root, "realsr-ncnn");
            File model = new File(root, MODEL + "/x4.bin");
            if (executable.exists()
                    && model.exists()
                    && executable.length() > 7_000_000
                    && model.length() > 30_000_000) {
                executable.setExecutable(true, true);
                return root;
            }
            copyTree(context, ASSET_ROOT, root);
            executable.setExecutable(true, true);
            return root;
        }
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
}
