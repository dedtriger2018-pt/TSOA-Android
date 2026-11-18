package com.winlator.tsoa;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.ImageView;
import android.widget.Toast;

import com.winlator.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Read-only bridge to the guest's visible output. A player classifies each saved frame. */
public final class TsoaJournal {
    private static final int[] CATEGORIES = {R.string.tsoa_pda_messages, R.string.tsoa_pda_documents,
            R.string.tsoa_pda_news, R.string.tsoa_pda_dialogue, R.string.tsoa_pda_choices, R.string.tsoa_pda_missions};
    private static final String ARCHIVE_PATTERN = "[0-5]-[0-9]{13}-(root|[0-9]{13}-[0-9a-f]{8})\\.jpg";
    private static final Executor DISK = Executors.newSingleThreadExecutor();
    private static final long MAX_STORAGE = 128L * 1024 * 1024;

    private TsoaJournal() {}

    public static void open(Activity activity, SurfaceView game) {
        if (!available(activity)) return;
        if (game == null) { browse(activity); return; }
        String[] choices = {s(activity, R.string.tsoa_pda_browse), s(activity, R.string.tsoa_pda_capture)};
        new AlertDialog.Builder(activity).setTitle(s(activity, R.string.tsoa_pda))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) browse(activity);
                    else capture(activity, game);
                }).setNegativeButton(s(activity, R.string.tsoa_cancel), null).show();
    }

    private static void capture(Activity activity, SurfaceView game) {
        int width = game.getWidth(), height = game.getHeight();
        if (width < 2 || height < 2 || !game.getHolder().getSurface().isValid()) { failed(activity); return; }
        float scale = Math.min(1f, 1920f / Math.max(width, height));
        Bitmap frame = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(game, frame, result -> {
                if (result != PixelCopy.SUCCESS || !available(activity)) {
                    frame.recycle();
                    if (available(activity)) failed(activity);
                    return;
                }
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(s(activity, R.string.tsoa_pda_category)).setItems(categories(activity), (prompt, category) ->
                                save(activity, frame.copy(Bitmap.Config.ARGB_8888, false), category))
                        .setNegativeButton(s(activity, R.string.tsoa_cancel), null).create();
                dialog.setOnDismissListener(prompt -> frame.recycle());
                dialog.show();
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException failure) {
            frame.recycle();
            failed(activity);
        }
    }

    private static void save(Activity activity, Bitmap frame, int category) {
        if (frame == null) { failed(activity); return; }
        File directory = folder(activity);
        String branch = TsoaOptions.prefs(activity).getString("chronos_head", "root");
        String parent = branch != null && branch.matches("[0-9]{13}-[0-9a-f]{8}") ? branch : "root";
        DISK.execute(() -> {
            File temp = null;
            boolean saved = false;
            try {
                if ((!directory.isDirectory() && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) throw new IOException();
                File[] records = records(directory);
                if (records.length >= 96) throw new IOException();
                long free = MAX_STORAGE;
                for (File record : records) free -= record.length();
                if (free <= 0) throw new IOException();
                temp = File.createTempFile(".capture-", ".tmp", directory);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw new IOException();
                    output.getFD().sync();
                }
                if (temp.length() > free) throw new IOException();
                String name = String.format(Locale.ROOT, "%d-%013d-%s.jpg", category, System.currentTimeMillis(), parent);
                Files.move(temp.toPath(), new File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE);
                saved = true;
            } catch (IOException problem) {
                android.util.Log.w("TSOA_FE_DIAG", "PDA capture unavailable", problem);
            } finally {
                if (temp != null) temp.delete();
                frame.recycle();
            }
            final boolean stored = saved;
            activity.runOnUiThread(() -> {
                if (available(activity)) Toast.makeText(activity, s(activity,
                        stored ? R.string.tsoa_pda_saved : R.string.tsoa_pda_failed), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static void browse(Activity activity) {
        new AlertDialog.Builder(activity).setTitle(s(activity, R.string.tsoa_pda))
                .setItems(categories(activity), (dialog, category) -> showList(activity, category))
                .setNegativeButton(s(activity, R.string.tsoa_cancel), null).show();
    }

    private static void showList(Activity activity, int category) {
        File[] files = Arrays.stream(records(folder(activity))).filter(item ->
                item.getName().charAt(0) == '0' + category).toArray(File[]::new);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(s(activity, CATEGORIES[category])).setNegativeButton(s(activity, R.string.tsoa_close), null);
        if (files.length == 0) dialog.setMessage(s(activity, R.string.tsoa_pda_empty));
        else {
            String[] labels = new String[files.length];
            for (int i = 0; i < labels.length; i++) {
                String name = files[i].getName();
                labels[i] = DateFormat.getDateTimeInstance().format(new Date(Long.parseLong(name.substring(2, 15))));
                if (!name.endsWith("-root.jpg"))
                    labels[i] += "\n" + s(activity, R.string.tsoa_chronos_point,
                            DateFormat.getDateTimeInstance().format(new Date(Long.parseLong(name.substring(16, 29)))));
            }
            dialog.setItems(labels, (list, index) -> showFrame(activity, category, files[index]));
        }
        dialog.show();
    }

    private static void showFrame(Activity activity, int category, File file) {
        DISK.execute(() -> {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            activity.runOnUiThread(() -> {
                if (!available(activity)) { if (bitmap != null) bitmap.recycle(); return; }
                if (bitmap == null) { failed(activity); return; }
                ImageView viewer = new ImageView(activity);
                viewer.setAdjustViewBounds(true);
                viewer.setScaleType(ImageView.ScaleType.FIT_CENTER);
                viewer.setImageBitmap(bitmap);
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(s(activity, CATEGORIES[category])).setView(viewer).setPositiveButton(s(activity, R.string.tsoa_close), null)
                        .setNeutralButton(s(activity, R.string.tsoa_chronos_delete), (d, w) ->
                                new AlertDialog.Builder(activity)
                                        .setMessage(s(activity, R.string.tsoa_pda_delete_confirm))
                                        .setPositiveButton(s(activity, R.string.tsoa_chronos_delete), (confirm, button) -> {
                                            if (!file.delete()) failed(activity);
                                            else showList(activity, category);
                                        }).setNegativeButton(s(activity, R.string.tsoa_cancel), null).show()).create();
                dialog.setOnDismissListener(d -> {
                    viewer.setImageDrawable(null);
                    bitmap.recycle();
                });
                dialog.show();
            });
        });
    }

    private static File folder(Activity activity) { return new File(activity.getNoBackupFilesDir(), "pda"); }
    private static File[] records(File directory) {
        File[] entries = directory.listFiles(file -> file.getName().matches(ARCHIVE_PATTERN)
                && file.isFile() && !Files.isSymbolicLink(file.toPath()));
        if (entries == null) return new File[0];
        Arrays.sort(entries, (a, b) -> b.getName().substring(2).compareTo(a.getName().substring(2)));
        return entries;
    }
    private static String[] categories(Activity activity) {
        String[] labels = new String[CATEGORIES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = s(activity, CATEGORIES[i]);
        return labels;
    }
    private static String s(Activity activity, int res, Object... args) { return TsoaMenuLanguage.get(activity, res, args); }
    private static boolean available(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed() && !new TsoaOptions(activity).isOriginalMode();
    }
    private static void failed(Activity activity) {
        Toast.makeText(activity, s(activity, R.string.tsoa_pda_failed), Toast.LENGTH_SHORT).show();
    }
}
