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
    private static final String EXECUTABLE = "librealsr_ncnn_exec.so";
    private static final String NCNN_LIBRARY = "libncnn.so";
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
            int outputScale) throws Exception {
        if (!isSupportedDevice()) {
            throw new UnsupportedOperationException("Real-ESRGAN requiere ARM64");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Procesado cancelado");
        }

        File root = prepare(context);
        File inputFile = File.createTempFile("realesrgan-in-", ".png", context.getCacheDir());
        File outputFile = File.createTempFile("realesrgan-out-", ".png", context.getCacheDir());
        // Do not sharpen before inference. Real-ESRGAN already reconstructs edges;
        // a second RGB sharpening pass creates halos and clipped colours.
        Bitmap prepared = input.copy(Bitmap.Config.ARGB_8888, false);
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

            File executable = installedNativeFile(context, EXECUTABLE);
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
            decode.inMutable = true;
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
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(log);
        // The executable depends on libncnn.so. Both files are installed together
        // by PackageManager, outside the writable app home directory.
        builder.environment().put("LD_LIBRARY_PATH", executable.getParent());
        Process process = builder.start();
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
            removeLegacyExecutables(root);
            File executable = installedNativeFile(context, EXECUTABLE);
            File ncnn = installedNativeFile(context, NCNN_LIBRARY);
            File model = new File(root, MODEL + "/x4.bin");
            if (!executable.isFile() || executable.length() < 7_000_000L
                    || !ncnn.isFile() || ncnn.length() < 10_000_000L) {
                throw new Exception("El motor Real-ESRGAN no est\u00e1 instalado correctamente");
            }
            if (model.exists() && model.length() > 30_000_000L) {
                return root;
            }
            copyTree(context, ASSET_ROOT, root);
            if (!model.exists() || model.length() < 30_000_000L) {
                throw new Exception("No se pudo instalar el modelo Real-ESRGAN");
            }
            return root;
        }
    }

    private static File installedNativeFile(Context context, String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name);
    }

    private static void removeLegacyExecutables(File root) {
        // Versions <= 1.6.0 copied these files to filesDir and attempted to execute
        // them there. Remove only those exact obsolete files during migration.
        File[] obsolete = {
                new File(root, "realsr-ncnn"),
                new File(root, "libncnn.so")
        };
        for (File file : obsolete) {
            if (file.isFile()) file.delete();
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
