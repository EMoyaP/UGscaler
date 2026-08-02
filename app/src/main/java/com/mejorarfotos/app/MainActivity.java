package com.mejorarfotos.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

/** Mobile-first UGscaler photo workflow: upload, crop, automatic local AI and export. */
@SuppressLint("SetTextI18n")
public class MainActivity extends Activity implements GenerativeView.Host {
    private static final String TAG = "UGscaler";
    private static final int PICK_IMAGE = 10;
    private static final int STORAGE_PERMISSION = 11;

    private final int background = Color.rgb(10, 14, 13);
    private final int panel = Color.rgb(24, 31, 28);
    private final int panel2 = Color.rgb(31, 40, 35);
    private final int ink = Color.rgb(241, 246, 241);
    private final int muted = Color.rgb(161, 176, 165);
    private final int accent = Color.rgb(214, 243, 106);
    private final int cyan = Color.rgb(42, 207, 231);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CropImageView cropView;
    private LockableScrollView scroll;
    private Button uploadButton;
    private Button cropButton;
    private Button enhanceButton;
    private Button newProjectButton;
    private Button editorTab;
    private Button generativeTab;
    private Button modelsTab;
    private TextView status;
    private TextView dimensions;
    private TextView percentText;
    private TextView viewerBadge;
    private ProgressBar progressBar;
    private LinearLayout actionRow;
    private ModelManagerView modelManagerView;
    private GenerativeView generativeView;
    private FrameLayout pageHost;

    private Bitmap originalBitmap;
    private Bitmap acceptedCrop;
    private Bitmap beforeBitmap;
    private Bitmap resultBitmap;
    private RectF acceptedSelection;
    private Uri savedResultUri;
    private Dialog resultDialog;
    private Dialog qualityDialog;
    private Bitmap pendingQualityBitmap;
    private Future<?> activeTask;
    private boolean processing;
    private boolean pendingEnhance;
    private volatile boolean destroyed;
    private boolean modelsPageVisible;
    private boolean generativePageVisible;
    private boolean generatedFromPrompt;
    private int progressValue;

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
        root.setPadding(dp(14), dp(8), dp(14), dp(10));

        root.addView(appBar(), fixed(54));
        root.addView(tabBar(), fixed(44));
        scroll = new LockableScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(12));
        content.addView(intro(), spacedWrap(10));
        content.addView(workspace(), spacedFixed(workspaceHeightDp(), 10));
        content.addView(fileAction(), spacedFixed(50, 10));

        actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        cropButton = button("Recortar", false);
        enhanceButton = button("Mejorar con IA", true);
        actionRow.addView(cropButton, weight(1f, 0, 7));
        actionRow.addView(enhanceButton, weight(1.25f, 0, 0));
        cropButton.setOnClickListener(v -> toggleCrop());
        enhanceButton.setOnClickListener(v -> enhance());
        content.addView(actionRow, spacedFixed(54, 10));

        LinearLayout progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(dp(13), dp(11), dp(13), dp(11));
        progressCard.setBackground(round(panel2, 14));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        status = text("Sube una foto para empezar", 13, ink);
        percentText = text("", 13, accent);
        percentText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        statusRow.addView(percentText, new LinearLayout.LayoutParams(-2, -2));
        progressCard.addView(statusRow);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        progressBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(53, 65, 57)));
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(7));
        progressParams.topMargin = dp(10);
        progressCard.addView(progressBar, progressParams);
        dimensions = text(
                "El resultado se guardará automáticamente en Imágenes/UGscaler",
                11,
                muted);
        dimensions.setPadding(0, dp(7), 0, 0);
        progressCard.addView(dimensions);
        content.addView(progressCard, spacedWrap(8));

        scroll.addView(content, new ScrollViewLayoutParams(-1, -2));
        android.widget.ScrollView modelScroll = new android.widget.ScrollView(this);
        modelScroll.setFillViewport(true);
        modelScroll.setVerticalScrollBarEnabled(false);
        modelScroll.setTag("models-page");
        modelManagerView = new ModelManagerView(this);
        modelScroll.addView(modelManagerView, new android.widget.ScrollView.LayoutParams(-1, -2));
        modelScroll.setVisibility(View.GONE);

        android.widget.ScrollView generativeScroll = new android.widget.ScrollView(this);
        generativeScroll.setFillViewport(true);
        generativeScroll.setVerticalScrollBarEnabled(false);
        generativeScroll.setTag("generative-page");
        generativeView = new GenerativeView(this, this);
        generativeScroll.addView(generativeView,
                new android.widget.ScrollView.LayoutParams(-1, -2));
        generativeScroll.setVisibility(View.GONE);

        pageHost = new FrameLayout(this);
        pageHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(generativeScroll, new FrameLayout.LayoutParams(-1, -1));
        pageHost.addView(modelScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(14), bars.top + dp(8), dp(14), bars.bottom + dp(10));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        refreshActions();
        showEditorPage();
    }

    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("UGscaler", 21, ink);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView label = text("MEJORA Y REESCALADO DE FOTOS", 9, cyan);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBox.addView(brand);
        titleBox.addView(label);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -1, 1f));
        newProjectButton = button("Nuevo proyecto", false);
        newProjectButton.setOnClickListener(v -> newProject());
        bar.addView(newProjectButton, fixedWidth(122));
        return bar;
    }

    private View tabBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(0, dp(2), 0, dp(4));
        editorTab = button("Editor", true);
        generativeTab = button("Crear", false);
        modelsTab = button("Modelos", false);
        editorTab.setOnClickListener(v -> showEditorPage());
        generativeTab.setOnClickListener(v -> showGenerativePage());
        modelsTab.setOnClickListener(v -> showModelsPage());
        tabs.addView(editorTab, weight(1f, 0, 5));
        tabs.addView(generativeTab, weight(1f, 0, 5));
        tabs.addView(modelsTab, weight(1f, 0, 0));
        return tabs;
    }

    private void showEditorPage() {
        if (scroll == null || pageHost == null) return;
        modelsPageVisible = false;
        generativePageVisible = false;
        scroll.setVisibility(View.VISIBLE);
        View modelsPage = pageHost.findViewWithTag("models-page");
        if (modelsPage != null) modelsPage.setVisibility(View.GONE);
        View generativePage = pageHost.findViewWithTag("generative-page");
        if (generativePage != null) generativePage.setVisibility(View.GONE);
        style(editorTab, true);
        style(generativeTab, false);
        style(modelsTab, false);
        newProjectButton.setVisibility(View.VISIBLE);
    }

    private void showModelsPage() {
        if (scroll == null || pageHost == null) return;
        modelsPageVisible = true;
        generativePageVisible = false;
        scroll.setVisibility(View.GONE);
        View modelsPage = pageHost.findViewWithTag("models-page");
        if (modelsPage != null) modelsPage.setVisibility(View.VISIBLE);
        View generativePage = pageHost.findViewWithTag("generative-page");
        if (generativePage != null) generativePage.setVisibility(View.GONE);
        style(editorTab, false);
        style(generativeTab, false);
        style(modelsTab, true);
        newProjectButton.setVisibility(View.GONE);
        modelManagerView.openAndCheck();
    }

    private void showGenerativePage() {
        if (scroll == null || pageHost == null || processing) return;
        modelsPageVisible = false;
        generativePageVisible = true;
        scroll.setVisibility(View.GONE);
        View modelsPage = pageHost.findViewWithTag("models-page");
        if (modelsPage != null) modelsPage.setVisibility(View.GONE);
        View generativePage = pageHost.findViewWithTag("generative-page");
        if (generativePage != null) generativePage.setVisibility(View.VISIBLE);
        style(editorTab, false);
        style(generativeTab, true);
        style(modelsTab, false);
        newProjectButton.setVisibility(View.GONE);
    }

    private View intro() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Mejora una foto en dos pasos", 20, ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = text(
                "Recorta si lo necesitas y deja que UGscaler seleccione la mejor escala.",
                12,
                muted);
        subtitle.setPadding(0, dp(4), 0, 0);
        box.addView(title);
        box.addView(subtitle);
        return box;
    }

    private View workspace() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(round(Color.rgb(17, 23, 20), 18));
        frame.setClipToOutline(true);
        frame.setElevation(dp(2));
        cropView = new CropImageView(this);
        cropView.setContentDescription("Previsualización y recorte de la fotografía");
        frame.addView(cropView, new FrameLayout.LayoutParams(-1, -1));
        viewerBadge = text("SIN FOTO", 10, ink);
        viewerBadge.setGravity(Gravity.CENTER);
        viewerBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        viewerBadge.setBackground(round(Color.argb(205, 18, 25, 21), 10));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(
                dp(116), dp(30), Gravity.TOP | Gravity.START);
        badge.setMargins(dp(11), dp(11), 0, 0);
        frame.addView(viewerBadge, badge);
        return frame;
    }

    private View fileAction() {
        uploadButton = button("Subir foto", false);
        uploadButton.setOnClickListener(v -> chooseImage());
        return uploadButton;
    }

    private void chooseImage() {
        if (processing) {
            toast("Espera a que termine el procesamiento");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) loadBitmap(uri);
    }

    private void loadBitmap(Uri uri) {
        cancelWork();
        int operation = generation.incrementAndGet();
        beginProgress("Preparando la fotografía…", 2);
        activeTask = executor.submit(() -> {
            Bitmap loaded = null;
            try {
                loaded = decodeSampled(uri, ProcessingMemory.photoDecodeMaxSide(this));
                if (loaded == null) throw new Exception("Imagen no válida");
                loaded = orient(uri, loaded);
                Bitmap delivered = loaded;
                loaded = null;
                runOnUiThread(() -> {
                    if (!isCurrent(operation)) {
                        recycle(delivered);
                        return;
                    }
                    replaceProjectImage(delivered);
                    endProgress("Foto preparada · puedes recortarla o mejorarla");
                });
            } catch (OutOfMemoryError error) {
                Log.e(TAG, "Sin memoria al cargar", error);
                fail(operation, "La fotografía es demasiado grande para este teléfono");
            } catch (Exception error) {
                Log.e(TAG, "No se pudo cargar", error);
                fail(operation, "No se pudo abrir esa fotografía");
            } finally {
                recycle(loaded);
            }
        });
    }

    private void replaceProjectImage(Bitmap bitmap) {
        dismissResult();
        recycleProjectBitmaps();
        originalBitmap = bitmap;
        cropView.setBitmap(bitmap);
        cropView.showFullImage();
        acceptedSelection = null;
        savedResultUri = null;
        viewerBadge.setText("ORIGINAL");
        uploadButton.setText("Cambiar foto");
        dimensions.setText(bitmap.getWidth() + " × " + bitmap.getHeight()
                + " px · IA local · salida PNG automática");
        scroll.setScrollingEnabled(true);
        refreshActions();
    }

    private void toggleCrop() {
        if (processing) return;
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            toast("Primero sube una foto");
            return;
        }
        if (cropView.isCropMode()) {
            acceptCrop();
        } else {
            cropView.setBitmap(originalBitmap);
            cropView.beginCrop();
            scroll.setScrollingEnabled(false);
            cropButton.setText("Aceptar");
            style(cropButton, true);
            enhanceButton.setEnabled(false);
            style(enhanceButton, true);
            viewerBadge.setText("AJUSTANDO RECORTE");
            status.setText("Mueve el rectángulo o arrastra sus esquinas");
            dimensions.setText("El desplazamiento de pantalla está bloqueado durante el recorte");
        }
    }

    private void acceptCrop() {
        Bitmap cropped = null;
        try {
            acceptedSelection = cropView.getSelectionNormalized();
            cropped = cropView.crop();
            recycle(acceptedCrop);
            acceptedCrop = cropped;
            cropped = null;
            cropView.setBitmap(acceptedCrop);
            cropView.showFullImage();
            scroll.setScrollingEnabled(true);
            cropButton.setText("Recortar");
            viewerBadge.setText("RECORTE");
            status.setText("Recorte aplicado · la IA también usará el contexto original");
            dimensions.setText(acceptedCrop.getWidth() + " × " + acceptedCrop.getHeight()
                    + " px · preparado para mejorar");
            refreshActions();
        } catch (OutOfMemoryError error) {
            recycle(cropped);
            scroll.setScrollingEnabled(true);
            toast("No hay memoria suficiente para crear este recorte");
        }
    }

    private void enhance() {
        if (processing) return;
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            toast("Primero sube una foto");
            return;
        }
        if (cropView.isCropMode()) {
            toast("Pulsa Aceptar para aplicar el recorte");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingEnhance = true;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION);
            return;
        }

        int operation = generation.incrementAndGet();
        final RectF selection = acceptedSelection == null ? null : new RectF(acceptedSelection);
        final Bitmap compareSource = copyOf(acceptedCrop != null ? acceptedCrop : originalBitmap);
        final Bitmap pipelineOriginal = copyOf(originalBitmap);
        if (compareSource == null || pipelineOriginal == null) {
            recycle(compareSource);
            recycle(pipelineOriginal);
            toast("No hay memoria suficiente para procesar la fotografía");
            return;
        }
        recycle(beforeBitmap);
        beforeBitmap = compareSource;
        generatedFromPrompt = false;
        beginProgress("Analizando desenfoque y movimiento…", 1);
        activeTask = executor.submit(() -> runEnhancement(operation, selection, pipelineOriginal));
    }

    private void runEnhancement(int operation, RectF selection, Bitmap pipelineOriginal) {
        ContextCrop contextCrop = null;
        Bitmap sourceCrop = null;
        Bitmap restoredCrop = null;
        Bitmap enhanced = null;
        try {
            contextCrop = createContextCrop(pipelineOriginal, selection);
            sourceCrop = contextCrop.extract(contextCrop.bitmap);
            if (sourceCrop == contextCrop.bitmap) sourceCrop = copyOf(contextCrop.bitmap);
            float focusScore = ImageQualityGuard.focusScore(sourceCrop);
            boolean needsDeblur = ImageQualityGuard.shouldDeblur(focusScore);
            // The model receives the selected area plus a safety margin from the
            // original. The final crop is taken only after restoration, so nearby
            // edges and textures can contribute context to the reconstruction.
            restoredCrop = copyOf(contextCrop.bitmap);
            setProgress(operation, 47, needsDeblur
                    ? "Analizando el desenfoque…"
                    : "Preparando la mejora…");
            int scale = ProcessingMemory.recommendedUpscale(this, restoredCrop);
            setProgress(operation, 50, "Mejorando la imagen ×" + scale + "…");
            startEstimatedUpscaleProgress(operation);
            try {
                enhanced = NativeRealEsrgan.enhance(this, restoredCrop, scale);
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception | OutOfMemoryError nativeError) {
                Log.w(TAG, "BSRGAN no disponible; usando restauración segura", nativeError);
                System.gc();
                enhanced = ImageEnhancer.enhance(this, restoredCrop, scale, 0, 0, 0, 0);
            }
            enhanced = ImageQualityGuard.ensureMinimumDimensions(enhanced, contextCrop.bitmap);
            setProgress(operation, 90, "Comprobando el resultado…");
            float artifactRisk = ImageQualityGuard.artifactRisk(enhanced, contextCrop.bitmap);
            boolean conservative = needsDeblur || artifactRisk > .01f;
            setProgress(operation, 92, conservative
                    ? "Protegiendo el aspecto original…"
                    : "Finalizando la mejora…");
            enhanced = ImageQualityGuard.protectInPlace(
                    enhanced,
                    contextCrop.bitmap,
                    conservative ? .35f : .90f,
                    conservative ? 20 : 56);
            enhanced = AdaptiveDetailRefiner.refine(enhanced, contextCrop.bitmap);
            Bitmap contextualResult = enhanced;
            enhanced = contextCrop.extract(contextualResult);
            if (enhanced != contextualResult) recycle(contextualResult);
            enhanced = ImageQualityGuard.ensureMinimumDimensions(enhanced, sourceCrop);
            float finalFocus = ImageQualityGuard.focusScore(enhanced);
            float finalArtifactRisk = ImageQualityGuard.artifactRisk(enhanced, sourceCrop);
            Log.i(TAG, "Protección de calidad aplicada; focusScore=" + focusScore
                    + ", desenfoque=" + needsDeblur + ", artifactRisk=" + artifactRisk
                    + ", finalFocus=" + finalFocus + ", finalArtifactRisk=" + finalArtifactRisk
                    + ", conservador=" + conservative);
            if (finalFocus < focusScore * 1.005f || finalArtifactRisk > .08f) {
                Bitmap candidate = enhanced;
                enhanced = null;
                runOnUiThread(() -> showQualityWarning(
                        operation, candidate, focusScore, finalFocus, finalArtifactRisk));
                return;
            }
            setProgress(operation, 95, "Guardando PNG en el carrete…");
            Uri saved = savePng(enhanced);
            Bitmap delivered = enhanced;
            enhanced = null;
            runOnUiThread(() -> completeEnhancement(operation, delivered, saved));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            fail(operation, "Procesamiento cancelado");
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Sin memoria durante la mejora", error);
            System.gc();
            fail(operation, "Memoria insuficiente · UGscaler ha conservado el original");
        } catch (Exception error) {
            Log.e(TAG, "Error de mejora", error);
            fail(operation, "No se pudo completar la mejora de esta fotografía");
        } finally {
            if (contextCrop != null) {
                Bitmap contextBitmap = contextCrop.bitmap;
                contextCrop.recycle();
                if (contextBitmap != pipelineOriginal) recycle(pipelineOriginal);
            } else {
                recycle(pipelineOriginal);
            }
            recycle(sourceCrop);
            recycle(restoredCrop);
            recycle(enhanced);
        }
    }

    private void showQualityWarning(int operation, Bitmap candidate, float originalFocus,
                                    float candidateFocus, float artifactRisk) {
        if (!isCurrent(operation)) {
            recycle(candidate);
            return;
        }
        discardQualityWarning();
        pendingQualityBitmap = candidate;
        setProgressNow(96, "La mejora necesita tu revisión");

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(round(panel, 20));
        TextView title = text("La IA no ha mejorado suficiente", 20, ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        float gain = originalFocus <= 0f ? 0f
                : (candidateFocus / originalFocus - 1f) * 100f;
        String reason = gain < .5f
                ? "No se ha recuperado más detalle medible que en el original."
                : "Se han detectado cambios que podrían ser artefactos.";
        TextView message = text(
                reason + " La resolución sí ha aumentado, pero UGscaler recomienda conservar "
                        + "la foto original. Puedes revisar el resultado igualmente.",
                13, muted);
        message.setPadding(0, dp(8), 0, dp(15));
        card.addView(message);
        LinearLayout buttons = new LinearLayout(this);
        Button keep = button("Conservar original", true);
        Button continueButton = button("Guardar igualmente", false);
        buttons.addView(keep, weight(1.15f, 0, 6));
        buttons.addView(continueButton, weight(1f, 0, 0));
        card.addView(buttons, fixed(52));

        keep.setOnClickListener(v -> rejectLowQualityResult());
        continueButton.setOnClickListener(v -> acceptLowQualityResult(operation));
        dialog.setOnCancelListener(ignored -> rejectLowQualityResult());
        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        qualityDialog = dialog;
        dialog.show();
        if (window != null) {
            window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(24),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        Log.w(TAG, "Resultado pendiente de confirmación; gain=" + gain
                + ", artifactRisk=" + artifactRisk);
    }

    private void acceptLowQualityResult(int operation) {
        Bitmap candidate = pendingQualityBitmap;
        pendingQualityBitmap = null;
        if (qualityDialog != null) qualityDialog.dismiss();
        qualityDialog = null;
        if (candidate == null || candidate.isRecycled() || !isCurrent(operation)) {
            recycle(candidate);
            endProgress("Resultado descartado");
            return;
        }
        setProgressNow(97, "Guardando PNG en el carrete…");
        activeTask = executor.submit(() -> {
            try {
                Uri saved = savePng(candidate);
                runOnUiThread(() -> completeEnhancement(operation, candidate, saved));
            } catch (Exception error) {
                recycle(candidate);
                fail(operation, "No se pudo guardar el resultado");
            }
        });
    }

    private void rejectLowQualityResult() {
        Bitmap rejected = pendingQualityBitmap;
        pendingQualityBitmap = null;
        if (qualityDialog != null) qualityDialog.dismiss();
        qualityDialog = null;
        recycle(rejected);
        endProgress("Original conservado · la IA no aportó una mejora suficiente");
    }

    private void discardQualityWarning() {
        Bitmap rejected = pendingQualityBitmap;
        pendingQualityBitmap = null;
        if (qualityDialog != null) qualityDialog.dismiss();
        qualityDialog = null;
        recycle(rejected);
    }

    private void completeEnhancement(int operation, Bitmap result, Uri saved) {
        if (!isCurrent(operation)) {
            recycle(result);
            return;
        }
        recycle(resultBitmap);
        resultBitmap = result;
        savedResultUri = saved;
        setProgressNow(100, "Mejora completada · PNG guardado en el carrete");
        dimensions.setText(result.getWidth() + " × " + result.getHeight()
                + " px · PNG sin pérdida · Imágenes/UGscaler");
        processing = false;
        activeTask = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        refreshActions();
        showResultDialog();
    }

    private void showResultDialog() {
        if (resultBitmap == null || resultBitmap.isRecycled()) return;
        dismissResult();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(panel, 20));

        boolean canCompare = beforeBitmap != null && !beforeBitmap.isRecycled();
        TextView title = text(generatedFromPrompt ? "Imagen generada" : "Foto mejorada", 21, ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        TextView saved = text(
                "Guardada automáticamente como PNG en Imágenes/UGscaler",
                11,
                muted);
        saved.setPadding(0, dp(3), 0, dp(10));
        card.addView(saved);

        FrameLayout previewFrame = new FrameLayout(this);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(false);
        preview.setBackgroundColor(Color.rgb(13, 18, 16));
        preview.setImageBitmap(resultBitmap);
        preview.setContentDescription("Resultado mejorado");
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView comparisonBadge = text("MEJORADA", 10, ink);
        comparisonBadge.setGravity(Gravity.CENTER);
        comparisonBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        comparisonBadge.setBackground(round(Color.argb(225, 18, 25, 21), 10));
        FrameLayout.LayoutParams comparisonBadgeParams = new FrameLayout.LayoutParams(
                dp(112), dp(30), Gravity.TOP | Gravity.START);
        comparisonBadgeParams.setMargins(dp(10), dp(10), 0, 0);
        previewFrame.addView(comparisonBadge, comparisonBadgeParams);
        card.addView(previewFrame,
                new LinearLayout.LayoutParams(-1, dp(resultPreviewHeightDp())));

        TextView hint = text(
                canCompare
                        ? "Toca Comparar para alternar · mantenlo pulsado para una vista rápida"
                        : "Creada íntegramente en el dispositivo",
                11,
                muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(8));
        card.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        HoldCompareButton compare = holdButton("Comparar", false);
        Button share = button("Compartir", true);
        Button close = button("Cerrar", false);
        buttons.addView(compare, weight(1f, 0, 6));
        buttons.addView(share, weight(1f, 0, 6));
        buttons.addView(close, weight(1f, 0, 0));
        card.addView(buttons, fixed(50));

        final boolean[] showingOriginal = {false};
        Runnable renderComparison = () -> {
            boolean original = showingOriginal[0]
                    && beforeBitmap != null && !beforeBitmap.isRecycled();
            preview.setImageBitmap(original ? beforeBitmap : resultBitmap);
            preview.setContentDescription(original ? "Foto original" : "Resultado mejorado");
            comparisonBadge.setText(original ? "ORIGINAL" : "MEJORADA");
            compare.setText(original ? "Ver mejora" : "Comparar");
            compare.setContentDescription(original
                    ? "Mostrar fotografía mejorada"
                    : "Mostrar fotografía original");
            hint.setText(original
                    ? "Mostrando el original · toca Ver mejora para volver"
                    : "Mostrando la mejora · toca Comparar para ver el original");
        };
        compare.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                if (beforeBitmap != null && !beforeBitmap.isRecycled()) {
                    preview.setImageBitmap(beforeBitmap);
                    preview.setContentDescription("Foto original");
                    comparisonBadge.setText("ORIGINAL");
                    hint.setText("Original · suelta para fijarlo o volver a la mejora");
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.setPressed(false);
                long duration = event.getEventTime() - event.getDownTime();
                if (duration < 450L) showingOriginal[0] = !showingOriginal[0];
                renderComparison.run();
                view.performClick();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                renderComparison.run();
                return true;
            }
            return true;
        });
        compare.setEnabled(canCompare);
        compare.setVisibility(canCompare ? View.VISIBLE : View.GONE);
        share.setOnClickListener(v -> shareResult());
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(card);
        dialog.setOnDismissListener(ignored -> {
            if (resultDialog == dialog) resultDialog = null;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels - dp(20);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = .82f;
            window.setAttributes(params);
        }
        resultDialog = dialog;
        dialog.show();
        if (window != null) {
            window.setLayout(
                    getResources().getDisplayMetrics().widthPixels - dp(20),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private Uri savePng(Bitmap bitmap) throws Exception {
        String name = "ugscaler_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/UGscaler");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File directory = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "UGscaler");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new Exception("No se pudo crear la carpeta UGscaler");
            }
            values.put(MediaStore.Images.Media.DATA, new File(directory, name).getAbsolutePath());
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("No se pudo crear el PNG");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new Exception("No se pudo escribir el PNG");
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null);
            }
            return uri;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private void shareResult() {
        if (savedResultUri == null) {
            toast("La imagen todavía no está disponible para compartir");
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, savedResultUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newUri(
                getContentResolver(), "UGscaler PNG", savedResultUri));
        try {
            startActivity(Intent.createChooser(share, "Compartir foto mejorada"));
        } catch (Exception error) {
            toast("No hay una aplicación disponible para compartir");
        }
    }

    private ContextCrop createContextCrop(Bitmap original, RectF selection) {
        if (selection == null) {
            return new ContextCrop(original, new RectF(0, 0, 1, 1));
        }
        int width = original.getWidth();
        int height = original.getHeight();
        float marginX = Math.max(.035f, selection.width() * .14f);
        float marginY = Math.max(.035f, selection.height() * .14f);
        float left = Math.max(0f, selection.left - marginX);
        float top = Math.max(0f, selection.top - marginY);
        float right = Math.min(1f, selection.right + marginX);
        float bottom = Math.min(1f, selection.bottom + marginY);
        int x = Math.max(0, Math.round(left * width));
        int y = Math.max(0, Math.round(top * height));
        int r = Math.min(width, Math.round(right * width));
        int b = Math.min(height, Math.round(bottom * height));
        Bitmap contextual = Bitmap.createBitmap(original, x, y, Math.max(1, r - x), Math.max(1, b - y));
        if (contextual == original) contextual = copyOf(original);
        RectF target = new RectF(
                (selection.left - left) / (right - left),
                (selection.top - top) / (bottom - top),
                (selection.right - left) / (right - left),
                (selection.bottom - top) / (bottom - top));
        return new ContextCrop(contextual, target);
    }

    private void beginProgress(String message, int initial) {
        processing = true;
        progressValue = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressBar.setVisibility(View.VISIBLE);
        setProgressNow(initial, message);
        refreshActions();
    }

    private void setProgress(int operation, int value, String message) {
        runOnUiThread(() -> {
            if (isCurrent(operation) && processing) setProgressNow(value, message);
        });
    }

    private void setProgressNow(int value, String message) {
        progressValue = Math.max(progressValue, Math.min(100, value));
        progressBar.setProgress(progressValue, true);
        percentText.setText(progressValue + "%");
        status.setText(message);
    }

    private void startEstimatedUpscaleProgress(int operation) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isCurrent(operation) || !processing || progressValue >= 92) return;
                setProgressNow(progressValue + 1, "Mejorando detalle…");
                handler.postDelayed(this, 650);
            }
        }, 650);
    }

    private void endProgress(String message) {
        processing = false;
        activeTask = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressValue = 0;
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        status.setText(message);
        refreshActions();
    }

    private void fail(int operation, String message) {
        runOnUiThread(() -> {
            if (!isCurrent(operation)) return;
            endProgress(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void refreshActions() {
        boolean hasImage = originalBitmap != null && !originalBitmap.isRecycled();
        cropButton.setEnabled(hasImage && !processing);
        enhanceButton.setEnabled(hasImage && !processing && !cropView.isCropMode());
        uploadButton.setEnabled(!processing);
        newProjectButton.setEnabled(!processing || hasImage);
        generativeTab.setEnabled(!processing);
        modelsTab.setEnabled(!processing);
        style(cropButton, cropView.isCropMode());
        style(enhanceButton, true);
        style(uploadButton, false);
        style(newProjectButton, false);
    }

    private void newProject() {
        dismissResult();
        cancelWork();
        cropView.setBitmap(null);
        scroll.setScrollingEnabled(true);
        recycleProjectBitmaps();
        acceptedSelection = null;
        savedResultUri = null;
        viewerBadge.setText("SIN FOTO");
        uploadButton.setText("Subir foto");
        cropButton.setText("Recortar");
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        status.setText("Sube una foto para empezar");
        dimensions.setText("El resultado se guardará automáticamente en Imágenes/UGscaler");
        refreshActions();
    }

    private void cancelWork() {
        discardQualityWarning();
        generation.incrementAndGet();
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) task.cancel(true);
        NativeRealEsrgan.cancelActive();
        processing = false;
        progressValue = 0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void dismissResult() {
        if (resultDialog != null) {
            resultDialog.dismiss();
            resultDialog = null;
        }
    }

    private void recycleProjectBitmaps() {
        recycle(originalBitmap);
        recycle(acceptedCrop);
        recycle(beforeBitmap);
        recycle(resultBitmap);
        originalBitmap = null;
        acceptedCrop = null;
        beforeBitmap = null;
        resultBitmap = null;
    }

    private Bitmap decodeSampled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("Imagen no válida");
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 1.35f) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            if (decoded == null) throw new Exception("No se pudo decodificar");
            Bitmap fitted = ProcessingMemory.fit(decoded, maxSide);
            if (fitted != decoded) decoded.recycle();
            return fitted;
        }
    }

    private Bitmap orient(Uri uri, Bitmap bitmap) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(stream);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return rotate(bitmap, 90);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return rotate(bitmap, 180);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return rotate(bitmap, 270);
        } catch (Exception ignored) {
            // The decoded bitmap is still usable when EXIF is absent.
        }
        return bitmap;
    }

    private Bitmap rotate(Bitmap bitmap, int degrees) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private boolean isCurrent(int operation) {
        return !destroyed && operation == generation.get();
    }

    @Override public void onBackPressed() {
        if (resultDialog != null) {
            dismissResult();
        } else if (modelsPageVisible || generativePageVisible) {
            showEditorPage();
        } else if (cropView.isCropMode()) {
            scroll.setScrollingEnabled(true);
            cropView.setBitmap(acceptedCrop != null ? acceptedCrop : originalBitmap);
            cropView.showFullImage();
            cropButton.setText("Recortar");
            refreshActions();
        } else if (originalBitmap != null) {
            newProject();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        dismissResult();
        discardQualityWarning();
        cancelWork();
        if (modelManagerView != null) modelManagerView.close();
        if (generativeView != null) generativeView.close();
        executor.shutdownNow();
        cropView.setBitmap(null);
        recycleProjectBitmaps();
        super.onDestroy();
    }

    @Override public Bitmap sourceForGenerative() {
        Bitmap source = acceptedCrop != null && !acceptedCrop.isRecycled()
                ? acceptedCrop : originalBitmap;
        return copyOf(source);
    }

    @Override public void showDownloads() {
        showModelsPage();
    }

    @Override public void deliverGenerativeResult(Bitmap result, Bitmap before) {
        if (result == null || result.isRecycled()) {
            recycle(before);
            toast("La IA local no devolvió una imagen válida");
            return;
        }
        final int operation = generation.incrementAndGet();
        processing = true;
        generatedFromPrompt = before == null;
        refreshActions();
        activeTask = executor.submit(() -> {
            Uri saved = null;
            Exception saveError = null;
            try {
                saved = savePng(result);
            } catch (Exception error) {
                saveError = error;
                Log.e(TAG, "No se pudo guardar el resultado generativo", error);
            }
            Uri deliveredUri = saved;
            Exception deliveredError = saveError;
            runOnUiThread(() -> {
                if (!isCurrent(operation)) {
                    recycle(result);
                    recycle(before);
                    return;
                }
                recycle(resultBitmap);
                recycle(beforeBitmap);
                resultBitmap = result;
                beforeBitmap = before;
                savedResultUri = deliveredUri;
                processing = false;
                activeTask = null;
                refreshActions();
                if (deliveredError != null) {
                    toast("Imagen creada, pero no se pudo guardar en el carrete");
                }
                showResultDialog();
            });
        });
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION && pendingEnhance) {
            pendingEnhance = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enhance();
            } else {
                toast("Se necesita permiso para guardar el PNG automáticamente");
            }
        }
    }

    private int workspaceHeightDp() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int dpHeight = Math.round(screen / getResources().getDisplayMetrics().density);
        return Math.max(280, Math.min(510, Math.round(dpHeight * .46f)));
    }

    private int resultPreviewHeightDp() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int dpHeight = Math.round(screen / getResources().getDisplayMetrics().density);
        return Math.max(300, Math.min(570, Math.round(dpHeight * .58f)));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setContentDescription(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setElevation(dp(1));
        style(button, primary);
        return button;
    }

    private HoldCompareButton holdButton(String value, boolean primary) {
        HoldCompareButton button = new HoldCompareButton(this);
        button.setText(value);
        button.setContentDescription(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setElevation(dp(1));
        style(button, primary);
        return button;
    }

    private void style(Button button, boolean primary) {
        button.setTextColor(primary ? Color.rgb(18, 24, 19) : ink);
        button.setBackground(round(primary ? accent : panel, 14));
        button.setAlpha(button.isEnabled() ? 1f : .42f);
    }

    private android.graphics.drawable.GradientDrawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), Color.rgb(52, 67, 58));
        return drawable;
    }

    private LinearLayout.LayoutParams fixed(int height) {
        return new LinearLayout.LayoutParams(-1, dp(height));
    }

    private LinearLayout.LayoutParams fixedWidth(int width) {
        return new LinearLayout.LayoutParams(dp(width), -1);
    }

    private LinearLayout.LayoutParams spacedFixed(int height, int bottom) {
        LinearLayout.LayoutParams params = fixed(height);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private LinearLayout.LayoutParams spacedWrap(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private LinearLayout.LayoutParams weight(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.leftMargin = dp(left);
        params.rightMargin = dp(right);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static Bitmap copyOf(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        return source.copy(Bitmap.Config.ARGB_8888, false);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static final class ScrollViewLayoutParams extends android.widget.ScrollView.LayoutParams {
        ScrollViewLayoutParams(int width, int height) {
            super(width, height);
        }
    }

    private static final class ContextCrop {
        final Bitmap bitmap;
        final RectF target;

        ContextCrop(Bitmap bitmap, RectF target) {
            if (bitmap == null) throw new OutOfMemoryError("No se pudo preparar el contexto");
            this.bitmap = bitmap;
            this.target = target;
        }

        Bitmap extract(Bitmap restored) {
            if (target.left <= 0f && target.top <= 0f
                    && target.right >= 1f && target.bottom >= 1f) return restored;
            int x = Math.max(0, Math.round(target.left * restored.getWidth()));
            int y = Math.max(0, Math.round(target.top * restored.getHeight()));
            int right = Math.min(restored.getWidth(), Math.round(target.right * restored.getWidth()));
            int bottom = Math.min(restored.getHeight(), Math.round(target.bottom * restored.getHeight()));
            return Bitmap.createBitmap(
                    restored, x, y, Math.max(1, right - x), Math.max(1, bottom - y));
        }

        void recycle() {
            MainActivity.recycle(bitmap);
        }
    }
}
