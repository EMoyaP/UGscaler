package com.mejorarfotos.app;

import android.annotation.SuppressLint;
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

/** Optional, resumable Stable Diffusion model card shown in Downloads. */
@SuppressLint({"SetTextI18n", "ViewConstructor"})
final class GenerativeModelCard extends LinearLayout
        implements GenerativeModelRepository.Listener {
    private static final int INK = Color.rgb(241, 246, 241);
    private static final int MUTED = Color.rgb(161, 176, 165);
    private static final int ACCENT = Color.rgb(214, 243, 106);
    private static final int CYAN = Color.rgb(42, 207, 231);

    private final GenerativeModelRepository repository;
    private final TextView version;
    private final TextView status;
    private final Button action;
    private final Button remove;
    private final ProgressBar progress;
    private GenerativeModelSpec model;
    private boolean busy;

    GenerativeModelCard(Context context) {
        super(context);
        repository = new GenerativeModelRepository(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(15), dp(16), dp(15));
        setBackground(round(Color.rgb(24, 31, 28), 18));

        TextView name = text("Creación generativa", 18, INK, true);
        addView(name);
        TextView description = text(
                "Crea imágenes desde texto o reinterpreta una foto con Stable Diffusion local. "
                        + "Sin cuotas ni pagos por imagen.",
                12,
                MUTED,
                false);
        description.setPadding(0, dp(5), 0, dp(8));
        addView(description);
        version = text("DreamShaper 7 LCM · descarga opcional de 1,51 GB", 12, CYAN, true);
        addView(version);
        TextView license = text(
                "CreativeML OpenRAIL-M · uso sujeto a las condiciones del modelo",
                10,
                MUTED,
                false);
        license.setPadding(0, dp(4), 0, 0);
        addView(license);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(53, 65, 57)));
        progress.setVisibility(GONE);
        LayoutParams progressParams = new LayoutParams(-1, dp(7));
        progressParams.topMargin = dp(12);
        addView(progress, progressParams);

        status = text("", 11, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(7), 0, 0);
        addView(status);

        action = button("Comprobando…", true);
        action.setEnabled(false);
        action.setOnClickListener(v -> {
            if (busy) {
                repository.cancelDownload();
                BackgroundTaskService.cancel(
                        getContext(), BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD);
            } else if (model != null) {
                busy = true;
                refreshState();
                if (getContext() instanceof MainActivity) {
                    ((MainActivity) getContext()).requestBackgroundNotificationPermission();
                }
                BackgroundTaskService.begin(
                        getContext(),
                        BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD,
                        "Descargando Stable Diffusion",
                        "Preparando la descarga de 1,51 GB…",
                        true);
                repository.download(model, this);
            }
        });
        LayoutParams actionParams = new LayoutParams(-1, dp(50));
        actionParams.topMargin = dp(10);
        addView(action, actionParams);

        remove = button("Eliminar modelo", false);
        remove.setOnClickListener(v -> {
            repository.remove();
            refreshState();
            Toast.makeText(getContext(), "Modelo eliminado", Toast.LENGTH_SHORT).show();
        });
        LayoutParams removeParams = new LayoutParams(-1, dp(46));
        removeParams.topMargin = dp(8);
        addView(remove, removeParams);
        refreshState();
    }

    void openAndCheck() {
        if (!busy) {
            status.setText("Buscando actualizaciones…");
            repository.checkForUpdates(this);
        }
    }

    void close() {
        if (busy) {
            BackgroundTaskService.cancel(
                    getContext(), BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD);
        }
        repository.close();
    }

    @Override public void onCatalog(GenerativeModelSpec value, boolean online) {
        model = value;
        refreshState();
        status.setText(online ? "Catálogo actualizado" : "Comprobando novedades…");
    }

    @Override public void onProgress(int percent, String phase) {
        busy = true;
        progress.setVisibility(VISIBLE);
        progress.setProgress(percent, true);
        status.setText(phase);
        action.setText("Cancelar · " + percent + " %");
        action.setEnabled(true);
        BackgroundTaskService.update(
                getContext(),
                BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD,
                percent,
                phase);
    }

    @Override public void onInstalled(GenerativeModelSpec value) {
        busy = false;
        model = value;
        progress.setVisibility(GONE);
        status.setText("Modelo verificado y listo para usar");
        refreshState();
        BackgroundTaskService.finish(
                getContext(),
                BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD,
                "Stable Diffusion está listo",
                "El modelo generativo se ha descargado y verificado correctamente.",
                true);
        Toast.makeText(getContext(), "Stable Diffusion está listo", Toast.LENGTH_LONG).show();
    }

    @Override public void onError(String message) {
        busy = false;
        progress.setVisibility(GONE);
        status.setText(message);
        refreshState();
        BackgroundTaskService.finish(
                getContext(),
                BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD,
                "Descarga incompleta",
                message,
                false);
    }

    @Override public void onCancelled() {
        busy = false;
        progress.setVisibility(GONE);
        status.setText("Descarga pausada · continuará desde el mismo punto");
        refreshState();
        BackgroundTaskService.cancel(
                getContext(), BackgroundTaskService.JOB_DIFFUSION_DOWNLOAD);
    }

    private void refreshState() {
        boolean installed = repository.isInstalled();
        boolean update = installed && model != null && repository.hasUpdate(model);
        long partial = repository.partialBytes();
        if (installed) {
            version.setText("DreamShaper 7 LCM · instalada " + repository.installedVersion());
        } else if (partial > 0L) {
            version.setText("Descarga parcial · "
                    + GenerativeModelRepository.formatBytes(partial) + " de 1,51 GB");
        } else {
            version.setText("DreamShaper 7 LCM · descarga opcional de 1,51 GB");
        }
        remove.setVisibility(installed || partial > 0L ? VISIBLE : GONE);
        if (busy) {
            action.setText("Cancelar");
            action.setEnabled(true);
        } else if (update) {
            action.setText("Actualizar");
            action.setEnabled(true);
        } else if (installed) {
            action.setText("Instalado");
            action.setEnabled(false);
        } else {
            action.setText(partial > 0L ? "Continuar descarga" : "Descargar");
            action.setEnabled(model != null);
        }
        action.setAlpha(action.isEnabled() ? 1f : .66f);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
