package com.mejorarfotos.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local text-to-image and image-to-image workspace. */
final class GenerativeView extends LinearLayout {
    interface Host {
        Bitmap sourceForGenerative();
        void showDownloads();
        void deliverGenerativeResult(Bitmap result, Bitmap before);
    }

    private static final int INK = Color.rgb(241, 246, 241);
    private static final int MUTED = Color.rgb(161, 176, 165);
    private static final int PANEL = Color.rgb(24, 31, 28);
    private static final int ACCENT = Color.rgb(214, 243, 106);
    private static final int CYAN = Color.rgb(42, 207, 231);

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Button createMode;
    private final Button improveMode;
    private final EditText prompt;
    private final EditText negativePrompt;
    private final LinearLayout strengthBox;
    private final SeekBar strength;
    private final TextView strengthValue;
    private final Button generate;
    private final ProgressBar progress;
    private final TextView status;
    private boolean improve;
    private boolean busy;

    GenerativeView(Context context, Host host) {
        super(context);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, dp(24));

        addView(text("Crear con IA local", 24, INK, true));
        TextView intro = text(
                "Genera una imagen desde una descripción o utiliza tu foto como referencia. "
                        + "No hay cuotas: el trabajo se realiza en el dispositivo.",
                13, MUTED, false);
        intro.setPadding(0, dp(6), 0, dp(14));
        addView(intro);

        LinearLayout modes = new LinearLayout(context);
        createMode = button("Desde texto", true);
        improveMode = button("Mejorar foto", false);
        modes.addView(createMode, weight(1f, 0, 6));
        modes.addView(improveMode, weight(1f, 0, 0));
        addView(modes, new LayoutParams(-1, dp(48)));
        createMode.setOnClickListener(v -> setMode(false));
        improveMode.setOnClickListener(v -> setMode(true));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(15));
        card.setBackground(round(PANEL, 18));
        LayoutParams cardParams = new LayoutParams(-1, -2);
        cardParams.topMargin = dp(12);
        addView(card, cardParams);

        card.addView(text("Descripción", 12, INK, true));
        prompt = edit("Describe la imagen que quieres crear", 4);
        LayoutParams promptParams = new LayoutParams(-1, dp(116));
        promptParams.topMargin = dp(6);
        card.addView(prompt, promptParams);

        TextView avoid = text("Evitar (opcional)", 12, INK, true);
        avoid.setPadding(0, dp(12), 0, 0);
        card.addView(avoid);
        negativePrompt = edit("Elementos que no quieres en el resultado", 2);
        LayoutParams negativeParams = new LayoutParams(-1, dp(76));
        negativeParams.topMargin = dp(6);
        card.addView(negativePrompt, negativeParams);

        strengthBox = new LinearLayout(context);
        strengthBox.setOrientation(VERTICAL);
        TextView strengthTitle = text("Intensidad del cambio", 12, INK, true);
        strengthValue = text("25 % · conserva la fotografía", 11, CYAN, true);
        strengthBox.addView(strengthTitle);
        strengthBox.addView(strengthValue);
        strength = new SeekBar(context);
        strength.setMax(100);
        strength.setProgress(25);
        strength.setProgressTintList(ColorStateList.valueOf(ACCENT));
        strength.setThumbTintList(ColorStateList.valueOf(ACCENT));
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                int shown = Math.max(15, Math.min(60, value));
                if (shown != value) seekBar.setProgress(shown);
                strengthValue.setText(shown + "% · " + (shown <= 30
                        ? "conserva la fotografía" : "reconstrucción más creativa"));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        strengthBox.addView(strength, new LayoutParams(-1, dp(42)));
        LayoutParams strengthParams = new LayoutParams(-1, -2);
        strengthParams.topMargin = dp(12);
        card.addView(strengthBox, strengthParams);

        TextView settings = text(
                "Calidad 512 × 512 · 6 pasos rápidos · resultado PNG",
                11, MUTED, false);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(0, dp(5), 0, dp(8));
        card.addView(settings);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(53, 65, 57)));
        progress.setVisibility(GONE);
        card.addView(progress, new LayoutParams(-1, dp(7)));

        status = text("El primer uso puede tardar varios minutos", 11, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        card.addView(status);

        generate = button("Generar imagen", true);
        generate.setOnClickListener(v -> generate());
        card.addView(generate, new LayoutParams(-1, dp(52)));
        setMode(false);
    }

    void close() {
        worker.shutdownNow();
    }

    boolean isBusy() { return busy; }

    private void setMode(boolean improvePhoto) {
        if (busy) return;
        improve = improvePhoto;
        style(createMode, !improve);
        style(improveMode, improve);
        strengthBox.setVisibility(improve ? VISIBLE : GONE);
        generate.setText(improve ? "Mejorar con Stable Diffusion" : "Generar imagen");
        prompt.setHint(improve
                ? "Ej.: fotografía nítida, detalle natural, conservar composición"
                : "Describe la imagen que quieres crear");
    }

    private void generate() {
        if (busy) return;
        if (!GenerativeModelRepository.modelFile(getContext()).isFile()) {
            Toast.makeText(getContext(), "Descarga primero el modelo generativo",
                    Toast.LENGTH_LONG).show();
            host.showDownloads();
            return;
        }
        String positive = prompt.getText().toString().trim();
        if (positive.isEmpty()) {
            prompt.setError("Escribe una descripción");
            prompt.requestFocus();
            return;
        }
        Bitmap before = improve ? host.sourceForGenerative() : null;
        if (improve && before == null) {
            Toast.makeText(getContext(), "Carga o recorta una fotografía en el editor",
                    Toast.LENGTH_LONG).show();
            return;
        }
        hideKeyboard();
        busy = true;
        setControlsEnabled(false);
        progress.setVisibility(VISIBLE);
        progress.setProgress(2);
        status.setText("Cargando el modelo local…");
        String negative = negativePrompt.getText().toString().trim();
        float imageStrength = Math.max(.15f, Math.min(.60f, strength.getProgress() / 100f));
        long seed = System.nanoTime() & Long.MAX_VALUE;
        worker.execute(() -> {
            try {
                Bitmap result = LocalDiffusionEngine.generate(
                        getContext(), positive, negative, before, 512,
                        imageStrength, seed,
                        (step, total) -> main.post(() -> {
                            int percent = 10 + Math.round(step * 85f / Math.max(1, total));
                            progress.setProgress(percent, true);
                            status.setText("Generando · paso " + step + " de " + total);
                        }));
                main.post(() -> {
                    busy = false;
                    setControlsEnabled(true);
                    progress.setVisibility(GONE);
                    status.setText("Imagen generada · preparando PNG");
                    host.deliverGenerativeResult(result, before);
                });
            } catch (Throwable error) {
                if (before != null && !before.isRecycled()) before.recycle();
                main.post(() -> {
                    busy = false;
                    setControlsEnabled(true);
                    progress.setVisibility(GONE);
                    status.setText("No se pudo completar la generación");
                    Toast.makeText(getContext(), error.getMessage() == null
                                    ? "Error del motor generativo" : error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        createMode.setEnabled(enabled);
        improveMode.setEnabled(enabled);
        prompt.setEnabled(enabled);
        negativePrompt.setEnabled(enabled);
        strength.setEnabled(enabled);
        generate.setEnabled(enabled);
        generate.setAlpha(enabled ? 1f : .66f);
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(prompt.getWindowToken(), 0);
    }

    private EditText edit(String hint, int lines) {
        EditText view = new EditText(getContext());
        view.setHint(hint);
        view.setHintTextColor(Color.rgb(115, 132, 120));
        view.setTextColor(INK);
        view.setTextSize(13);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMinLines(lines);
        view.setMaxLines(lines);
        view.setBackground(round(Color.rgb(16, 22, 19), 12));
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button view = new Button(getContext());
        view.setText(value);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        style(view, primary);
        return view;
    }

    private void style(Button view, boolean primary) {
        view.setTextColor(primary ? Color.rgb(12, 17, 14) : INK);
        view.setBackground(round(primary ? ACCENT : Color.rgb(31, 40, 35), 15));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LayoutParams weight(float weight, int left, int right) {
        LayoutParams params = new LayoutParams(0, -1, weight);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
