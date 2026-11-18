package com.winlator.tsoa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.core.GameImportManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** User-initiated maintenance through Android's document picker; no storage permission needed. */
public final class TsoaMaintenance {
    private static final int IMPORT_SCENE = 85;
    private static final int EXPORT_SAVES = 82;
    private static final int IMPORT_SAVES = 83;
    private static final int EXPORT_DIAGNOSTICS = 84;
    private static final String SAVE_PATH = "rootfs/home/xuser/.wine/drive_c/users/xuser/AppData/LocalLow/Caramel-Mocha Studio/TSOA_FE";
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile boolean closed;

    public TsoaMaintenance(Activity activity) {
        this.activity = activity;
    }

    public boolean isBusy() { return busy; }

    public void exportSaves() {
        if (busy) return;
        createDocument(EXPORT_SAVES, "tsoa-saves-" + timestamp() + ".zip");
    }

    public void importSaves() {
        if (busy) return;
        showDialog(s(R.string.tsoa_maintenance_restore_title), s(R.string.tsoa_maintenance_restore_message),
            s(R.string.tsoa_maintenance_choose_backup), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                startPicker(intent, IMPORT_SAVES);
            });
    }

    public void exportDiagnostics() {
        if (busy) return;
        createDocument(EXPORT_DIAGNOSTICS, "tsoa-diagnostico-" + timestamp() + ".zip");
    }

    public void importScene() {
        if (busy || closed) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startPicker(intent, IMPORT_SCENE);
    }

    public void removeScene() {
        if (busy || closed) return;
        showDialog(s(R.string.tsoa_scene_remove), s(R.string.tsoa_scene_remove_message),
            s(R.string.tsoa_scene_remove), () -> execute(s(R.string.tsoa_scene_remove), () -> {
                TsoaSceneStore.remove(activity);
                return s(R.string.tsoa_scene_removed);
            }));
    }

    public void verifyFiles() {
        execute(s(R.string.tsoa_maintenance_verifying), () -> {
            File game = GameImportManager.getGameDirectory(activity);
            String[] files = {"TSOA.exe", "UnityPlayer.dll", "GameAssembly.dll", "baselib.dll", "TSOA_Data/boot.config",
                "TSOA_Data/resources.assets", "TSOA_Data/resources.assets.resS"};
            String[] directories = {"TSOA_Data/il2cpp_data", "TSOA_Data/Plugins", "TSOA_Data/StreamingAssets"};
            StringBuilder missing = new StringBuilder();
            for (String path : files) {
                File file = new File(game, path);
                if (!file.isFile() || file.length() == 0) missing.append("• ").append(path).append('\n');
            }
            for (String path : directories) if (!new File(game, path).isDirectory()) missing.append("• ").append(path).append('/').append('\n');
            if (missing.length() > 0) return s(R.string.tsoa_maintenance_missing_files, missing);
            return s(R.string.tsoa_maintenance_files_present);
        });
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_SAVES && requestCode != IMPORT_SAVES && requestCode != EXPORT_DIAGNOSTICS
                && requestCode != IMPORT_SCENE) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (requestCode == IMPORT_SCENE) execute(s(R.string.tsoa_scene_checking), () -> {
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException();
                TsoaSceneStore.install(activity, input);
            } catch (IOException | SecurityException error) {
                android.util.Log.w("TSOA_FE_DIAG", "Could not import scene profile", error);
                throw new IOException(s(R.string.tsoa_scene_import_failed), error);
            }
            return s(R.string.tsoa_scene_imported);
        });
        else if (requestCode == EXPORT_SAVES) execute(s(R.string.tsoa_maintenance_exporting_saves), () -> {
            try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException(s(R.string.tsoa_maintenance_cannot_open_file));
                TsoaSaveArchive.write(new File(activity.getFilesDir(), SAVE_PATH), output);
            }
            return s(R.string.tsoa_maintenance_saves_exported);
        });
        else if (requestCode == IMPORT_SAVES) execute(s(R.string.tsoa_maintenance_restoring_saves), () -> {
            File backup;
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException(s(R.string.tsoa_maintenance_cannot_read_backup));
                backup = TsoaSaveArchive.restore(new File(activity.getFilesDir(), SAVE_PATH), input);
            }
            return s(R.string.tsoa_maintenance_saves_restored) + (backup == null ? "" : s(R.string.tsoa_maintenance_previous_backup, backup.getName()));
        });
        else execute(s(R.string.tsoa_maintenance_exporting_diagnostics), () -> {
            try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException(s(R.string.tsoa_maintenance_cannot_open_file));
                writeDiagnostics(output);
            }
            return s(R.string.tsoa_maintenance_diagnostics_exported);
        });
        return true;
    }

    private void createDocument(int requestCode, String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startPicker(intent, requestCode);
    }

    private void startPicker(Intent intent, int requestCode) {
        try { activity.startActivityForResult(intent, requestCode); }
        catch (Exception error) { showResult(s(R.string.tsoa_maintenance_cannot_open_picker)); }
    }

    private interface Job { String run() throws Exception; }

    private void execute(String message, Job job) {
        if (busy || closed) return;
        busy = true;
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String result;
            try { result = job.run(); }
            catch (Exception error) {
                String detail = TsoaMenuErrors.message(activity, error);
                result = s(R.string.tsoa_maintenance_failed,
                    detail == null ? s(R.string.tsoa_maintenance_check_file_space) : detail);
            }
            busy = false;
            final String text = result;
            activity.runOnUiThread(() -> { if (!closed && !activity.isFinishing() && !activity.isDestroyed()) showResult(text); });
        });
    }

    private void writeDiagnostics(OutputStream output) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("informacoes.txt"));
            zip.write(metadata().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            addLog(zip, "redirector.log", new File(activity.getFilesDir(), "tsoa_redirector_v2/redirector.log"));
            addLog(zip, "Player.log", new File(activity.getFilesDir(), SAVE_PATH + "/Player.log"));
            addLog(zip, "Player-prev.log", new File(activity.getFilesDir(), SAVE_PATH + "/Player-prev.log"));
            File relocation = new File(activity.getFilesDir(), "tsoa_redirector_v2/tsoa-runtime.txt");
            if (relocation.isFile()) addLog(zip, "tsoa-runtime.txt", relocation);
        }
    }

    private String metadata() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        TsoaOptions options = new TsoaOptions(activity);
        return s(R.string.tsoa_maintenance_metadata,
            new Date().toString(), activity.getPackageName(), info.versionName, info.versionCode,
            Build.MANUFACTURER + " " + Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS), Process.myUid(),
            options.getScreenSize(), options.getFpsLimit(), options.getControlsMode(),
            GameImportManager.isValidGameDirectory(GameImportManager.getGameDirectory(activity)));
    }

    private static void addLog(ZipOutputStream zip, String name, File file) throws IOException {
        if (!file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath())) return;
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - MAX_LOG_BYTES);
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            byte[] buffer = new byte[32768];
            long remaining = MAX_LOG_BYTES;
            int count;
            while (remaining > 0 && (count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining))) != -1) {
                zip.write(buffer, 0, count);
                remaining -= count;
            }
        }
        zip.closeEntry();
    }

    private void showResult(String message) { showDialog("TSOA_FE", message, s(R.string.tsoa_maintenance_ok), null); }

    private void showDialog(String title, String message, String positive, Runnable action) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(activity, android.R.style.Theme_Material_Dialog_Alert))
            .setTitle(title).setMessage(message).setPositiveButton(positive, (dialog, which) -> { if (action != null) action.run(); });
        if (action != null) builder.setNegativeButton(s(R.string.tsoa_maintenance_cancel), null);
        AlertDialog dialog = builder.create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xff102027));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xffa9c9c6);
        if (action != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xff8eaaaa);
    }

    private String s(int resource, Object... arguments) {
        return TsoaMenuLanguage.get(activity, resource, arguments);
    }

    private static String timestamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()); }

    /** Allows a job already writing a backup to finish safely while preventing stale UI callbacks. */
    public void close() { closed = true; worker.shutdown(); }
}
