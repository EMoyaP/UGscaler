package com.mejorarfotos.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps user-initiated downloads and local AI inference alive while the UI is suspended. */
public final class BackgroundTaskService extends Service {
    static final String JOB_ENHANCE = "enhance";
    static final String JOB_GENERATE = "generate";
    static final String JOB_BSRGAN_DOWNLOAD = "download-bsrgan";
    static final String JOB_DIFFUSION_DOWNLOAD = "download-diffusion";

    private static final String ACTION_BEGIN = "ugscaler.background.BEGIN";
    private static final String ACTION_UPDATE = "ugscaler.background.UPDATE";
    private static final String ACTION_FINISH = "ugscaler.background.FINISH";
    private static final String ACTION_CANCEL = "ugscaler.background.CANCEL";
    private static final String EXTRA_ID = "job_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_PROGRESS = "progress";
    private static final String EXTRA_DOWNLOAD = "download";
    private static final String EXTRA_SUCCESS = "success";

    private static final String CHANNEL_PROGRESS = "ugscaler_processing";
    private static final String CHANNEL_RESULTS = "ugscaler_results";
    private static final int ACTIVE_NOTIFICATION_ID = 4600;

    private static volatile BackgroundTaskService running;
    private static volatile boolean appVisible;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JobState> jobs = new LinkedHashMap<>();
    private PowerManager.WakeLock wakeLock;

    static void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    static void begin(Context context, String id, String title, String text, boolean download) {
        Intent intent = command(context, ACTION_BEGIN, id, title, text, 0);
        intent.putExtra(EXTRA_DOWNLOAD, download);
        ContextCompat.startForegroundService(context.getApplicationContext(), intent);
    }

    static void update(Context context, String id, int progress, String text) {
        BackgroundTaskService service = running;
        if (service != null) {
            service.main.post(() -> service.updateJob(id, progress, text));
            return;
        }
        dispatch(context, command(context, ACTION_UPDATE, id, null, text, progress));
    }

    static void finish(Context context, String id, String title, String text, boolean success) {
        Intent intent = command(context, ACTION_FINISH, id, title, text, 100);
        intent.putExtra(EXTRA_SUCCESS, success);
        BackgroundTaskService service = running;
        if (service != null) {
            service.main.post(() -> service.finishJob(id, title, text, success));
        } else {
            dispatch(context, intent);
        }
    }

    static void cancel(Context context, String id) {
        BackgroundTaskService service = running;
        if (service != null) {
            service.main.post(() -> service.cancelJob(id));
        } else {
            dispatch(context, command(context, ACTION_CANCEL, id, null, null, 0));
        }
    }

    private static Intent command(Context context, String action, String id,
                                  String title, String text, int progress) {
        Intent intent = new Intent(context, BackgroundTaskService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_TEXT, text);
        intent.putExtra(EXTRA_PROGRESS, progress);
        return intent;
    }

    private static void dispatch(Context context, Intent intent) {
        try {
            context.getApplicationContext().startService(intent);
        } catch (RuntimeException ignored) {
            // If there is no active foreground service, there is no background job
            // left to represent. A user action will start a fresh service later.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        createChannels();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "UGscaler:BackgroundAI");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String id = intent.getStringExtra(EXTRA_ID);
        if (id == null || id.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_BEGIN.equals(action)) {
            beginJob(id,
                    value(intent.getStringExtra(EXTRA_TITLE), "UGscaler"),
                    value(intent.getStringExtra(EXTRA_TEXT), "Preparando…"),
                    intent.getBooleanExtra(EXTRA_DOWNLOAD, false));
        } else if (ACTION_UPDATE.equals(action)) {
            updateJob(id, intent.getIntExtra(EXTRA_PROGRESS, 0),
                    value(intent.getStringExtra(EXTRA_TEXT), "Procesando…"));
        } else if (ACTION_FINISH.equals(action)) {
            finishJob(id,
                    value(intent.getStringExtra(EXTRA_TITLE), "Trabajo finalizado"),
                    value(intent.getStringExtra(EXTRA_TEXT), "UGscaler ha terminado"),
                    intent.getBooleanExtra(EXTRA_SUCCESS, true));
        } else if (ACTION_CANCEL.equals(action)) {
            cancelJob(id);
        }
        return START_NOT_STICKY;
    }

    private void beginJob(String id, String title, String text, boolean download) {
        jobs.put(id, new JobState(id, title, text, 0, download));
        showForeground();
    }

    private void updateJob(String id, int progress, String text) {
        JobState state = jobs.get(id);
        if (state == null) return;
        state.progress = Math.max(state.progress, Math.min(100, progress));
        state.text = value(text, state.text);
        showForeground();
    }

    private void finishJob(String id, String title, String text, boolean success) {
        jobs.remove(id);
        if (!appVisible) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(resultNotificationId(id), resultNotification(title, text, success));
            }
        }
        refreshOrStop();
    }

    private void cancelJob(String id) {
        jobs.remove(id);
        refreshOrStop();
    }

    private void refreshOrStop() {
        if (!jobs.isEmpty()) {
            showForeground();
            return;
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        releaseWakeLock();
        stopSelf();
    }

    private void showForeground() {
        if (jobs.isEmpty()) return;
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        }
        ServiceCompat.startForeground(
                this,
                ACTIVE_NOTIFICATION_ID,
                progressNotification(),
                Build.VERSION.SDK_INT >= 29 ? foregroundTypes() : 0);
    }

    @SuppressLint("InlinedApi")
    private int foregroundTypes() {
        int types = 0;
        for (JobState state : jobs.values()) {
            if (state.download) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            } else if (Build.VERSION.SDK_INT >= 34) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
        }
        return types;
    }

    private Notification progressNotification() {
        JobState latest = null;
        int total = 0;
        for (JobState state : jobs.values()) {
            latest = state;
            total += state.progress;
        }
        int average = Math.max(0, Math.min(100, total / Math.max(1, jobs.size())));
        String title = jobs.size() == 1 && latest != null
                ? latest.title : jobs.size() + " tareas activas";
        String text = latest == null ? "Trabajando en segundo plano" : latest.text;
        return new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, average, false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private Notification resultNotification(String title, String text, boolean success) {
        return new NotificationCompat.Builder(this, CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setPriority(success
                        ? NotificationCompat.PRIORITY_DEFAULT
                        : NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS, "Procesamiento en segundo plano",
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("Progreso de mejoras, generación y descargas locales");
        progress.setSound(null, null);
        manager.createNotificationChannel(progress);
        NotificationChannel results = new NotificationChannel(
                CHANNEL_RESULTS, "Resultados de UGscaler",
                NotificationManager.IMPORTANCE_DEFAULT);
        results.setDescription("Avisos cuando una tarea finaliza con la aplicación suspendida");
        manager.createNotificationChannel(results);
    }

    private static int resultNotificationId(String id) {
        return 4700 + Math.abs(id.hashCode() % 200);
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        jobs.clear();
        releaseWakeLock();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class JobState {
        final String id;
        final String title;
        final boolean download;
        String text;
        int progress;

        JobState(String id, String title, String text, int progress, boolean download) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.progress = progress;
            this.download = download;
        }
    }
}
