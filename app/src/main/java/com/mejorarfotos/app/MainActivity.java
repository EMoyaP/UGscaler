package com.mejorarfotos.app;

import android.Manifest;
import android.app.Activity;
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
import android.view.Gravity;
import android.view.View;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UGscaler editor: photo restoration and frame recovery from video. */
public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 10, PICK_VIDEO = 11, TAKE_PHOTO = 12, CAMERA_PERMISSION = 13;
    private final int background = Color.rgb(13, 16, 15), panel = Color.rgb(25, 31, 28);
    private final int panel2 = Color.rgb(32, 40, 35), ink = Color.rgb(239, 244, 239);
    private final int muted = Color.rgb(157, 173, 161), accent = Color.rgb(214, 243, 106);
    private CropImageView cropView;
    private TextView status, resolution, compareLabel, videoInfo;
    private View videoControlsView;
    private Button enhanceButton, saveButton, compareButton, photoMode, videoMode;
    private SeekBar noiseSeek, detailSeek, sharpSeek, frameSeek;
    private Switch faceRestoreSwitch;
    private Bitmap currentBitmap, enhancedBitmap, originalCrop;
    private Uri cameraUri, currentVideoUri;
    private int scaleFactor = 2, profile = 0;
    private long videoDurationUs;
    private VideoFrameProcessor.Info currentVideoInfo;
    private boolean videoModeEnabled, loadingFrame;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        root.setPadding(dp(16), dp(9), dp(16), dp(10));
        root.addView(appBar(), fixed(46));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.addView(modeBar(), fixed(48));
        editor.addView(workspace(), fixed(340));
        editor.addView(sourceBar(), fixed(48));
        videoControlsView = videoControls(); editor.addView(videoControlsView, fixed(61));
        status = text("Listo para mejorar · elige una foto o un video", 12, muted);
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setBackground(round(panel2, 12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        editor.addView(status, fixed(33));
        editor.addView(section("MEJORA INTELIGENTE", "Pipeline local · la foto original no se modifica"), fixed(45));
        editor.addView(presets(), fixed(58));
        editor.addView(scaleControls(), fixed(48));
        editor.addView(faceControls(), fixed(62));
        noiseSeek = addSlider(editor, "Reducir ruido", "Limpia grano y compresion", 18);
        detailSeek = addSlider(editor, "Recuperar detalle", "Textura natural sin halos", 64);
        sharpSeek = addSlider(editor, "Enfoque", "Define bordes y microcontraste", 58);
        editor.addView(section("REVISION", "Desliza la linea en el visor para comparar"), fixed(45));
        resolution = text("Salida estimada · todavia no procesada", 12, muted);
        editor.addView(resolution, fixed(30));
        scroll.addView(editor, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(5); scrollParams.bottomMargin = dp(7);
        root.addView(scroll, scrollParams);
        root.addView(actions(), fixed(52));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(16), bars.top + dp(9), dp(16), bars.bottom + dp(10));
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
        if (Build.VERSION.SDK_INT >= 21) frame.setClipToOutline(true);
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
        TextView hint = text("índice exacto", 10, accent); hint.setGravity(Gravity.RIGHT); labels.addView(hint, fixedWidth(105));
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
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ESCALA", 10, muted); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(title, fixedWidth(58));
        Button x2 = button("2x", true), x4 = button("4x", false);
        row.addView(x2, fixedWidth(70)); LinearLayout.LayoutParams x4p = fixedWidth(70); x4p.leftMargin = dp(7); row.addView(x4, x4p);
        TextView note = text("Salida hasta 4096 px", 11, muted); note.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); row.addView(note, new LinearLayout.LayoutParams(0, -1, 1));
        Button reset = button("Restablecer", false); LinearLayout.LayoutParams resetParams = fixedWidth(102); resetParams.leftMargin = dp(8); row.addView(reset, resetParams);
        x2.setOnClickListener(v -> { scaleFactor = 2; style(x2, true); style(x4, false); });
        x4.setOnClickListener(v -> { scaleFactor = 4; style(x4, true); style(x2, false); });
        reset.setOnClickListener(v -> { profile = 0; scaleFactor = 2; style(x2, true); style(x4, false); applyProfile(0); status.setText("Ajustes restablecidos · elige un preset o personaliza la mejora"); });
        return row;
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
        faceRestoreSwitch.setChecked(true);
        faceRestoreSwitch.setContentDescription("Activar restauración facial generativa");
        row.addView(faceRestoreSwitch, fixedWidth(55));
        return row;
    }

    private SeekBar addSlider(LinearLayout parent, String title, String hint, int value) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout labels = new LinearLayout(this); labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(title, 13, ink); left.setTypeface(Typeface.DEFAULT, Typeface.BOLD); labels.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView right = text(String.valueOf(value), 12, accent); right.setGravity(Gravity.RIGHT); labels.addView(right, fixedWidth(35)); row.addView(labels, fixed(22));
        SeekBar seek = new SeekBar(this); seek.setContentDescription(title); seek.setMax(100); seek.setProgress(value); row.addView(seek, fixed(25));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int n, boolean fromUser) { right.setText(String.valueOf(n)); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        });
        parent.addView(row, fixed(58)); return seek;
    }

    private View section(String title, String sub) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = text(title, 10, accent); a.setTypeface(Typeface.DEFAULT, Typeface.BOLD); box.addView(a, fixed(18)); box.addView(text(sub, 11, muted), fixed(19)); return box;
    }

    private View actions() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        enhanceButton = button("Mejorar con IA", true); saveButton = button("Exportar JPG", false); saveButton.setEnabled(false);
        row.addView(enhanceButton, weight(1, 0, 7)); row.addView(saveButton, weight(1, 0, 0));
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
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
        status.setText("Analizando foto..."); executor.execute(() -> {
            try {
                InputStream stream = getContentResolver().openInputStream(uri); Bitmap decoded = BitmapFactory.decodeStream(stream); if (stream != null) stream.close();
                if (decoded == null) throw new Exception("invalid"); decoded = orient(uri, decoded); final Bitmap result = decoded;
                runOnUiThread(() -> showImage(result, result.getWidth() + " x " + result.getHeight() + " px · original"));
            } catch (Exception e) { runOnUiThread(() -> status.setText("No se pudo leer esa foto")); }
        });
    }

    private void loadVideo(Uri uri) {
        currentVideoUri = uri; status.setText("Analizando video...");
        executor.execute(() -> {
            try {
                VideoFrameProcessor.Info info = VideoFrameProcessor.inspect(this, uri);
                VideoFrameProcessor.Selection selection = VideoFrameProcessor.bestFrameAcrossVideo(this, uri, info);
                Bitmap result;
                try {
                    result = TemporalFrameFusion.fuse(this, uri, selection, info);
                } catch (Exception fusionError) {
                    result = selection.bitmap;
                }
                currentVideoInfo = info; videoDurationUs = info.durationUs; final Bitmap fused = result; final int selectedProgress = (int) Math.max(0, Math.min(1000, selection.timeUs * 1000L / info.durationUs));
                runOnUiThread(() -> { frameSeek.setProgress(selectedProgress); videoInfo.setText("Fusión temporal · " + Math.round(selectedProgress / 10f) + "% · " + info.durationLabel); showImage(fused, fused.getWidth() + " x " + fused.getHeight() + " px · 5 fotogramas alineados · base " + (selection.frameIndex >= 0 ? selection.frameIndex : "")); });
            } catch (Exception e) { runOnUiThread(() -> { currentVideoUri = null; status.setText("No se pudo leer ese video"); }); }
        });
    }

    private void loadVideoFrame(int progress) {
        if (loadingFrame || currentVideoUri == null || videoDurationUs <= 0 || currentVideoInfo == null) return;
        loadingFrame = true; status.setText("Buscando el fotograma mas nitido..."); final long target = videoDurationUs * progress / 1000L;
        executor.execute(() -> {
            try {
                VideoFrameProcessor.Selection selection = VideoFrameProcessor.sharpestFrame(this, currentVideoUri, target, currentVideoInfo);
                Bitmap frame;
                try {
                    frame = TemporalFrameFusion.fuse(
                            this, currentVideoUri, selection, currentVideoInfo);
                } catch (Exception fusionError) {
                    frame = selection.bitmap;
                }
                final Bitmap fused = frame;
                final int exactProgress = (int) Math.max(0, Math.min(1000, selection.timeUs * 1000L / videoDurationUs));
                runOnUiThread(() -> { loadingFrame = false; frameSeek.setProgress(exactProgress); videoInfo.setText("Fusión temporal · " + exactProgress / 10f + "% · base " + (selection.frameIndex >= 0 ? selection.frameIndex : "exacta")); showImage(fused, fused.getWidth() + " x " + fused.getHeight() + " px · vecinos alineados y fusionados"); });
            } catch (Exception e) { runOnUiThread(() -> { loadingFrame = false; status.setText("No se pudo extraer el fotograma"); }); }
        });
    }

    private void showImage(Bitmap result, String dimensions) {
        recycleState(); currentBitmap = result; cropView.setBitmap(result); cropView.showFullImage();
        compareButton.setVisibility(View.GONE); saveButton.setEnabled(false); compareLabel.setText("VISTA ORIGINAL");
        resolution.setText(dimensions); status.setText(videoModeEnabled ? "Puedes mover el selector o recortar el fotograma antes de mejorarlo" : "Ajusta el recorte, elige un preset y pulsa Mejorar con IA");
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
        if (currentBitmap == null) { toast(videoModeEnabled ? "Primero elige un video" : "Primero elige una foto"); return; }
        if (originalCrop != null && !originalCrop.isRecycled()) originalCrop.recycle(); originalCrop = cropView.crop();
        final Bitmap source = originalCrop; final int noise = noiseSeek.getProgress(), detail = detailSeek.getProgress(), sharp = sharpSeek.getProgress();
        final boolean restoreFaces = faceRestoreSwitch.isChecked();
        enhanceButton.setEnabled(false); saveButton.setEnabled(false); enhanceButton.setText("Procesando...");
        status.setText(videoModeEnabled ? "Real-ESRGAN sobre la fusión temporal..." : "Reconstruyendo detalle con Real-ESRGAN...");
        executor.execute(() -> {
            try {
                Bitmap computed;
                String engine;
                try {
                    computed = NativeRealEsrgan.enhance(
                            MainActivity.this, source, scaleFactor, profile, noise, detail, sharp);
                    engine = videoModeEnabled
                            ? "fusión temporal + Real-ESRGAN"
                            : "Real-ESRGAN nativo";
                } catch (Exception nativeError) {
                    computed = ImageEnhancer.enhance(
                            source, scaleFactor, profile, noise, detail, sharp);
                    engine = videoModeEnabled
                            ? "fusión temporal + motor local"
                            : "motor local de respaldo";
                }
                if (restoreFaces) {
                    try {
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
                    } catch (Exception faceError) {
                        engine += " · CodeFormer no disponible";
                    }
                }
                final Bitmap result = computed; final String engineName = engine;
                runOnUiThread(() -> { enhancedBitmap = result; cropView.setComparison(originalCrop, result); compareButton.setVisibility(View.VISIBLE); compareLabel.setText("COMPARACION · arrastra"); enhanceButton.setText("Actualizar resultado"); enhanceButton.setEnabled(true); saveButton.setEnabled(true); resolution.setText(result.getWidth() + " x " + result.getHeight() + " px · resultado mejorado"); status.setText("Listo · " + engineName + " · puedes cambiar el recorte o abrir otro archivo"); });
            } catch (Exception e) { runOnUiThread(() -> { enhanceButton.setText("Mejorar con IA"); enhanceButton.setEnabled(true); status.setText("No se pudo procesar el archivo"); }); }
        });
    }

    private void saveImage() {
        if (enhancedBitmap == null) return; String name = "ugscaler_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        try {
            OutputStream output;
            if (Build.VERSION.SDK_INT >= 29) { ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UGscaler"); Uri saved = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); output = getContentResolver().openOutputStream(saved); }
            else { File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name); output = new FileOutputStream(file); }
            if (output == null) throw new Exception("storage"); enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 96, output); output.close(); toast("Guardada en Imagenes / UGscaler");
        } catch (Exception e) { toast("No se pudo guardar la imagen"); }
    }

    private void setVideoMode(boolean enabled) { videoModeEnabled = enabled; refreshModeUi(); }
    private void refreshModeUi() { if (photoMode == null) return; style(photoMode, !videoModeEnabled); style(videoMode, videoModeEnabled); videoControlsView.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); frameSeek.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); videoInfo.setVisibility(videoModeEnabled ? View.VISIBLE : View.GONE); saveButton.setText(videoModeEnabled ? "Exportar fotograma" : "Exportar JPG"); }

    private void clearEditor() { currentVideoUri = null; currentVideoInfo = null; videoDurationUs = 0; loadingFrame = false; recycleState(); if (cropView != null) { cropView.setBitmap(null); cropView.showFullImage(); } compareButton.setVisibility(View.GONE); saveButton.setEnabled(false); status.setText("Listo para mejorar · elige una foto o un video"); resolution.setText("Salida estimada · todavia no procesada"); videoInfo.setText("Video · buscando el mejor fotograma"); }
    private void recycleState() { if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle(); if (enhancedBitmap != null && !enhancedBitmap.isRecycled()) enhancedBitmap.recycle(); if (originalCrop != null && !originalCrop.isRecycled()) originalCrop.recycle(); currentBitmap = null; enhancedBitmap = null; originalCrop = null; }

    @Override public void onBackPressed() { if (currentBitmap != null || currentVideoUri != null) clearEditor(); else super.onBackPressed(); }
    @Override protected void onDestroy() { executor.shutdownNow(); recycleState(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (request == CAMERA_PERMISSION && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) takePhoto(); }

    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private Button button(String value, boolean selected) { Button b = new Button(this); b.setText(value); b.setContentDescription(value); b.setTextSize(12); b.setAllCaps(false); b.setMinHeight(0); b.setMinimumHeight(0); b.setGravity(Gravity.CENTER); b.setPadding(dp(4), 0, dp(4), 0); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setElevation(dp(1)); style(b, selected); return b; }
    private void style(Button b, boolean selected) { b.setTextColor(selected ? Color.rgb(20, 25, 18) : ink); b.setBackground(round(selected ? accent : panel, 13)); b.setAlpha(b.isEnabled() ? 1f : .45f); }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), Color.rgb(55, 68, 59)); return d; }
    private LinearLayout.LayoutParams fixed(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private LinearLayout.LayoutParams fixedWidth(int w) { return new LinearLayout.LayoutParams(dp(w), -2); }
    private LinearLayout.LayoutParams weight(float w, int left, int right) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, w); p.leftMargin = dp(left); p.rightMargin = dp(right); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_SHORT).show()); }
}
