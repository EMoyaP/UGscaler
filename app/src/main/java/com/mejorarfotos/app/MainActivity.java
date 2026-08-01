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
import android.os.CancellationSignal;
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

import androidx.annotation.NonNull;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

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
public class MainActivity extends Activity {
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
    private Button localModeButton;
    private Button generativeModeButton;
    private TextView status;
    private TextView dimensions;
    private TextView percentText;
    private TextView viewerBadge;
    private ProgressBar progressBar;
    private LinearLayout actionRow;
    private LinearLayout generativePanel;
    private TextView accountStatus;
    private Button googleAccountButton;
    private TextView engineDescription;
    private FirebaseAuth firebaseAuth;
    private CredentialManager credentialManager;

    private Bitmap originalBitmap;
    private Bitmap acceptedCrop;
    private Bitmap beforeBitmap;
    private Bitmap resultBitmap;
    private RectF acceptedSelection;
    private Uri savedResultUri;
    private Dialog resultDialog;
    private Future<?> activeTask;
    private boolean processing;
    private boolean pendingEnhance;
    private boolean pendingGenerativeAfterSignIn;
    private boolean signingIn;
    private boolean generativeMode;
    private boolean resultWasGenerative;
    private volatile boolean destroyed;
    private int progressValue;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        firebaseAuth = FirebaseAuth.getInstance();
        credentialManager = CredentialManager.create(this);
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
        scroll = new LockableScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(12));
        content.addView(intro(), spacedWrap(10));
        content.addView(modeTabs(), spacedFixed(52, 8));
        generativePanel = generativeSettings();
        content.addView(generativePanel, spacedWrap(10));
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
        dimensions = text("Procesado privado · sin conexión · salida PNG", 11, muted);
        dimensions.setPadding(0, dp(7), 0, 0);
        progressCard.addView(dimensions);
        content.addView(progressCard, spacedWrap(8));

        engineDescription = text(
                "RT-Focuser elimina desenfoque y Real-ESRGAN recupera resolución. "
                        + "La escala se adapta automáticamente a la foto y al teléfono.",
                11,
                muted);
        engineDescription.setGravity(Gravity.CENTER);
        engineDescription.setPadding(dp(10), dp(7), dp(10), dp(5));
        content.addView(engineDescription, spacedWrap(0));

        scroll.addView(content, new ScrollViewLayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(14), bars.top + dp(8), dp(14), bars.bottom + dp(10));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        setGenerativeMode(false);
        refreshActions();
    }

    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("UGscaler", 21, ink);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView label = text("RESTAURACIÓN FOTOGRÁFICA · IA LOCAL + GOOGLE", 9, cyan);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBox.addView(brand);
        titleBox.addView(label);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -1, 1f));
        newProjectButton = button("Nuevo proyecto", false);
        newProjectButton.setOnClickListener(v -> newProject());
        bar.addView(newProjectButton, fixedWidth(122));
        return bar;
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

    private View modeTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        localModeButton = button("IA local", true);
        generativeModeButton = button("IA generativa", false);
        row.addView(localModeButton, weight(1f, 0, 7));
        row.addView(generativeModeButton, weight(1f, 0, 0));
        localModeButton.setOnClickListener(v -> setGenerativeMode(false));
        generativeModeButton.setOnClickListener(v -> setGenerativeMode(true));
        return row;
    }

    private LinearLayout generativeSettings() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(round(Color.rgb(35, 35, 27), 14));

        TextView title = text("NANO BANANA 2 · CUENTA GOOGLE", 11, accent);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        TextView warning = text(
                "La foto se envía a Google solo al usar este modo. La IA puede reconstruir "
                        + "detalles plausibles que no coincidan exactamente con la realidad.",
                11,
                ink);
        warning.setPadding(0, dp(4), 0, dp(8));
        card.addView(warning);

        accountStatus = text("No has iniciado sesión", 12, muted);
        accountStatus.setPadding(dp(12), 0, dp(12), 0);
        accountStatus.setBackground(round(panel, 12));
        accountStatus.setContentDescription("Estado de la cuenta Google");
        card.addView(accountStatus, fixed(44));

        googleAccountButton = button("Continuar con Google", true);
        LinearLayout.LayoutParams accountButtonParams = fixed(48);
        accountButtonParams.topMargin = dp(8);
        card.addView(googleAccountButton, accountButtonParams);
        TextView quotaNotice = text(
                "Iniciar sesión no activa pagos. La mejora generativa depende de la cuota cloud del proyecto.",
                10,
                muted);
        quotaNotice.setPadding(dp(2), dp(7), dp(2), 0);
        quotaNotice.setGravity(Gravity.CENTER);
        card.addView(quotaNotice);
        googleAccountButton.setOnClickListener(v -> {
            if (firebaseAuth.getCurrentUser() == null) signInWithGoogle(false);
            else signOutGoogle();
        });
        updateGoogleAccountUi();
        return card;
    }

    private void setGenerativeMode(boolean enabled) {
        if (processing) {
            toast("Espera a que termine el procesamiento");
            return;
        }
        generativeMode = enabled;
        if (generativePanel != null) {
            generativePanel.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (localModeButton != null) style(localModeButton, !enabled);
        if (generativeModeButton != null) style(generativeModeButton, enabled);
        if (enhanceButton != null) {
            enhanceButton.setText(enabled ? "Mejorar con IA generativa" : "Mejorar con IA");
        }
        if (engineDescription != null) {
            engineDescription.setText(enabled
                    ? "RT-Focuser prepara el recorte y Nano Banana reconstruye el detalle. "
                            + "Requiere Internet, una cuenta Google y cuota disponible."
                    : "RT-Focuser elimina desenfoque y Real-ESRGAN recupera resolución. "
                            + "La escala se adapta automáticamente a la foto y al teléfono.");
        }
        if (dimensions != null && originalBitmap == null) {
            dimensions.setText(enabled
                    ? "Procesamiento en Gemini API · salida PNG"
                    : "Procesado privado · sin conexión · salida PNG");
        }
        if (dimensions != null && originalBitmap != null && resultBitmap == null) {
            Bitmap shown = acceptedCrop != null ? acceptedCrop : originalBitmap;
            dimensions.setText(shown.getWidth() + " × " + shown.getHeight()
                    + " px · " + (enabled ? "Nano Banana API" : "IA local")
                    + " · salida PNG automática");
        }
        if (status != null && originalBitmap == null) {
            status.setText(enabled && firebaseAuth.getCurrentUser() == null
                    ? "Identifícate con Google y sube una foto"
                    : "Sube una foto para empezar");
        }
        refreshActions();
    }

    private void signInWithGoogle(boolean continueAfterSignIn) {
        if (signingIn) return;
        pendingGenerativeAfterSignIn = continueAfterSignIn;
        signingIn = true;
        updateGoogleAccountUi();
        com.google.android.libraries.identity.googleid.GetGoogleIdOption googleOption =
                new com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setAutoSelectEnabled(false)
                        .setServerClientId(getString(R.string.default_web_client_id))
                        .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        credentialManager.getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(@NonNull GetCredentialResponse result) {
                        handleGoogleCredential(result.getCredential());
                    }

                    @Override public void onError(@NonNull GetCredentialException error) {
                        signingIn = false;
                        pendingGenerativeAfterSignIn = false;
                        updateGoogleAccountUi();
                        if (error instanceof NoCredentialException) {
                            toast("No hay ninguna cuenta Google disponible en el teléfono");
                        } else if (!(error instanceof GetCredentialCancellationException)) {
                            Log.e(TAG, "Error al seleccionar cuenta Google", error);
                            toast("No se pudo abrir el acceso con Google");
                        }
                    }
                });
    }

    private void handleGoogleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                .equals(credential.getType())) {
            signingIn = false;
            updateGoogleAccountUi();
            toast("La credencial seleccionada no es una cuenta Google");
            return;
        }
        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(
                    ((CustomCredential) credential).getData());
            AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(
                    googleCredential.getIdToken(), null);
            firebaseAuth.signInWithCredential(firebaseCredential)
                    .addOnCompleteListener(this, task -> {
                        signingIn = false;
                        updateGoogleAccountUi();
                        if (task.isSuccessful()) {
                            toast("Sesión iniciada con Google");
                            if (pendingGenerativeAfterSignIn) {
                                pendingGenerativeAfterSignIn = false;
                                enhance();
                            }
                        } else {
                            pendingGenerativeAfterSignIn = false;
                            Log.e(TAG, "Firebase Auth rechazó la cuenta", task.getException());
                            toast("Google no pudo validar la sesión");
                        }
                    });
        } catch (Exception error) {
            signingIn = false;
            pendingGenerativeAfterSignIn = false;
            updateGoogleAccountUi();
            Log.e(TAG, "Token de Google no válido", error);
            toast("La respuesta de Google no es válida");
        }
    }

    private void signOutGoogle() {
        firebaseAuth.signOut();
        pendingGenerativeAfterSignIn = false;
        updateGoogleAccountUi();
        credentialManager.clearCredentialStateAsync(
                new ClearCredentialStateRequest(),
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override public void onResult(@NonNull Void ignored) {
                        updateGoogleAccountUi();
                    }

                    @Override public void onError(@NonNull ClearCredentialException error) {
                        Log.w(TAG, "No se pudo limpiar el selector de cuentas", error);
                    }
                });
        toast("Sesión de Google cerrada");
    }

    private void updateGoogleAccountUi() {
        if (accountStatus == null || googleAccountButton == null) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (signingIn) {
            accountStatus.setText("Abriendo el selector seguro de Google…");
            googleAccountButton.setText("Conectando…");
            googleAccountButton.setEnabled(false);
        } else if (user != null) {
            String identity = user.getEmail();
            if (identity == null || identity.trim().isEmpty()) identity = user.getDisplayName();
            accountStatus.setText(identity == null ? "Cuenta Google conectada" : identity);
            googleAccountButton.setText("Cerrar sesión");
            googleAccountButton.setEnabled(!processing);
        } else {
            accountStatus.setText("No has iniciado sesión");
            googleAccountButton.setText("Continuar con Google");
            googleAccountButton.setEnabled(!processing);
        }
        style(googleAccountButton, user == null);
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
        resultWasGenerative = false;
        cropView.setBitmap(bitmap);
        cropView.showFullImage();
        acceptedSelection = null;
        savedResultUri = null;
        viewerBadge.setText("ORIGINAL");
        uploadButton.setText("Cambiar foto");
        dimensions.setText(bitmap.getWidth() + " × " + bitmap.getHeight()
                + " px · " + (generativeMode ? "Nano Banana API" : "IA local")
                + " · salida PNG automática");
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
        final boolean requestedGenerative = generativeMode;
        if (requestedGenerative && firebaseAuth.getCurrentUser() == null) {
            toast("Identifícate con Google para usar la IA generativa");
            signInWithGoogle(true);
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
        beginProgress(
                requestedGenerative
                        ? "Preparando la restauración generativa…"
                        : "Analizando desenfoque y movimiento…",
                1);
        activeTask = executor.submit(() -> {
            if (requestedGenerative) {
                runGenerativeEnhancement(operation, selection, pipelineOriginal);
            } else {
                runEnhancement(operation, selection, pipelineOriginal);
            }
        });
    }

    private void runEnhancement(int operation, RectF selection, Bitmap pipelineOriginal) {
        ContextCrop contextCrop = null;
        Bitmap sourceCrop = null;
        Bitmap deblurredContext = null;
        Bitmap restoredCrop = null;
        Bitmap enhanced = null;
        try {
            contextCrop = createContextCrop(pipelineOriginal, selection);
            sourceCrop = contextCrop.extract(contextCrop.bitmap);
            if (sourceCrop == contextCrop.bitmap) sourceCrop = copyOf(contextCrop.bitmap);
            float focusScore = ImageQualityGuard.focusScore(sourceCrop);
            boolean needsDeblur = ImageQualityGuard.shouldDeblur(focusScore);
            if (needsDeblur) {
                setProgress(operation, 4, "RT-Focuser · corrigiendo desenfoque detectado…");
                int deblurMax = ProcessingMemory.deblurInputMaxSide(this);
                deblurredContext = RtFocuserDeblurrer.restore(
                        this,
                        contextCrop.bitmap,
                        deblurMax,
                        value -> setProgress(operation, 4 + value * 43 / 100,
                                "RT-Focuser · eliminando desenfoque…"));
                restoredCrop = contextCrop.extract(deblurredContext);
                if (restoredCrop != deblurredContext) {
                    recycle(deblurredContext);
                    deblurredContext = null;
                }
            } else {
                restoredCrop = copyOf(sourceCrop);
                setProgress(operation, 47,
                        "Detalle original protegido · evitando sobreenfoque…");
            }
            int scale = ProcessingMemory.recommendedUpscale(this, restoredCrop);
            setProgress(operation, 50,
                    "Real-ESRGAN · reescalando automáticamente ×" + scale + "…");
            startEstimatedUpscaleProgress(operation);
            try {
                enhanced = NativeRealEsrgan.enhance(this, restoredCrop, scale);
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception | OutOfMemoryError nativeError) {
                Log.w(TAG, "Real-ESRGAN no disponible; usando restauración segura", nativeError);
                System.gc();
                enhanced = ImageEnhancer.enhance(this, restoredCrop, scale, 0, 0, 0, 0);
            }
            setProgress(operation, 92, "Protegiendo color, luces y detalle original…");
            enhanced = ImageQualityGuard.protectInPlace(
                    enhanced,
                    sourceCrop,
                    scale,
                    needsDeblur ? .68f : .50f,
                    needsDeblur ? 36 : 24);
            Log.i(TAG, "Protección de calidad aplicada; focusScore=" + focusScore
                    + ", deblur=" + needsDeblur);
            setProgress(operation, 95, "Guardando PNG en el carrete…");
            Uri saved = savePng(enhanced);
            Bitmap delivered = enhanced;
            enhanced = null;
            runOnUiThread(() -> completeEnhancement(operation, delivered, saved, false));
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
            recycle(deblurredContext);
            recycle(sourceCrop);
            recycle(restoredCrop);
            recycle(enhanced);
        }
    }

    private void runGenerativeEnhancement(
            int operation, RectF selection, Bitmap pipelineOriginal) {
        ContextCrop contextCrop = null;
        Bitmap sourceCrop = null;
        Bitmap deblurredContext = null;
        Bitmap preparedCrop = null;
        Bitmap generated = null;
        try {
            contextCrop = createContextCrop(pipelineOriginal, selection);
            sourceCrop = contextCrop.extract(contextCrop.bitmap);
            if (sourceCrop == contextCrop.bitmap) sourceCrop = copyOf(contextCrop.bitmap);
            float focusScore = ImageQualityGuard.focusScore(sourceCrop);
            if (ImageQualityGuard.shouldDeblur(focusScore)) {
                setProgress(operation, 4, "RT-Focuser · preparando bordes y estructura…");
                deblurredContext = RtFocuserDeblurrer.restore(
                        this,
                        contextCrop.bitmap,
                        Math.min(1280, ProcessingMemory.deblurInputMaxSide(this)),
                        value -> setProgress(operation, 4 + value * 31 / 100,
                                "RT-Focuser · preparando la referencia…"));
                preparedCrop = contextCrop.extract(deblurredContext);
                if (preparedCrop != deblurredContext) {
                    recycle(deblurredContext);
                    deblurredContext = null;
                }
            } else {
                preparedCrop = copyOf(sourceCrop);
                setProgress(operation, 35, "Detalle original protegido · preparando Gemini…");
            }
            setProgress(operation, 38, "Enviando referencias cifradas por HTTPS a Gemini…");
            startEstimatedGenerativeProgress(operation);
            generated = GeminiImageRestorer.restore(preparedCrop, contextCrop.bitmap);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelado");
            setProgress(operation, 95, "Guardando el resultado generativo como PNG…");
            Uri saved = savePng(generated);
            Bitmap delivered = generated;
            generated = null;
            runOnUiThread(() -> completeEnhancement(operation, delivered, saved, true));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            fail(operation, "Procesamiento generativo cancelado");
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Sin memoria en el modo generativo", error);
            System.gc();
            fail(operation, "Memoria insuficiente · se ha conservado el original");
        } catch (Exception error) {
            Log.e(TAG, "Error de Gemini", error);
            fail(operation, safeApiMessage(error));
        } finally {
            if (contextCrop != null) {
                Bitmap contextBitmap = contextCrop.bitmap;
                contextCrop.recycle();
                if (contextBitmap != pipelineOriginal) recycle(pipelineOriginal);
            } else {
                recycle(pipelineOriginal);
            }
            recycle(deblurredContext);
            recycle(sourceCrop);
            recycle(preparedCrop);
            recycle(generated);
        }
    }

    private String safeApiMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "No se pudo completar la mejora generativa";
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }

    private void completeEnhancement(int operation, Bitmap result, Uri saved, boolean generative) {
        if (!isCurrent(operation)) {
            recycle(result);
            return;
        }
        recycle(resultBitmap);
        resultBitmap = result;
        savedResultUri = saved;
        resultWasGenerative = generative;
        setProgressNow(100, generative
                ? "Reconstrucción generativa completada · PNG guardado"
                : "Mejora completada · PNG guardado en el carrete");
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

        TextView title = text(
                resultWasGenerative ? "Reconstrucción generativa" : "Foto mejorada",
                21,
                ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        TextView saved = text(
                resultWasGenerative
                        ? "PNG guardado · puede contener detalles plausibles generados por IA"
                        : "Guardada automáticamente como PNG en Imágenes/UGscaler",
                11,
                resultWasGenerative ? accent : muted);
        saved.setPadding(0, dp(3), 0, dp(10));
        card.addView(saved);

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(false);
        preview.setBackgroundColor(Color.rgb(13, 18, 16));
        preview.setImageBitmap(resultBitmap);
        preview.setContentDescription("Resultado mejorado");
        card.addView(preview, new LinearLayout.LayoutParams(-1, dp(resultPreviewHeightDp())));

        TextView hint = text("Mantén pulsado Comparar para ver el original", 11, muted);
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

        compare.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                preview.setImageBitmap(beforeBitmap);
                viewerBadge.setText("ORIGINAL");
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                preview.setImageBitmap(resultBitmap);
                viewerBadge.setText(acceptedCrop == null ? "ORIGINAL" : "RECORTE");
                view.performClick();
                return true;
            }
            return true;
        });
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
                setProgressNow(progressValue + 1, "Real-ESRGAN · reconstruyendo detalle…");
                handler.postDelayed(this, 650);
            }
        }, 650);
    }

    private void startEstimatedGenerativeProgress(int operation) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isCurrent(operation) || !processing || progressValue >= 91) return;
                int increment = progressValue < 65 ? 2 : 1;
                setProgressNow(
                        progressValue + increment,
                        progressValue < 60
                                ? "Nano Banana · interpretando la fotografía…"
                                : "Nano Banana · reconstruyendo detalle y resolución…");
                handler.postDelayed(this, 900);
            }
        }, 700);
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
        localModeButton.setEnabled(!processing);
        generativeModeButton.setEnabled(!processing);
        updateGoogleAccountUi();
        style(cropButton, cropView.isCropMode());
        style(enhanceButton, true);
        style(uploadButton, false);
        style(newProjectButton, false);
        style(localModeButton, !generativeMode);
        style(generativeModeButton, generativeMode);
    }

    private void newProject() {
        dismissResult();
        cancelWork();
        cropView.setBitmap(null);
        scroll.setScrollingEnabled(true);
        recycleProjectBitmaps();
        acceptedSelection = null;
        savedResultUri = null;
        resultWasGenerative = false;
        viewerBadge.setText("SIN FOTO");
        uploadButton.setText("Subir foto");
        cropButton.setText("Recortar");
        progressBar.setVisibility(View.GONE);
        percentText.setText("");
        status.setText(generativeMode && firebaseAuth.getCurrentUser() == null
                ? "Identifícate con Google y sube una foto"
                : "Sube una foto para empezar");
        dimensions.setText(generativeMode
                ? "Procesamiento en Gemini API · salida PNG"
                : "Procesado privado · sin conexión · salida PNG");
        refreshActions();
    }

    private void cancelWork() {
        generation.incrementAndGet();
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) task.cancel(true);
        NativeRealEsrgan.cancelActive();
        GeminiImageRestorer.cancelActive();
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
        cancelWork();
        executor.shutdownNow();
        cropView.setBitmap(null);
        recycleProjectBitmaps();
        super.onDestroy();
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
