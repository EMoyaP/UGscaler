package com.mejorarfotos.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** UGscaler editor: photo restoration and frame recovery from video. */
public class MainActivity extends Activity {
    private static final String TAG = "UGscaler";
    private static final int PICK_IMAGE = 10, PICK_VIDEO = 11, TAKE_PHOTO = 12;
    private static final int CAMERA_PERMISSION = 13, STORAGE_PERMISSION = 14;
    private final int background = Color.rgb(13, 16, 15), panel = Color.rgb(25, 31, 28);
    private final int panel2 = Color.rgb(32, 40, 35), ink = Color.rgb(239, 244, 239);
    private final int muted = Color.rgb(157, 173, 161), accent = Color.rgb(214, 243, 106);
    private CropImageView cropView;
    private TextView status, resolution, compareLabel, videoInfo;
    private View videoControlsView;
    private Button enhanceButton, saveButton, compareButton, photoMode, videoMode, cropButton;
    private SeekBar noiseSeek, detailSeek, sharpSeek, frameSeek;
    private Switch faceRestoreSwitch;
    private Bitmap currentBitmap, enhancedBitmap, originalCrop;
    private Uri cameraUri, currentVideoUri;
    private int scaleFactor = 2, profile = 0;
    private long videoDurationUs;
    private VideoFrameProcessor.Info currentVideoInfo;
    private boolean videoModeEnabled, loadingFrame, editingOriginal = true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger operationGeneration = new AtomicInteger();
    private volatile Future<?> activeTask;
    private volatile boolean processing;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(12), dp(7), dp(12), dp(8));
        root.addView(appBar(), fixed(50));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.addView(modeBar(), spacedFixed(52, 8));
        editor.addView(workspace(), spacedFixed(viewportHeightDp(), 8));
        editor.addView(cropControls(), spacedWrap(8));
        editor.addView(sourceBar(), spacedFixed(52, 8));
        videoControlsView = videoControls();
        editor.addView(videoControlsView, spacedWrap(8));
        status = text("Listo para mejorar · elige una foto o un video", 12, muted);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setMinHeight(dp(44));
        status.setBackground(round(panel2, 12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        editor.addView(status, spacedWrap(10));
        editor.addView(
                section(
                        "MEJORA INTELIGENTE",
                        "Procesado local y privado · el original nunca se sobrescribe"),
                spacedWrap(6));
        editor.addView(presets(), spacedFixed(52, 8));
        editor.addView(scaleControls(), spacedWrap(8));
        editor.addView(faceControls(), spacedWrap(8));
        noiseSeek = addSlider(editor, "Reducir ruido", "Limpia grano y compresion", 18);
        detailSeek = addSlider(editor, "Recuperar detalle", "Textura natural sin halos", 64);
        sharpSeek = addSlider(editor, "Enfoque", "Define bordes y microcontraste", 58);
        editor.addView(
                section("REVISIÓN", "Desliza la línea del visor para comparar"),
                spacedWrap(4));
        resolution = text("Salida estimada · todavia no procesada", 12, muted);
        resolution.setPadding(dp(2), dp(8), dp(2), dp(12));
        editor.addView(resolution, spacedWrap(4));
        scroll.addView(editor, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(4);
        scrollParams.bottomMargin = dp(6);
        root.addView(scroll, scrollParams);
        root.addView(actions(), fixed(58));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(12), bars.top + dp(7), dp(12), bars.bottom + dp(8));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        refreshModeUi();
    }

    private View appBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("UGscaler", 19, ink); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        Button fresh = button("Nuevo", false); fresh.setOnClickListener(v -> clearEditor());
        bar.addView(fresh, fixedWidth(78));
        TextView tag = text("IA LOCAL", 10, accent); tag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER); tag.setPadding(dp(8), 0, dp(8), 0); tag.setBackground(round(Color.rgb(38, 48, 35), 20));
        LinearLayout.LayoutParams tagParams = fixedWidth(78); tagParams.leftMargin = dp(7); bar.addView(tag, tagParams);
        return bar;
    }

    private View modeBar() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        photoMode = button("Foto", true); videoMode = button("Video", false);
        row.addView(photoMode, weight(1, 0, 5)); row.addView(videoMode, weight(1, 0, 0));
        photoMode.setOnClickListener(v -> setVideoMode(false));
        videoMode.setOnClickListener(v -> setVideoMode(true));
        return row;
    }

    private View workspace() {
        FrameLayout frame = new FrameLayout(this); frame.setBackground(round(Color.rgb(19, 24, 21), 16)); frame.setElevation(dp(2));
        frame.setClipToOutline(true);
        cropView = new CropImageView(this); cropView.setContentDescription("Visor de imagen, fotograma y recorte");
        frame.addView(cropView, new FrameLayout.LayoutParams(-1, -1));
        compareLabel = text("VISTA ORIGINAL", 10, ink); compareLabel.setGravity(Gravity.CENTER);
        compareLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD); compareLabel.setBackground(round(Color.argb(180, 18, 23, 20), 9));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(125), dp(27), Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(10), dp(10), 0, 0); frame.addView(compareLabel, badgeParams);
        compareButton = button("Comparar antes / despues", false); compareButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams compareParams = new FrameLayout.LayoutParams(dp(205), dp(39), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        compareParams.bottomMargin = dp(12); frame.addView(compareButton, compareParams);
        compareButton.setOnClickListener(v -> {
            cropView.toggleComparison();
            compareLabel.setText(cropView.isComparing() ? "COMPARACION · arrastra" : "VISTA RESULTADO");
        });
        return frame;
    }

    private View cropControls() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(round(panel2, 13));

        TextView title = text("RECORTE DE LA IMAGEN ORIGINAL", 11, accent);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title, fixed(19));
        TextView help = text("Arrastra el interior para moverlo y las esquinas para ajustar", 11, muted);
        card.addView(help, fixed(21));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        cropButton = button("Activar recorte", true);
        Button fullButton = button("Usar imagen completa", false);
        actions.addView(cropButton, weight(1, 0, 6));
        actions.addView(fullButton, weight(1, 0, 0));
        card.addView(actions, new LinearLayout.LayoutParams(-1, dp(46)));

        cropButton.setOnClickListener(v -> startCrop());
        fullButton.setOnClickListener(v -> useFullOriginal());
        return card;
    }

    private View sourceBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        Button openPhoto = button("Abrir foto", false), openVideo = button("Abrir video", false), camera = button("Camara", false);
        bar.addView(openPhoto, weight(1, 0, 5)); bar.addView(openVideo, weight(1, 0, 5)); bar.addView(camera, weight(1, 0, 0));
        openPhoto.setOnClickListener(v -> { setVideoMode(false); chooseImage(); });
        openVideo.setOnClickListener(v -> { setVideoMode(true); chooseVideo(); });
        camera.setOnClickListener(v -> takePhoto());
        return bar;
    }

    private View videoControls() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout labels = new LinearLayout(this); labels.setGravity(Gravity.CENTER_VERTICAL);
        videoInfo = text("Video · buscando el mejor fotograma", 11, muted); labels.addView(videoInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = text("índice exacto", 10, accent); hint.setGravity(Gravity.END); labels.addView(hint, fixedWidth(105));
        box.addView(labels, fixed(22));
        frameSeek = new SeekBar(this); frameSeek.setMax(1000); frameSeek.setProgress(500); frameSeek.setContentDescription("Seleccionar fotograma del video");
        box.addView(frameSeek, fixed(28));
        frameSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { if (fromUser && videoDurationUs > 0) videoInfo.setText("Fotograma aprox. " + Math.round(value / 1000f * 100) + "%"); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { if (videoModeEnabled && currentVideoUri != null) loadVideoFrame(bar.getProgress()); }
        });
        return box;
    }

    private View presets() {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Auto", "Retrato", "Paisaje", "Texto"};
        for (int i = 0; i < names.length; i++) {
            final int value = i; Button preset = button(names[i], i == 0);
            LinearLayout.LayoutParams p = fixedWidth(92); if (i > 0) p.leftMargin = dp(7); row.addView(preset, p);
            preset.setOnClickListener(v -> { profile = value; selectPreset(row, value); applyProfile(value); });
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1)); return scroll;
    }

    private void selectPreset(LinearLayout row, int selected) { for (int i = 0; i < row.getChildCount(); i++) style((Button) row.getChildAt(i), i == selected); }

    private void applyProfile(int value) {
        if (value == 1) { noiseSeek.setProgress(38); detailSeek.setProgress(49); sharpSeek.setProgress(42); }
        else if (value == 2) { noiseSeek.setProgress(14); detailSeek.setProgress(76); sharpSeek.setProgress(68); }
        else if (value == 3) { noiseSeek.setProgress(28); detailSeek.setProgress(86); sharpSeek.setProgress(78); }
        else { noiseSeek.setProgress(18); detailSeek.setProgress(64); sharpSeek.setProgress(58); }
        status.setText("Preset aplicado · pulsa Mejorar con IA para generar una nueva version");
    }

    private View scaleControls() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(round(panel2, 13));

        TextView title = text("ESCALA DE SALIDA", 11, accent);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title, fixed(19));
        TextView note = text("2× recomendado · 4× aumenta resolución y consumo de memoria", 11, muted);
        card.addView(note, fixed(21));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button x2 = button("2x", true), x4 = button("4x", false);
        Button reset = button("Restablecer", false);
        row.addView(x2, weight(1, 0, 6));
        row.addView(x4, weight(1, 0, 6));
        row.addView(reset, weight(1.35f, 0, 0));
        card.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
        x2.setOnClickListener(v -> { scaleFactor = 2; style(x2, true); style(x4, false); });
        x4.setOnClickListener(v -> { scaleFactor = 4; style(x4, true); style(x2, false); });
        reset.setOnClickListener(v -> { profile = 0; scaleFactor = 2; style(x2, true); style(x4, false); applyProfile(0); status.setText("Ajustes restablecidos · elige un preset o personaliza la mejora"); });
        return card;
    }

    private View faceControls() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.setBackground(round(panel2, 13));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Rostro IA · CodeFormer", 13, ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title, fixed(25));
        copy.addView(text("Fidelidad 90% · puede reconstruir rasgos", 10, muted), fixed(23));
        row.addView(copy, new LinearLayout.LayoutParams(0, -1, 1f));
        faceRestoreSwitch = new Switch(this);
        // CodeFormer is opt-in because its 359 MiB model can exceed Android's
        // process memory limit when combined with a large enhanced bitmap.
        faceRestoreSwitch.setChecked(false);
        faceRestoreSwitch.setContentDescription("Activar restauración facial generativa");
        faceRestoreSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                toast("CodeFormer solo se ejecutará si hay memoria suficiente");
            }
        });
        row.addView(faceRestoreSwitch, fixedWidth(55));
        return row;
    }

    private SeekBar addSlider(LinearLayout parent, String title, String hint, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(5));
        row.setBackground(round(panel, 12));
        LinearLayout labels = new LinearLayout(this); labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(title, 13, ink); left.setTypeface(Typeface.DEFAULT, Typeface.BOLD); labels.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView right = text(String.valueOf(value), 12, accent); right.setGravity(Gravity.END); labels.addView(right, fixedWidth(35)); row.addView(labels, fixed(22));
        TextView hintView = text(hint, 10, muted);
        row.addView(hintView, fixed(18));
        SeekBar seek = new SeekBar(this); seek.setContentDescription(title); seek.setMax(100); seek.setProgress(value); row.addView(seek, fixed(32));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int n, boolean fromUser) { right.setText(String.valueOf(n)); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        });
        parent.addView(row, spacedWrap(7)); return seek;
    }

    private View section(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(2), dp(7), dp(2), dp(7));
        TextView a = text(title, 10, accent);
        a.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(a, fixed(20));
        box.addView(text(sub, 11, muted), new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private View actions() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        enhanceButton = button("Mejorar con IA", true);
        saveButton = button("Guardar PNG", false);
        saveButton.setEnabled(false);
        row.addView(enhanceButton, weight(1.25f, 0, 7));
        row.addView(saveButton, weight(1, 0, 0));
        enhanceButton.setOnClickListener(v -> enhance()); saveButton.setOnClickListener(v -> saveImage()); return row;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_IMAGE);
    }

    private void chooseVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("video/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_VIDEO);
    }

    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION); return;
        }
        try {
            File dir = new File(getCacheDir(), "images"); if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "captura_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, "com.mejorarfotos.app.fileprovider", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE); intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, TAKE_PHOTO);
        } catch (Exception e) { toast("No se pudo abrir la camara"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK) return;
        Uri uri = requestCode == TAKE_PHOTO ? cameraUri : data == null ? null : data.getData(); if (uri == null) return;
        if (requestCode == PICK_VIDEO) { setVideoMode(true); loadVideo(uri); } else { setVideoMode(false); loadBitmap(uri); }
    }

    private void loadBitmap(Uri uri) {
        cancelCurrentWork(false);
        final int operation = operationGeneration.incrementAndGet();
        setProcessingUi(true, "Analizando foto...");
        activeTask = executor.submit(() -> {
            Bitmap result = null;
            try {
                Bitmap decoded = decodeSampled(
                        uri, ProcessingMemory.photoDecodeMaxSide(MainActivity.this));
                if (decoded == null) throw new Exception("Foto inválida");
                result = orient(uri, decoded);
                final Bitmap delivered = result;
                result = null;
                runOnUiThread(() -> {
                    if (!isCurrent(operation)) {
                        recycle(delivered);
                        return;
                    }
                    setProcessingUi(false, null);
                    showImage(
                            delivered,
                            delivered.getWidth() + " x " + delivered.getHeight() + " px · original");
                });
            } catch (OutOfMemoryError memoryError) {
                Log.e(TAG, "Sin memoria al cargar la foto", memoryError);
                postFailure(operation, "La foto es demasiado grande para la memoria disponible");
            } catch (Exception error) {
                Log.e(TAG, "No se pudo leer la foto", error);
                postFailure(operation, "No se pudo leer esa foto");
            } finally {
                recycle(result);
            }
        });
    }

    private void loadVideo(Uri uri) {
        cancelCurrentWork(false);
        currentVideoUri = uri;
        final int operation = operationGeneration.incrementAndGet();
        setProcessingUi(true, "Analizando video...");
        activeTask = executor.submit(() -> {
            VideoFrameProcessor.Selection selection = null;
            Bitmap result = null;
            try {
                VideoFrameProcessor.Info info = VideoFrameProcessor.inspect(this, uri);
                selection = VideoFrameProcessor.bestFrameAcrossVideo(this, uri, info);
                try {
                    result = TemporalFrameFusion.fuse(this, uri, selection, info);
                } catch (Exception fusionError) {
                    result = selection.bitmap;
                }
                final Bitmap delivered = result;
                final long selectedTime = selection.timeUs;
                final int selectedFrame = selection.frameIndex;
                final int selectedProgress = (int) Math.max(
                        0, Math.min(1000, selectedTime * 1000L / info.durationUs));
                result = null;
                selection = null;
                runOnUiThread(() -> {
                    if (!isCurrent(operation)) {
                        recycle(delivered);
                        return;
                    }
                    currentVideoInfo = info;
                    videoDurationUs = info.durationUs;
                    setProcessingUi(false, null);
                    frameSeek.setProgress(selectedProgress);
                    videoInfo.setText(
                            "Fusión temporal · "
                                    + Math.round(selectedProgress / 10f)
                                    + "% · "
                                    + info.durationLabel);
                    showImage(
                            delivered,
                            delivered.getWidth()
                                    + " x "
                                    + delivered.getHeight()
                                    + " px · fotogramas alineados · base "
                                    + (selectedFrame >= 0 ? selectedFrame : ""));
                });
            } catch (OutOfMemoryError memoryError) {
                Log.e(TAG, "Sin memoria al analizar el video", memoryError);
                postFailure(operation, "Memoria insuficiente para fusionar ese video");
            } catch (Exception error) {
                Log.e(TAG, "No se pudo leer el video", error);
                postFailure(operation, "No se pudo leer ese video");
            } finally {
                if (selection != null) recycle(selection.bitmap);
                recycle(result);
            }
        });
    }

    private void loadVideoFrame(int progress) {
        if (loadingFrame || currentVideoUri == null || videoDurationUs <= 0 || currentVideoInfo == null) return;
        cancelCurrentWork(false);
        loadingFrame = true;
        final Uri videoUri = currentVideoUri;
        final VideoFrameProcessor.Info videoInfoSnapshot = currentVideoInfo;
        final long duration = videoDurationUs;
        final long target = duration * progress / 1000L;
        final int operation = operationGeneration.incrementAndGet();
        setProcessingUi(true, "Buscando el fotograma más nítido...");
        activeTask = executor.submit(() -> {
            VideoFrameProcessor.Selection selection = null;
            Bitmap frame = null;
            try {
                selection = VideoFrameProcessor.sharpestFrame(
                        this, videoUri, target, videoInfoSnapshot);
                try {
                    frame = TemporalFrameFusion.fuse(
                            this, videoUri, selection, videoInfoSnapshot);
                } catch (Exception fusionError) {
                    frame = selection.bitmap;
                }
                final Bitmap fused = frame;
                final int selectedFrame = selection.frameIndex;
                final int exactProgress = (int) Math.max(
                        0, Math.min(1000, selection.timeUs * 1000L / duration));
                frame = null;
                selection = null;
                runOnUiThread(() -> {
                    if (!isCurrent(operation)) {
                        recycle(fused);
                        return;
                    }
                    loadingFrame = false;
                    setProcessingUi(false, null);
                    frameSeek.setProgress(exactProgress);
                    videoInfo.setText(
                            "Fusión temporal · "
                                    + exactProgress / 10f
                                    + "% · base "
                                    + (selectedFrame >= 0 ? selectedFrame : "exacta"));
                    showImage(
                            fused,
                            fused.getWidth()
                                    + " x "
                                    + fused.getHeight()
                                    + " px · vecinos alineados y fusionados");
                });
            } catch (OutOfMemoryError memoryError) {
                Log.e(TAG, "Sin memoria al extraer el fotograma", memoryError);
                postFailure(operation, "Memoria insuficiente para fusionar el fotograma");
            } catch (Exception error) {
                Log.e(TAG, "No se pudo extraer el fotograma", error);
                postFailure(operation, "No se pudo extraer el fotograma");
            } finally {
                if (selection != null) recycle(selection.bitmap);
                recycle(frame);
            }
        });
    }

    private void showImage(Bitmap result, String dimensions) {
        recycleState();
        currentBitmap = result;
        editingOriginal = true;
        cropView.setBitmap(result);
        cropView.showFullImage();
        compareButton.setVisibility(View.GONE); saveButton.setEnabled(false); compareLabel.setText("VISTA ORIGINAL");
        cropButton.setText("Activar recorte");
        resolution.setText(dimensions); status.setText(videoModeEnabled ? "Puedes mover el selector o recortar el fotograma antes de mejorarlo" : "Ajusta el recorte, elige un preset y pulsa Mejorar con IA");
    }

    private void startCrop() {
        if (processing) {
            toast("Espera a que termine o cancela el procesamiento");
            return;
        }
        if (currentBitmap == null || currentBitmap.isRecycled()) {
            toast(videoModeEnabled ? "Primero elige un fotograma" : "Primero elige una foto");
            return;
        }
        editingOriginal = true;
        cropView.setBitmap(currentBitmap);
        cropView.beginCrop();
        compareButton.setVisibility(View.GONE);
        compareLabel.setText("RECORTE ORIGINAL");
        cropButton.setText("Recorte activo");
        status.setText(
                "Recorte activo · arrastra el interior para moverlo y las esquinas para ajustar");
    }

    private void useFullOriginal() {
        if (processing) {
            toast("Espera a que termine o cancela el procesamiento");
            return;
        }
        if (currentBitmap == null || currentBitmap.isRecycled()) {
            toast(videoModeEnabled ? "Primero elige un fotograma" : "Primero elige una foto");
            return;
        }
        editingOriginal = true;
        cropView.setBitmap(currentBitmap);
        cropView.showFullImage();
        compareButton.setVisibility(View.GONE);
        compareLabel.setText("IMAGEN COMPLETA");
        cropButton.setText("Activar recorte");
        status.setText("Se procesará la imagen original completa");
    }

    private Bitmap orient(Uri uri, Bitmap bitmap) {
        try {
            InputStream stream = getContentResolver().openInputStream(uri); ExifInterface exif = new ExifInterface(stream);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); if (stream != null) stream.close();
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return rotate(bitmap, 90); if (o == ExifInterface.ORIENTATION_ROTATE_180) return rotate(bitmap, 180); if (o == ExifInterface.ORIENTATION_ROTATE_270) return rotate(bitmap, 270);
        } catch (Exception ignored) {} return bitmap;
    }

    private Bitmap rotate(Bitmap bitmap, int degrees) { android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(degrees); Bitmap r = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true); if (r != bitmap) bitmap.recycle(); return r; }

    private void enhance() {
        if (processing) {
            cancelCurrentWork(false);
            status.setText("Procesado cancelado · la imagen original se mantiene");
            return;
        }
        if (currentBitmap == null) { toast(videoModeEnabled ? "Primero elige un video" : "Primero elige una foto"); return; }
        final Bitmap source;
        try {
            if (!editingOriginal && originalCrop != null && !originalCrop.isRecycled()) {
                source = originalCrop.copy(Bitmap.Config.ARGB_8888, false);
                if (source == null) throw new OutOfMemoryError("No se pudo copiar el original");
            } else {
                source = cropView.crop();
            }
        } catch (OutOfMemoryError memoryError) {
            Log.e(TAG, "Sin memoria para crear el recorte", memoryError);
            status.setText("Memoria insuficiente · reduce el recorte o usa escala 2x");
            return;
        }
        if (source == null) return;
        final int noise = noiseSeek.getProgress();
        final int detail = detailSeek.getProgress();
        final int sharp = sharpSeek.getProgress();
        final int requestedScale = scaleFactor;
        final int requestedProfile = profile;
        final boolean videoSource = videoModeEnabled;
        final boolean restoreFaces = faceRestoreSwitch.isChecked();
        final int operation = operationGeneration.incrementAndGet();
        if (enhancedBitmap != null && !enhancedBitmap.isRecycled()) {
            cropView.setBitmap(currentBitmap);
            recycle(enhancedBitmap);
            enhancedBitmap = null;
        }
        recycle(originalCrop);
        originalCrop = null;
        compareButton.setVisibility(View.GONE);
        compareLabel.setText("PROCESANDO ORIGINAL");
        setProcessingUi(
                true,
                videoSource
                        ? "Real-ESRGAN sobre la fusión temporal..."
                        : "Reconstruyendo detalle con Real-ESRGAN...");
        activeTask = executor.submit(() -> {
            Bitmap computed = null;
            try {
                String engine;
                try {
                    computed = NativeRealEsrgan.enhance(
                            MainActivity.this,
                            source,
                            requestedScale,
                            requestedProfile,
                            noise,
                            detail,
                            sharp);
                    engine = videoSource
                            ? "fusión temporal + Real-ESRGAN"
                            : "Real-ESRGAN nativo";
                } catch (InterruptedException cancelled) {
                    throw cancelled;
                } catch (Exception | OutOfMemoryError nativeError) {
                    Log.w(TAG, "Real-ESRGAN no disponible; se usa el motor seguro", nativeError);
                    System.gc();
                    computed = ImageEnhancer.enhance(
                            MainActivity.this,
                            source,
                            requestedScale,
                            requestedProfile,
                            noise,
                            detail,
                            sharp);
                    engine = videoSource
                            ? "fusión temporal + motor local"
                            : "motor local de respaldo";
                }
                if (restoreFaces) {
                    if (!ProcessingMemory.canRunCodeFormer(MainActivity.this, computed)) {
                        engine += " · CodeFormer omitido por memoria";
                    } else try {
                        CodeFormerFaceRestorer.Result restored =
                                CodeFormerFaceRestorer.restore(
                                        MainActivity.this,
                                        computed,
                                        percent -> runOnUiThread(() -> status.setText(
                                                percent >= 0
                                                        ? "Instalando CodeFormer · " + percent + "% · solo se descarga el modelo"
                                                        : "Instalando CodeFormer · descargando modelo")));
                        if (restored.bitmap != computed) {
                            computed.recycle();
                            computed = restored.bitmap;
                        }
                        engine += restored.restoredFaces > 0
                                ? " + CodeFormer 90% (" + restored.restoredFaces + " rostro)"
                                : " · sin rostro detectable";
                    } catch (Exception | OutOfMemoryError faceError) {
                        Log.w(TAG, "CodeFormer omitido para mantener la estabilidad", faceError);
                        engine += " · CodeFormer no disponible";
                    }
                }
                final Bitmap result = computed;
                final String engineName = engine;
                computed = null;
                runOnUiThread(() -> {
                    if (!isCurrent(operation)) {
                        recycle(source);
                        recycle(result);
                        return;
                    }
                    recycle(originalCrop);
                    recycle(enhancedBitmap);
                    originalCrop = source;
                    enhancedBitmap = result;
                    editingOriginal = false;
                    cropView.setComparison(originalCrop, result);
                    compareButton.setVisibility(View.VISIBLE);
                    compareLabel.setText("COMPARACIÓN · arrastra");
                    cropButton.setText("Modificar recorte");
                    setProcessingUi(false, null);
                    enhanceButton.setText("Actualizar resultado");
                    saveButton.setEnabled(true);
                    resolution.setText(
                            result.getWidth()
                                    + " x "
                                    + result.getHeight()
                                    + " px · resultado mejorado");
                    status.setText(
                            "Listo · "
                                    + engineName
                                    + " · puedes cambiar el recorte o abrir otro archivo");
                });
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                recycle(source);
                if (isCurrent(operation)) {
                    postFailure(operation, "Procesado cancelado · la imagen original se mantiene");
                }
            } catch (OutOfMemoryError memoryError) {
                Log.e(TAG, "Sin memoria durante la mejora", memoryError);
                recycle(source);
                System.gc();
                postFailure(
                        operation,
                        "Memoria insuficiente · prueba escala 2x o desactiva Rostro IA");
            } catch (Exception error) {
                Log.e(TAG, "No se pudo procesar el archivo", error);
                recycle(source);
                postFailure(operation, "No se pudo procesar el archivo");
            } finally {
                recycle(computed);
            }
        });
    }

    private void saveImage() {
        if (enhancedBitmap == null || enhancedBitmap.isRecycled()) return;
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION);
            return;
        }

        String name = "ugscaler_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";
        Uri saved = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/UGscaler");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            } else {
                File directory = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES),
                        "UGscaler");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new Exception("No se pudo crear la carpeta UGscaler");
                }
                values.put(
                        MediaStore.Images.Media.DATA,
                        new File(directory, name).getAbsolutePath());
            }

            saved = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (saved == null) throw new Exception("No se pudo crear el archivo");
            try (OutputStream output = getContentResolver().openOutputStream(saved, "w")) {
                if (output == null
                        || !enhancedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new Exception("No se pudo escribir la imagen");
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(saved, ready, null, null);
            }
            resolution.setText(
                    enhancedBitmap.getWidth()
                            + " x "
                            + enhancedBitmap.getHeight()
                            + " px · PNG guardado sin pérdida");
            status.setText("PNG guardado en Imágenes / UGscaler");
            showSavedImageActions(saved, name);
        } catch (OutOfMemoryError memoryError) {
            if (saved != null) getContentResolver().delete(saved, null, null);
            Log.e(TAG, "Sin memoria al exportar", memoryError);
            toast("No hay memoria suficiente para exportar esta resolución");
        } catch (Exception error) {
            if (saved != null) getContentResolver().delete(saved, null, null);
            Log.e(TAG, "No se pudo guardar el PNG", error);
            toast("No se pudo guardar el PNG");
        }
    }

    private void showSavedImageActions(Uri uri, String name) {
        new AlertDialog.Builder(this)
                .setTitle("PNG guardado")
                .setMessage(name + "\nImágenes / UGscaler")
                .setPositiveButton("Ver imagen", (dialog, which) -> openSavedImage(uri))
                .setNeutralButton("Compartir", (dialog, which) -> shareSavedImage(uri))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void openSavedImage(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "image/png");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newUri(getContentResolver(), "UGscaler PNG", uri));
        try {
            startActivity(view);
        } catch (Exception error) {
            toast("No hay una aplicación disponible para abrir imágenes");
        }
    }

    private void shareSavedImage(Uri uri) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newUri(getContentResolver(), "UGscaler PNG", uri));
        try {
            startActivity(Intent.createChooser(share, "Compartir PNG mejorado"));
        } catch (Exception error) {
            toast("No hay una aplicación disponible para compartir");
        }
    }

    private void setVideoMode(boolean enabled) { videoModeEnabled = enabled; refreshModeUi(); }
    private void refreshModeUi() { if (photoMode == null) return; style(photoMode, !videoModeEnabled); style(videoMode, videoModeEnabled); videoControlsView.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); frameSeek.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); videoInfo.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); saveButton.setText("Guardar PNG"); }

    private void clearEditor() {
        cancelCurrentWork(false);
        currentVideoUri = null;
        currentVideoInfo = null;
        videoDurationUs = 0;
        loadingFrame = false;
        editingOriginal = true;
        if (cropView != null) {
            cropView.setBitmap(null);
            cropView.showFullImage();
        }
        recycleState();
        compareButton.setVisibility(View.GONE);
        saveButton.setEnabled(false);
        cropButton.setText("Activar recorte");
        enhanceButton.setText("Mejorar con IA");
        enhanceButton.setEnabled(true);
        status.setText("Listo para mejorar · elige una foto o un video");
        resolution.setText("Salida estimada · todavía no procesada");
        videoInfo.setText("Video · buscando el mejor fotograma");
    }

    private void recycleState() {
        recycle(currentBitmap);
        recycle(enhancedBitmap);
        recycle(originalCrop);
        currentBitmap = null;
        enhancedBitmap = null;
        originalCrop = null;
    }

    private void cancelCurrentWork(boolean showMessage) {
        operationGeneration.incrementAndGet();
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) task.cancel(true);
        NativeRealEsrgan.cancelActive();
        processing = false;
        loadingFrame = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (enhanceButton != null) {
            enhanceButton.setEnabled(true);
            enhanceButton.setText(
                    enhancedBitmap == null ? "Mejorar con IA" : "Actualizar resultado");
        }
        if (showMessage && status != null) {
            status.setText("Procesado cancelado · la imagen original se mantiene");
        }
    }

    private void setProcessingUi(boolean busy, String message) {
        processing = busy;
        if (busy) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            enhanceButton.setText("Cancelar");
            enhanceButton.setEnabled(true);
            saveButton.setEnabled(false);
        } else {
            activeTask = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            enhanceButton.setText(
                    enhancedBitmap == null ? "Mejorar con IA" : "Actualizar resultado");
            enhanceButton.setEnabled(true);
        }
        if (message != null) status.setText(message);
    }

    private void postFailure(int operation, String message) {
        runOnUiThread(() -> {
            if (!isCurrent(operation)) return;
            loadingFrame = false;
            compareLabel.setText("VISTA ORIGINAL");
            cropButton.setText("Activar recorte");
            setProcessingUi(false, message);
        });
    }

    private boolean isCurrent(int operation) {
        return !destroyed && operation == operationGeneration.get();
    }

    private Bitmap decodeSampled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("No se pudo abrir la foto");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new Exception("Dimensiones de foto inválidas");
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while ((longest + sample - 1L) / sample > maxSide
                && sample <= Integer.MAX_VALUE / 2) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("No se pudo abrir la foto");
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, decode);
            if (bitmap == null) throw new Exception("No se pudo decodificar la foto");
            return bitmap;
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    @Override public void onBackPressed() {
        if (processing || currentBitmap != null || currentVideoUri != null) clearEditor();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelCurrentWork(false);
        executor.shutdownNow();
        recycleState();
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(
            int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED) return;
        if (request == CAMERA_PERMISSION) takePhoto();
        if (request == STORAGE_PERMISSION) saveImage();
    }

    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); v.setLineSpacing(0, 1.06f); return v; }
    private Button button(String value, boolean selected) { Button b = new Button(this); b.setText(value); b.setContentDescription(value); b.setTextSize(13); b.setAllCaps(false); b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44)); b.setGravity(Gravity.CENTER); b.setPadding(dp(7), 0, dp(7), 0); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setElevation(dp(1)); style(b, selected); return b; }
    private void style(Button b, boolean selected) { b.setTextColor(selected ? Color.rgb(20, 25, 18) : ink); b.setBackground(round(selected ? accent : panel, 13)); b.setAlpha(b.isEnabled() ? 1f : .45f); }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), Color.rgb(55, 68, 59)); return d; }
    private LinearLayout.LayoutParams fixed(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private LinearLayout.LayoutParams fixedWidth(int w) { return new LinearLayout.LayoutParams(dp(w), -2); }
    private LinearLayout.LayoutParams spacedFixed(int h, int bottom) {
        LinearLayout.LayoutParams params = fixed(h);
        params.bottomMargin = dp(bottom);
        return params;
    }
    private LinearLayout.LayoutParams spacedWrap(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        return params;
    }
    private LinearLayout.LayoutParams weight(float w, int left, int right) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, w); p.leftMargin = dp(left); p.rightMargin = dp(right); return p; }
    private int viewportHeightDp() {
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density) - 24;
        return Math.max(280, Math.min(430, Math.round(widthDp * .92f)));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_SHORT).show()); }
}
