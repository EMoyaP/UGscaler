package com.mejorarfotos.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Mobile model-download screen with explicit install and update states. */
final class ModelManagerView extends LinearLayout implements ModelRepository.Listener {
    private static final int INK = Color.rgb(241, 246, 241);
    private static final int MUTED = Color.rgb(161, 176, 165);
    private static final int PANEL = Color.rgb(24, 31, 28);
    private static final int ACCENT = Color.rgb(214, 243, 106);
    private static final int CYAN = Color.rgb(42, 207, 231);

    private final ModelRepository repository;
    private final GenerativeModelCard generativeCard;
    private final TextView version;
    private final TextView checkStatus;
    private final Button action;
    private final Button restore;
    private final ProgressBar progress;
    private ModelSpec model;
    private boolean busy;

    ModelManagerView(Context context) {
        super(context);
        repository = new ModelRepository(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, dp(24));

        TextView title = text("Modelos locales", 24, INK, true);
        addView(title);
        TextView intro = text(
                "La app comprueba si hay una versión nueva al abrir esta sección. "
                        + "La descarga se realiza una sola vez; las fotos siempre se procesan en el teléfono.",
                13, MUTED, false);
        intro.setPadding(0, dp(6), 0, dp(14));
        addView(intro);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(round(PANEL, 18));
        TextView name = text("Mejora general", 18, INK, true);
        card.addView(name);
        TextView description = text(
                "Recupera bordes y texturas y amplía la imagen. Adecuado para fotos, objetos y paisajes.",
                12, MUTED, false);
        description.setPadding(0, dp(5), 0, dp(9));
        card.addView(description);
        version = text("Preparando información…", 12, CYAN, true);
        card.addView(version);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(53, 65, 57)));
        progress.setVisibility(GONE);
        LayoutParams progressParams = new LayoutParams(-1, dp(7));
        progressParams.topMargin = dp(12);
        card.addView(progress, progressParams);

        action = button("Comprobando…", true);
        action.setEnabled(false);
        action.setOnClickListener(v -> install());
        LayoutParams actionParams = new LayoutParams(-1, dp(50));
        actionParams.topMargin = dp(13);
        card.addView(action, actionParams);

        restore = button("Restaurar versión incluida", false);
        restore.setVisibility(GONE);
        restore.setOnClickListener(v -> {
            repository.restoreBundled();
            refreshState();
            Toast.makeText(getContext(), "Se ha restaurado el modelo incluido", Toast.LENGTH_SHORT).show();
        });
        LayoutParams restoreParams = new LayoutParams(-1, dp(46));
        restoreParams.topMargin = dp(8);
        card.addView(restore, restoreParams);
        addView(card, new LayoutParams(-1, -2));

        generativeCard = new GenerativeModelCard(context);
        LayoutParams generativeParams = new LayoutParams(-1, -2);
        generativeParams.topMargin = dp(12);
        addView(generativeCard, generativeParams);

        checkStatus = text("", 11, MUTED, false);
        checkStatus.setGravity(Gravity.CENTER);
        checkStatus.setPadding(dp(10), dp(12), dp(10), 0);
        addView(checkStatus);
    }

    void openAndCheck() {
        if (busy) return;
        checkStatus.setText("Buscando actualizaciones…");
        repository.checkForUpdates(this);
        generativeCard.openAndCheck();
    }

    void close() {
        repository.close();
        generativeCard.close();
    }

    @Override public void onCatalog(ModelSpec value, boolean online) {
        model = value;
        refreshState();
        checkStatus.setText(online
                ? "Catálogo actualizado"
                : "Información local disponible · comprobando novedades…");
    }

    @Override public void onProgress(int percent) {
        busy = true;
        progress.setVisibility(VISIBLE);
        progress.setProgress(percent, true);
        action.setEnabled(false);
        action.setText("Descargando " + percent + " %");
    }

    @Override public void onInstalled(ModelSpec installed) {
        busy = false;
        progress.setProgress(100, true);
        progress.setVisibility(GONE);
        model = installed;
        refreshState();
        checkStatus.setText("Modelo verificado y listo para usar");
        Toast.makeText(getContext(), "Modelo actualizado", Toast.LENGTH_SHORT).show();
    }

    @Override public void onError(String message) {
        busy = false;
        progress.setVisibility(GONE);
        refreshState();
        checkStatus.setText(message);
    }

    private void install() {
        if (model == null || busy || !repository.hasUpdate(model)) return;
        busy = true;
        action.setEnabled(false);
        progress.setProgress(0);
        progress.setVisibility(VISIBLE);
        checkStatus.setText("La descarga se verificará antes de instalarse");
        repository.install(model, this);
    }

    private void refreshState() {
        String installed = repository.installedVersion();
        boolean update = model != null && repository.hasUpdate(model);
        String available = model == null ? installed : model.version;
        long bytes = model == null ? 0L : model.bytes;
        version.setText("Instalada " + installed + " · Disponible " + available
                + (bytes > 0 ? " · " + formatBytes(bytes) : ""));
        if (busy) return;
        action.setText(update ? "Actualizar" : "Instalado");
        action.setEnabled(update);
        action.setAlpha(update ? 1f : .66f);
        restore.setVisibility(repository.isDownloaded() ? VISIBLE : GONE);
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
        view.setTextColor(primary ? Color.rgb(12, 17, 14) : INK);
        view.setBackground(round(primary ? ACCENT : Color.rgb(31, 40, 35), 15));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
