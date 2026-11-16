package com.winlator.core;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Installs the relocated built-in Box64 without replacing a valid binary prematurely. */
public final class TsoaBox64Runtime {
    private static final String TAG = "TSOA_FE_DIAG";
    private static final String METADATA_ASSET = "box64/tsoa-relocation.json";
    private static final String BINARY_PATH = "usr/local/bin/box64";
    private static final int BUFFER_SIZE = 65536;

    private TsoaBox64Runtime() {}

    public static void install(Context context, RootFS rootFS) throws IOException {
        Metadata metadata = readMetadata(context, rootFS);
        File destination = new File(rootFS.getRootDir(), BINARY_PATH);
        if (matches(destination, metadata, true)) {
            Log.i(TAG, "Relocated Box64 already installed and verified.");
            return;
        }

        Path staging = Files.createTempDirectory(context.getCacheDir().toPath(), "tsoa-box64-");
        Path temporary = null;
        try {
            File stagedBinary = new File(staging.toFile(), BINARY_PATH);
            Files.createDirectories(stagedBinary.getParentFile().toPath());
            Path expectedPath = stagedBinary.toPath().normalize();
            boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                    context, metadata.assetPath, staging.toFile(), (file, size) ->
                            file.toPath().normalize().equals(expectedPath) ? file : null);
            if (!extracted || !matches(stagedBinary, metadata, false)) {
                throw new IOException("Relocated Box64 asset failed integrity verification.");
            }

            Files.createDirectories(destination.getParentFile().toPath());
            temporary = Files.createTempFile(destination.getParentFile().toPath(),
                    "box64-tsoa-", ".tmp");
            copyAndSync(stagedBinary, temporary.toFile());
            try {
                Os.chmod(temporary.toString(), 0771);
            }
            catch (ErrnoException e) {
                throw new IOException("Unable to make relocated Box64 executable.", e);
            }
            if (!matches(temporary.toFile(), metadata, true)) {
                throw new IOException("Staged Box64 is not an executable verified copy.");
            }

            Files.move(temporary, destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            Log.i(TAG, "Relocated Box64 installed and verified: " + destination);
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException e) {
                    Log.w(TAG, "Unable to remove temporary Box64: " + temporary, e);
                }
            }
            if (!FileUtils.delete(staging.toFile())) {
                Log.w(TAG, "Unable to remove Box64 staging directory: " + staging);
            }
        }
    }

    private static Metadata readMetadata(Context context, RootFS rootFS) throws IOException {
        JSONObject json;
        try (InputStream input = context.getAssets().open(METADATA_ASSET);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        catch (JSONException e) {
            throw new IOException("Invalid Box64 relocation metadata.", e);
        }

        try {
            String expectedAsset = "box64/box64-" + DefaultVersion.BOX64 + ".tzst";
            String interpreter = new File(rootFS.getRootDir(),
                    "lib/ld-linux-aarch64.so.1").getAbsolutePath();
            String runpath = new File(rootFS.getRootDir(), "lib").getAbsolutePath();
            Metadata metadata = new Metadata(json.getString("asset_path"),
                    json.getString("binary_sha256"), json.getLong("binary_size"));
            if (!metadata.assetPath.equals(expectedAsset)
                    || !metadata.sha256.matches("[0-9a-f]{64}") || metadata.size <= 0
                    || !interpreter.equals(json.getString("interpreter"))
                    || !runpath.equals(json.getString("runpath"))) {
                throw new IOException("Box64 asset is not relocated for the current application path.");
            }
            return metadata;
        }
        catch (JSONException e) {
            throw new IOException("Incomplete Box64 relocation metadata.", e);
        }
    }

    private static boolean matches(File binary, Metadata metadata, boolean executable)
            throws IOException {
        if (!Files.isRegularFile(binary.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !binary.canRead() || binary.length() != metadata.size
                || (executable && !binary.canExecute())) return false;
        return metadata.sha256.equals(sha256(binary));
    }

    private static String sha256(File binary) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        try (FileInputStream input = new FileInputStream(binary)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        char[] result = new char[64];
        byte[] hash = digest.digest();
        for (int i = 0; i < hash.length; i++) {
            int value = hash[i] & 0xff;
            result[i * 2] = hex[value >>> 4];
            result[i * 2 + 1] = hex[value & 0xf];
        }
        return new String(result);
    }

    private static void copyAndSync(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
    }

    private static final class Metadata {
        final String assetPath;
        final String sha256;
        final long size;

        Metadata(String assetPath, String sha256, long size) {
            this.assetPath = assetPath;
            this.sha256 = sha256;
            this.size = size;
        }
    }
}
