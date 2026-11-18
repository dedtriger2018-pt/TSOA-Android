package com.winlator.tsoa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.renderer.SceneProfile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Private, bounded scene data. A validated replacement becomes visible in one rename. */
final class TsoaSceneStore {
    static final String REVISION_KEY = "scene_profile_revision";
    private static final Executor READER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Listener { void loaded(SceneProfile profile, boolean damaged); }

    static void read(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        READER.execute(() -> {
            SceneProfile profile = null;
            boolean damaged = false;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file(app).toPath()))) {
                profile = SceneProfile.read(in);
            } catch (NoSuchFileException ignored) {
                // Fresh install or the player removed the profile.
            } catch (IOException | SecurityException error) {
                damaged = true;
                Log.w("TSOA_FE_DIAG", "Scene profile unavailable", error);
            }
            final SceneProfile result = profile;
            final boolean failed = damaged;
            MAIN.post(() -> listener.loaded(result, failed));
        });
    }

    // All mutations, including the change notification, are ordered across launcher instances.
    static synchronized void install(Context app, InputStream source) throws IOException {
        File target = file(app);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0 || count > SceneProfile.MAX_FILE_BYTES - total)
                        throw new IOException("Invalid scene profile length");
                    out.write(buffer, 0, count);
                    total += count;
                }
                out.getFD().sync();
            }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(temporary.toPath()))) {
                SceneProfile.read(in);
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            changed(app);
        } finally {
            if (temporary.exists() && !temporary.delete())
                Log.w("TSOA_FE_DIAG", "Could not remove staged scene profile");
        }
    }

    static synchronized void remove(Context app) throws IOException {
        File target = file(app);
        Files.deleteIfExists(new File(target.getParentFile(), target.getName() + ".tmp").toPath());
        Files.deleteIfExists(target.toPath());
        changed(app);
    }

    private static void changed(Context app) {
        android.content.SharedPreferences prefs = TsoaOptions.prefs(app);
        prefs.edit().putLong(REVISION_KEY, prefs.getLong(REVISION_KEY, 0) + 1).apply();
    }

    private static File file(Context app) {
        return new File(app.getNoBackupFilesDir(), "active.tscene");
    }

    private TsoaSceneStore() {}
}
