package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TsoaBox64Runtime;
import com.winlator.core.TsoaRedirectorV2;
import com.winlator.core.TsoaRedirectorV2Log;
import com.winlator.core.TsoaRuntimeConfig;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private volatile boolean startupFailed;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            startupFailed = false;
            try {
                extractBox64File();
                copyDefaultBox64RCFile();
                pid = execGuestProgram();
            }
            catch (IOException e) {
                startupFailed = true;
                Context context = environment.getContext();
                android.util.Log.e("TSOA_FE_DIAG", "Unable to prepare TSOA runtime", e);
                TsoaRedirectorV2Log.append(context, "TSOA runtime setup failed: " + e.getMessage());
                if (terminationCallback != null) terminationCallback.call(1);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public boolean hasStartupFailed() {
        return startupFailed;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private int execGuestProgram() throws IOException {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        boolean tsoaRedirectorRequested =
                "1".equals(envVars.get("TSOA_REDIRECTOR_V2"));
        envVars.remove("TSOA_REDIRECTOR_V2");

        if (tsoaRedirectorRequested) {
            TsoaRuntimeConfig.apply(context, rootFS, envVars);
            TsoaRedirectorV2Log.append(context, "Runtime configured: DISPLAY=" + envVars.get("DISPLAY")
                    + " VK_DRIVER_FILES=" + envVars.get("VK_DRIVER_FILES")
                    + " WINEESYNC=" + envVars.get("WINEESYNC"));
            File redirectorLibrary = TsoaRedirectorV2.install(context);
            String wineDir = rootFS.getWinePath();
            String wineAbsolute = rootDir.getAbsolutePath() + wineDir;

            File wineBin = new File(wineAbsolute, "bin");
            File wineNls = new File(wineAbsolute, "share/wine/nls");

            if (redirectorLibrary != null &&
                    wineBin.isDirectory() && wineNls.isDirectory()) {

                String redirectorPath = redirectorLibrary.getAbsolutePath();
                String existingPreload = envVars.get("BOX64_LD_PRELOAD");
                StringBuilder preload = new StringBuilder(redirectorPath);

                if (!existingPreload.isEmpty()) {
                    for (String entry : existingPreload.split(":")) {
                        if (!entry.isEmpty() && !entry.equals(redirectorPath)) {
                            preload.append(':').append(entry);
                        }
                    }
                }

                envVars.put("BOX64_LD_PRELOAD", preload.toString());
                // RTLD_NEXT must skip the interposer when resolving the original libc function.
                envVars.put("BOX64_FORCE_LD_PRELOAD", "0");
                envVars.put("TSOA_ROOTFS", rootDir.getAbsolutePath());
                envVars.put("TSOA_WINE_DIR", wineDir);

                android.util.Log.i("TSOA_REDIRECT_V2",
                        "Preload configured: " + redirectorPath);
            }
            else {
                throw new IOException("Redirector installation or Wine directories are invalid.");
            }
        }

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        String command = rootDir+"/lib/ld-linux-aarch64.so.1 --inhibit-cache --library-path "+rootDir+"/lib "+rootDir+"/usr/local/bin/box64 "+guestExecutable;

        android.util.Log.e("TSOA_FE_DIAG", "Launching guest command: " + command);
        if (tsoaRedirectorRequested) {
            TsoaRedirectorV2Log.append(context, "===== TSOA guest launch =====");
            TsoaRedirectorV2Log.append(context, "Launching guest command: " + command);
        }

        Callback<String> outputCallback = tsoaRedirectorRequested
                ? line -> {
                    TsoaRedirectorV2Log.append(context, line);
                    android.util.Log.e("TSOA_FE_DIAG", line);
                }
                : null;

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            android.util.Log.e("TSOA_FE_DIAG",
                    "Guest process exited with status=" + status + " command=" + command);
            if (tsoaRedirectorRequested) {
                TsoaRedirectorV2Log.append(context,
                        "Guest process exited with status=" + status);
            }
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        }, outputCallback);
    }

    private void extractBox64File() throws IOException {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        boolean tsoaRuntime = this.envVars != null &&
                "1".equals(this.envVars.get("TSOA_REDIRECTOR_V2"));
        if (tsoaRuntime && DefaultVersion.BOX64.equals(box64Version)) {
            // Validate the installed binary as well as the version: older APKs installed
            // the same Box64 version with an interpreter pointing at com.winlator.
            TsoaBox64Runtime.install(context, environment.getRootFS());
            preferences.edit().putString("current_box64_version", box64Version).apply();
            return;
        }

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}
