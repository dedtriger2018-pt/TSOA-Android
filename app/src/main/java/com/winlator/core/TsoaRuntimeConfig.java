package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Configures native libraries whose compiled paths still reference com.winlator. */
public final class TsoaRuntimeConfig {
    private static final String TAG = "TSOA_FE_DIAG";

    private TsoaRuntimeConfig() {}

    public static void apply(Context context, RootFS rootFS, EnvVars envVars)
            throws IOException {
        File rootDir = rootFS.getRootDir();
        File source = new File(rootDir,
                "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
        File driver = new File(rootDir, "lib/libvulkan_freedreno.so");
        if (!driver.isFile() || !driver.canRead()) {
            driver = new File(rootFS.getLibDir(), "libvulkan_freedreno.so");
        }
        if (!driver.isFile() || !driver.canRead()) {
            throw new IOException("Turnip driver is missing or unreadable: " + driver);
        }

        JSONObject manifest;
        try {
            String json = new String(Files.readAllBytes(source.toPath()),
                    StandardCharsets.UTF_8);
            manifest = new JSONObject(json);
            // Keep the packaged manifest intact, including its version and architecture.
            manifest.getJSONObject("ICD").put("library_path", driver.getAbsolutePath());
        }
        catch (JSONException e) {
            throw new IOException("Invalid Turnip ICD manifest: " + source, e);
        }

        File directory = new File(context.getFilesDir(), "tsoa_redirector_v2");
        Files.createDirectories(directory.toPath());
        File destination = new File(directory, "freedreno_icd.json");
        byte[] content = manifest.toString().getBytes(StandardCharsets.UTF_8);
        writeAtomically(destination, content);

        String display = new File(rootDir, "tmp/.X11-unix/X0").getAbsolutePath();
        envVars.put("DISPLAY", display);
        envVars.put("WINEESYNC", "0");
        envVars.put("VK_DRIVER_FILES", destination.getAbsolutePath());

        if (!envVars.get("ANDROID_ALSA_SERVER").isEmpty()) {
            boolean audioConfigured = TsoaAudioConfig.apply(rootDir, envVars);
            if (audioConfigured) {
                Log.i(TAG, "TSOA ALSA configured: ALSA_CONFIG_PATH=" + envVars.get("ALSA_CONFIG_PATH")
                        + " ALSA_PLUGIN_DIR=" + envVars.get("ALSA_PLUGIN_DIR"));
            }
            else {
                Log.w(TAG, "TSOA ALSA configuration or bridge plugin missing; continuing without relocation");
            }
        }

        Log.i(TAG, "TSOA runtime configured: DISPLAY=" + display
                + " VK_DRIVER_FILES=" + destination.getAbsolutePath());
    }

    private static void writeAtomically(File destination, byte[] content)
            throws IOException {
        Path temporary = Files.createTempFile(destination.getParentFile().toPath(),
                "freedreno-icd-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(content);
                output.flush();
                output.getFD().sync();
            }
            Files.move(temporary, destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }
}
