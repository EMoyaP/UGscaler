package com.mejorarfotos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Resumable installer for the optional local Stable Diffusion model. */
final class GenerativeModelRepository {
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/EMoyaP/UGscaler/main/generative-catalog.json";
    private static final String FILE_NAME = "dreamshaper-7-lcm-q4_0.gguf";
    private static final long MAX_BYTES = 2_500_000_000L;
    private static final String PREFS = "ugscaler_models";

    interface Listener {
        void onCatalog(GenerativeModelSpec model, boolean online);
        void onProgress(int percent, String phase);
        void onInstalled(GenerativeModelSpec model);
        void onError(String message);
        void onCancelled();
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Future<?> downloadTask;
    private volatile boolean closed;

    GenerativeModelRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void checkForUpdates(Listener listener) {
        worker.execute(() -> {
            try {
                GenerativeModelSpec bundled = readCatalog(
                        context.getAssets().open("generative-catalog.json"));
                post(() -> listener.onCatalog(bundled, false));
            } catch (Exception error) {
                post(() -> listener.onError("No se pudo leer el catálogo generativo"));
                return;
            }
            try {
                HttpURLConnection connection = open(CATALOG_URL, 0L);
                try (InputStream input = connection.getInputStream()) {
                    GenerativeModelSpec remote = readCatalog(input);
                    post(() -> listener.onCatalog(remote, true));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // The embedded catalogue remains available without a connection.
            }
        });
    }

    void download(GenerativeModelSpec model, Listener listener) {
        Future<?> current = downloadTask;
        if (current != null && !current.isDone()) return;
        downloadTask = worker.submit(() -> {
            File partial = partialFile(context);
            try {
                File directory = modelDirectory(context);
                ensureDirectory(directory);
                ensureFreeSpace(model, partial);
                long existing = partial.isFile() ? partial.length() : 0L;
                if (existing > model.bytes) {
                    partial.delete();
                    existing = 0L;
                }
                transfer(model, partial, existing, listener);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                post(() -> listener.onProgress(100, "Verificando el modelo…"));
                if (!model.sha256.equals(sha256(partial))) {
                    partial.delete();
                    throw new Exception("El modelo descargado no supera la verificación");
                }
                File live = modelFile(context);
                File previous = new File(live.getParentFile(), FILE_NAME + ".previous");
                if (previous.isFile()) previous.delete();
                if (live.isFile() && !live.renameTo(previous)) {
                    throw new Exception("No se pudo preparar la actualización");
                }
                if (!partial.renameTo(live)) {
                    if (previous.isFile()) previous.renameTo(live);
                    throw new Exception("No se pudo activar el modelo");
                }
                if (previous.isFile()) previous.delete();
                preferences.edit().putString("generative.version", model.version).apply();
                post(() -> listener.onInstalled(model));
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                post(listener::onCancelled);
            } catch (Exception error) {
                post(() -> listener.onError(error.getMessage() == null
                        ? "No se pudo descargar el modelo" : error.getMessage()));
            }
        });
    }

    void cancelDownload() {
        Future<?> task = downloadTask;
        if (task != null) task.cancel(true);
    }

    boolean isInstalled() {
        File file = modelFile(context);
        return file.isFile() && file.length() > 1_500_000_000L;
    }

    boolean hasUpdate(GenerativeModelSpec model) {
        return isInstalled() && model != null
                && ModelVersion.compare(model.version, installedVersion()) > 0;
    }

    String installedVersion() {
        return isInstalled() ? preferences.getString("generative.version", "0") : "0";
    }

    long partialBytes() {
        File partial = partialFile(context);
        return partial.isFile() ? partial.length() : 0L;
    }

    void remove() {
        cancelDownload();
        File live = modelFile(context);
        File partial = partialFile(context);
        if (live.isFile()) live.delete();
        if (partial.isFile()) partial.delete();
        preferences.edit().remove("generative.version").apply();
    }

    void close() {
        closed = true;
        cancelDownload();
        worker.shutdownNow();
    }

    static File modelFile(Context context) {
        return new File(modelDirectory(context), FILE_NAME);
    }

    private void transfer(GenerativeModelSpec model, File target, long offset,
                          Listener listener) throws Exception {
        HttpURLConnection connection = open(model.url, offset);
        boolean append = offset > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL;
        long received = append ? offset : 0L;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(target, append))) {
            byte[] buffer = new byte[256 * 1024];
            int last = -1;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                received += read;
                if (received > MAX_BYTES) throw new Exception("El modelo supera el límite permitido");
                output.write(buffer, 0, read);
                int percent = (int) Math.min(99L, received * 100L / model.bytes);
                if (percent != last) {
                    last = percent;
                    int value = percent;
                    post(() -> listener.onProgress(value, "Descargando modelo…"));
                }
            }
        } finally {
            connection.disconnect();
        }
        if (received != model.bytes) throw new Exception("La descarga está incompleta");
    }

    private void ensureFreeSpace(GenerativeModelSpec model, File partial) throws Exception {
        long remaining = Math.max(0L, model.bytes - (partial.isFile() ? partial.length() : 0L));
        long required = remaining + 256L * 1024L * 1024L;
        long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        if (available < required) {
            throw new Exception("Se necesitan al menos " + formatBytes(required) + " libres");
        }
    }

    private static HttpURLConnection open(String value, long offset) throws Exception {
        URL url = new URL(value);
        for (int redirects = 0; redirects < 8; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("Solo se permiten descargas HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "UGscaler-Android");
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new Exception("Redirección inválida");
                url = new URL(url, location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new Exception("Servidor no disponible (" + code + ")");
            }
            return connection;
        }
        throw new Exception("Demasiadas redirecciones");
    }

    private static GenerativeModelSpec readCatalog(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new GenerativeModelSpec(new JSONObject(
                    bytes.toString(StandardCharsets.UTF_8.name())));
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private void post(Runnable action) {
        if (!closed) main.post(() -> { if (!closed) action.run(); });
    }

    private static File modelDirectory(Context context) {
        return new File(context.getFilesDir(), "generative-models");
    }

    private static File partialFile(Context context) {
        return new File(modelDirectory(context), FILE_NAME + ".partial");
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new Exception("No se pudo preparar el almacenamiento");
        }
    }

    static String formatBytes(long bytes) {
        return String.format(Locale.getDefault(), "%.2f GB", bytes / 1024d / 1024d / 1024d);
    }
}
