package com.winlator.tsoa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.winlator.R;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Read-only synchronization at the guest session boundaries. */
public final class TsoaHybridSync {
    private static final String CHANNEL = "tsoa_chronos";
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    private TsoaHybridSync() {}

    public static String startFingerprint(Context context) {
        TsoaOptions options = new TsoaOptions(context);
        if (options.isOriginalMode() || options.getSyncMode() == 0) return null;
        try { return ChronosStore.fingerprint(saves(context)); }
        catch (IOException | RuntimeException error) {
            Log.w("TSOA_FE_DIAG", "Chronos: could not read initial save state", error);
            return null;
        }
    }

    public static void finishSession(Context context, String initial) {
        TsoaOptions options = new TsoaOptions(context);
        if (initial == null || options.isOriginalMode() || options.getSyncMode() == 0
                || !TsoaSaveAccess.finishGameForSync()) {
            TsoaSaveAccess.endGame();
            return;
        }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                ChronosStore.Point point = store(app).createAutomatic(saves(app), initial);
                if (point != null && new TsoaOptions(app).isCheckpointNotificationEnabled()) notify(app);
            } catch (IOException | RuntimeException error) {
                Log.w("TSOA_FE_DIAG", "Chronos automatic checkpoint skipped", error);
            } finally {
                TsoaSaveAccess.endMaintenance();
            }
        });
    }

    static ChronosStore store(Context context) { return new ChronosStore(new File(context.getNoBackupFilesDir(), "chronos")); }

    private static File saves(Context context) { return new File(context.getFilesDir(), TsoaMaintenance.SAVE_PATH); }

    private static void notify(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                TsoaMenuLanguage.get(context, R.string.tsoa_chronos_terminal), NotificationManager.IMPORTANCE_LOW));
        if (!manager.areNotificationsEnabled()) return;
        manager.notify(1604, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(TsoaMenuLanguage.get(context, R.string.tsoa_sync_checkpoint))
                .setContentText(TsoaMenuLanguage.get(context, R.string.tsoa_sync_record_detail))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build());
    }
}
