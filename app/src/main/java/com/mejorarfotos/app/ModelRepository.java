package com.mejorarfotos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and atomically installs free local models without ever handling photos. */
final class ModelRepository {
    static final String MODEL_ID = "bsrgan-general";
    static final String BUNDLED_VERSION = "1.0.0";
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/EMoyaP/UGscaler/main/model-catalog.json";
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;
    private static final String PREFS = "ugscaler_models";

    interface Listener {
        void onCatalog(ModelSpec model, boolean online);
        void onProgress(int percent);
        void onInstalled(ModelSpec model);
        void onError(String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private volatile boolean closed;

    ModelRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void checkForUpdates(Listener listener) {
        worker.execute(() -> {
            ModelSpec local;
            try {
                local = readCatalog(context.getAssets().open("model-catalog.json"));
                post(() -> listener.onCatalog(local, false));
            } catch (Exception error) {
                post(() -> listener.onError("No se pudo leer el catálogo incluido"));
                return;
            }
            try {
                HttpURLConnection connection = openHttps(CATALOG_URL);
                try (InputStream input = connection.getInputStream()) {
                    ModelSpec remote = readCatalog(input);
                    post(() -> listener.onCatalog(remote, true));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // The bundled catalogue remains fully usable offline.
            }
        });
    }

    void install(ModelSpec model, Listener listener) {
        worker.execute(() -> {
            File downloads = new File(context.getFilesDir(), "model-downloads");
            File partial = new File(downloads, model.id + ".partial");
            File next = new File(downloads, model.id + ".next");
            try {
                ensureDirectory(downloads);
                deleteExact(partial);
                deleteTree(next);
                download(model, partial, listener);
                if (!model.sha256.equals(sha256(partial))) {
                    throw new Exception("La descarga no supera la verificación de seguridad");
                }
                extractModel(partial, next);
                validateFiles(next, model);
                installAtomically(next, model);
                deleteExact(partial);
                post(() -> listener.onInstalled(model));
            } catch (Exception error) {
                deleteExact(partial);
                deleteTree(next);
                post(() -> listener.onError(error.getMessage() == null
                        ? "No se pudo instalar el modelo" : error.getMessage()));
            }
        });
    }

    String installedVersion() {
        File dynamic = activeModelDirectory(context);
        if (dynamic != null) {
            return preferences.getString(MODEL_ID + ".version", BUNDLED_VERSION);
        }
        return BUNDLED_VERSION;
    }

    boolean hasUpdate(ModelSpec model) {
        return model != null && ModelVersion.compare(model.version, installedVersion()) > 0;
    }

    boolean isDownloaded() {
        return activeModelDirectory(context) != null;
    }

    void restoreBundled() {
        deleteTree(modelDirectory(context));
        preferences.edit().remove(MODEL_ID + ".version").apply();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    static File activeModelDirectory(Context context) {
        File directory = modelDirectory(context);
        File bin = new File(directory, "x4.bin");
        File param = new File(directory, "x4.param");
        return bin.isFile() && bin.length() > 30_000_000L
                && param.isFile() && param.length() > 10_000L ? directory : null;
    }

    private void download(ModelSpec model, File target, Listener listener) throws Exception {
        HttpURLConnection connection = openHttps(model.url);
        long expected = connection.getContentLengthLong();
        if (expected > MAX_PACKAGE_BYTES) throw new Exception("El paquete es demasiado grande");
        long total = expected > 0 ? expected : model.bytes;
        long received = 0L;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            int last = -1;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                received += read;
                if (received > MAX_PACKAGE_BYTES) throw new Exception("El paquete es demasiado grande");
                output.write(buffer, 0, read);
                int percent = total <= 0 ? 0 : (int) Math.min(99L, received * 100L / total);
                if (percent != last) {
                    last = percent;
                    int value = percent;
                    post(() -> listener.onProgress(value));
                }
            }
        } finally {
            connection.disconnect();
        }
        if (model.bytes > 0 && received != model.bytes) {
            throw new Exception("La descarga está incompleta");
        }
    }

    private static HttpURLConnection openHttps(String value) throws Exception {
        URL url = new URL(value);
        for (int redirects = 0; redirects < 6; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("Solo se permiten descargas HTTPS");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(45_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "UGscaler-Android");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new Exception("Redirección inválida");
                url = new URL(url, location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new Exception("Servidor no disponible (" + code + ")");
            }
            return connection;
        }
        throw new Exception("Demasiadas redirecciones");
    }

    private void installAtomically(File next, ModelSpec model) throws Exception {
        File live = modelDirectory(context);
        File backup = new File(live.getParentFile(), model.id + ".previous");
        deleteTree(backup);
        if (live.exists() && !live.renameTo(backup)) {
            throw new Exception("No se pudo preparar la actualización");
        }
        if (!next.renameTo(live)) {
            if (backup.exists()) backup.renameTo(live);
            throw new Exception("No se pudo activar el modelo");
        }
        deleteTree(backup);
        preferences.edit().putString(MODEL_ID + ".version", model.version).apply();
    }

    private static void extractModel(File archive, File destination) throws Exception {
        ensureDirectory(destination);
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(archive)))) {
            ZipEntry entry;
            int files = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                if (!"x4.bin".equals(name) && !"x4.param".equals(name)) continue;
                File output = new File(destination, name);
                if (!output.getCanonicalPath().startsWith(root)) {
                    throw new SecurityException("Paquete de modelo inválido");
                }
                try (BufferedOutputStream stream = new BufferedOutputStream(
                        new FileOutputStream(output))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) stream.write(buffer, 0, read);
                }
                files++;
            }
            if (files != 2) throw new Exception("El paquete no contiene el modelo esperado");
        }
    }

    private static void validateFiles(File directory, ModelSpec model) throws Exception {
        File bin = new File(directory, "x4.bin");
        File param = new File(directory, "x4.param");
        if (!model.binSha256.equals(sha256(bin))
                || !model.paramSha256.equals(sha256(param))) {
            throw new Exception("Los archivos del modelo no superan la verificación");
        }
    }

    private static ModelSpec readCatalog(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) bytes.write(buffer, 0, read);
            JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            JSONArray models = root.getJSONArray("models");
            if (models.length() != 1) throw new Exception("Catálogo incompatible");
            return new ModelSpec(models.getJSONObject(0));
        }
    }

    private static String sha256(File file) throws Exception {
        if (!file.isFile()) return "";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private void post(Runnable action) {
        if (!closed) main.post(() -> { if (!closed) action.run(); });
    }

    private static File modelDirectory(Context context) {
        return new File(new File(context.getFilesDir(), "downloaded-models"), MODEL_ID);
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new Exception("No se pudo preparar el almacenamiento");
        }
    }

    private static void deleteExact(File file) {
        if (file.isFile()) file.delete();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
