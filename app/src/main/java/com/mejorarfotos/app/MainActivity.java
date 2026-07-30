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
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 10, TAKE_PHOTO = 11, CAMERA_PERMISSION = 12;
    private final int background = Color.rgb(13, 16, 15), panel = Color.rgb(25, 31, 28);
    private final int panel2 = Color.rgb(32, 40, 35), ink = Color.rgb(239, 244, 239);
    private final int muted = Color.rgb(157, 173, 161), accent = Color.rgb(214, 243, 106);
    private CropImageView cropView;
    private TextView status, resolution, compareLabel;
    private Button enhanceButton, saveButton, compareButton;
    private SeekBar noiseSeek, detailSeek, sharpSeek;
    private Bitmap currentBitmap, enhancedBitmap, originalCrop;
    private Uri cameraUri;
    private int scaleFactor = 2, profile = 0;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(9), dp(16), dp(10));
        root.addView(appBar(), fixed(46));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout editor = new LinearLayout(this); editor.setOrientation(LinearLayout.VERTICAL);
        editor.addView(workspace(), fixed(340));
        editor.addView(sourceBar(), fixed(48));
        status = text("Listo para mejorar · ajusta el recorte en el visor", 12, muted);
        status.setPadding(dp(12), 0, dp(12), 0); status.setBackground(round(panel2, 12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        editor.addView(status, fixed(33));
        editor.addView(section("MEJORA INTELIGENTE", "Ajustes no destructivos · la foto original no se modifica"), fixed(45));
        editor.addView(presets(), fixed(58));
        editor.addView(scaleControls(), fixed(48));
        noiseSeek = addSlider(editor, "Reducir ruido", "Limpia grano y compresión", 18);
        detailSeek = addSlider(editor, "Recuperar detalle", "Textura natural sin halos", 64);
        sharpSeek = addSlider(editor, "Enfoque", "Define bordes y microcontraste", 58);
        editor.addView(section("REVISIÓN", "Desliza la línea en el visor para comparar"), fixed(45));
        resolution = text("Salida estimada · todavía no procesada", 12, muted);
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
    }

    private View appBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("UGscaler", 19, ink); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        TextView tag = text("IA NATIVA  ·  OFFLINE", 10, accent); tag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER); tag.setPadding(dp(10), 0, dp(10), 0);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(Color.rgb(38, 48, 35)); pill.setCornerRadius(dp(20)); tag.setBackground(pill);
        bar.addView(tag, fixedWidth(142)); return bar;
    }

    private View workspace() {
        FrameLayout frame = new FrameLayout(this); frame.setBackground(round(Color.rgb(19, 24, 21), 16)); frame.setElevation(dp(2));
        if (Build.VERSION.SDK_INT >= 21) frame.setClipToOutline(true);
        cropView = new CropImageView(this); cropView.setContentDescription("Visor de imagen y recorte"); frame.addView(cropView, new FrameLayout.LayoutParams(-1, -1));
        compareLabel = text("VISTA ORIGINAL", 10, ink); TextView badge = compareLabel; badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD); badge.setBackground(round(Color.argb(180, 18, 23, 20), 9));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(108), dp(27), Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(10), dp(10), 0, 0); frame.addView(badge, badgeParams);
        compareButton = button("Comparar antes / después", false); compareButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams compareParams = new FrameLayout.LayoutParams(dp(190), dp(39), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        compareParams.bottomMargin = dp(12); frame.addView(compareButton, compareParams);
        compareButton.setOnClickListener(v -> {
            cropView.toggleComparison();
            compareLabel.setText(cropView.isComparing() ? "COMPARACIÓN · arrastra la línea" : "VISTA RESULTADO");
        });
        return frame;
    }

    private View sourceBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        Button open = button("＋  Abrir imagen", false), camera = button("◉  Cámara", false);
        bar.addView(open, weight(1, 0, 6)); bar.addView(camera, weight(1, 0, 0));
        open.setOnClickListener(v -> chooseFromGallery()); camera.setOnClickListener(v -> takePhoto());
        return bar;
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

    private void selectPreset(LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) style((Button) row.getChildAt(i), i == selected);
    }

    private void applyProfile(int value) {
        if (value == 1) { noiseSeek.setProgress(38); detailSeek.setProgress(49); sharpSeek.setProgress(42); }
        else if (value == 2) { noiseSeek.setProgress(14); detailSeek.setProgress(76); sharpSeek.setProgress(68); }
        else if (value == 3) { noiseSeek.setProgress(28); detailSeek.setProgress(86); sharpSeek.setProgress(78); }
        else { noiseSeek.setProgress(18); detailSeek.setProgress(64); sharpSeek.setProgress(58); }
        status.setText("Preset aplicado · pulsa Mejorar con IA para generar una nueva versión");
    }

    private View scaleControls() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ESCALA", 10, muted); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title, fixedWidth(58));
        Button x2 = button("2×", true), x4 = button("4×", false);
        row.addView(x2, fixedWidth(70)); LinearLayout.LayoutParams x4p = fixedWidth(70); x4p.leftMargin = dp(7); row.addView(x4, x4p);
        TextView note = text("Salida hasta 4096 px", 11, muted); note.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(note, new LinearLayout.LayoutParams(0, -1, 1));
        Button reset = button("Restablecer", false); LinearLayout.LayoutParams resetParams = fixedWidth(102); resetParams.leftMargin = dp(8); row.addView(reset, resetParams);
        x2.setOnClickListener(v -> { scaleFactor = 2; style(x2, true); style(x4, false); });
        x4.setOnClickListener(v -> { scaleFactor = 4; style(x4, true); style(x2, false); });
        reset.setOnClickListener(v -> { profile = 0; scaleFactor = 2; style(x2, true); style(x4, false); applyProfile(0); status.setText("Ajustes restablecidos · elige un preset o personaliza la mejora"); }); return row;
    }

    private SeekBar addSlider(LinearLayout parent, String title, String hint, int value) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout labels = new LinearLayout(this); labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = text(title, 13, ink); left.setTypeface(Typeface.DEFAULT, Typeface.BOLD); labels.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView right = text(String.valueOf(value), 12, accent); right.setGravity(Gravity.RIGHT); labels.addView(right, fixedWidth(35)); row.addView(labels, fixed(22));
        SeekBar seek = new SeekBar(this); seek.setContentDescription(title); seek.setMax(100); seek.setProgress(value); row.addView(seek, fixed(25));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int n, boolean fromUser) { right.setText(String.valueOf(n)); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        parent.addView(row, fixed(58)); return seek;
    }

    private View section(String title, String sub) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = text(title, 10, accent); a.setTypeface(Typeface.DEFAULT, Typeface.BOLD); box.addView(a, fixed(18));
        box.addView(text(sub, 11, muted), fixed(19)); return box;
    }

    private View actions() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        enhanceButton = button("Mejorar con IA", true); saveButton = button("Exportar JPG", false); saveButton.setEnabled(false);
        row.addView(enhanceButton, weight(1, 0, 7)); row.addView(saveButton, weight(1, 0, 0));
        enhanceButton.setOnClickListener(v -> enhance()); saveButton.setOnClickListener(v -> saveImage()); return row;
    }

    private void chooseFromGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_IMAGE);
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
        } catch (Exception e) { toast("No se pudo abrir la cámara"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK) return;
        Uri uri = requestCode == TAKE_PHOTO ? cameraUri : data.getData(); if (uri != null) loadBitmap(uri);
    }

    private void loadBitmap(Uri uri) {
        status.setText("Analizando imagen…"); executor.execute(() -> {
            try {
                InputStream stream = getContentResolver().openInputStream(uri); Bitmap decoded = BitmapFactory.decodeStream(stream); if (stream != null) stream.close();
                if (decoded == null) throw new Exception("invalid"); decoded = orient(uri, decoded); Bitmap result = decoded;
                runOnUiThread(() -> {
                    if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
                    if (enhancedBitmap != null && !enhancedBitmap.isRecycled()) enhancedBitmap.recycle();
                    if (originalCrop != null && !originalCrop.isRecycled()) originalCrop.recycle();
                    currentBitmap = result; enhancedBitmap = null; originalCrop = null; cropView.setBitmap(result); cropView.showFullImage();
                    compareButton.setVisibility(View.GONE); saveButton.setEnabled(false); compareLabel.setText("VISTA ORIGINAL");
                    status.setText("Ajusta el recorte · después elige un preset o controla cada ajuste"); resolution.setText(result.getWidth() + " × " + result.getHeight() + " px · original");
                });
            } catch (Exception e) { runOnUiThread(() -> status.setText("No se pudo leer esa imagen")); }
        });
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
        if (currentBitmap == null) { toast("Primero elige una foto"); return; }
        if (originalCrop == null) originalCrop = cropView.crop();
        final Bitmap source = originalCrop; final int noise = noiseSeek.getProgress(), detail = detailSeek.getProgress(), sharp = sharpSeek.getProgress();
        enhanceButton.setEnabled(false); saveButton.setEnabled(false); enhanceButton.setText("Procesando…"); status.setText("Ejecutando Real-ESRGAN nativo…");
        executor.execute(() -> {
            try {
                Bitmap computed; String engine;
                try {
                    computed = NativeRealEsrgan.enhance(MainActivity.this, source, scaleFactor);
                    engine = "Real-ESRGAN nativo · NCNN";
                } catch (Exception nativeError) {
                    computed = ImageEnhancer.enhance(source, scaleFactor, profile, noise, detail, sharp);
                    engine = "motor local de respaldo";
                }
                final Bitmap result = computed; final String engineName = engine;
                runOnUiThread(() -> {
                    enhancedBitmap = result; cropView.setComparison(originalCrop, result); compareButton.setVisibility(View.VISIBLE); compareLabel.setText("COMPARACIÓN · arrastra la línea");
                    enhanceButton.setText("Actualizar resultado"); enhanceButton.setEnabled(true); saveButton.setEnabled(true);
                    resolution.setText(result.getWidth() + " × " + result.getHeight() + " px · resultado mejorado"); status.setText("Listo · " + engineName + " · compara antes/después y guarda el resultado");
                });
            } catch (Exception e) { runOnUiThread(() -> { enhanceButton.setText("Mejorar con IA"); enhanceButton.setEnabled(true); status.setText("No se pudo procesar la imagen"); }); }
        });
    }

    private void saveImage() {
        if (enhancedBitmap == null) return; String name = "ugscaler_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        try {
            OutputStream output;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UGscaler");
                Uri saved = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); output = getContentResolver().openOutputStream(saved);
            } else { File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name); output = new FileOutputStream(file); }
            enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 96, output); output.close(); toast("Guardada en Imágenes / UGscaler");
        } catch (Exception e) { toast("No se pudo guardar la imagen"); }
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
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
