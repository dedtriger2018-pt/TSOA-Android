package com.winlator.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

public final class TsoaRedirectorV2 {
    private static final String TAG = "TSOA_REDIRECT_V2";

    private static final String ASSET_PATH =
            "tsoa_redirector_v2/libtsoa_redirector_v2_x86_64.so";

    private static final String FILE_NAME =
            "libtsoa_redirector_v2_x86_64.so";

    private static final long EXPECTED_SIZE = 27464L;

    private static final String EXPECTED_SHA256 =
            "05ea9b59c53fe9bc56f6b19268eaa83ec75e1e62376730d948b41ff7a6465a43";

    private static final char[] HEX =
            "0123456789abcdef".toCharArray();

    private TsoaRedirectorV2() {}

    public static File install(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null.");
            return null;
        }

        File directory = new File(
                context.getFilesDir(), "tsoa_redirector_v2");

        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create private directory.");
            return null;
        }

        File destination = new File(directory, FILE_NAME);
        File temporary = new File(directory, FILE_NAME + ".tmp");

        try {
            if (isVerified(destination)) {
                Log.i(TAG, "Existing library verified.");
                return destination;
            }

            if (temporary.exists() && !temporary.delete()) {
                Log.e(TAG, "Unable to remove temporary file.");
                return null;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (
                InputStream input = context.getAssets().open(ASSET_PATH);
                FileOutputStream output = new FileOutputStream(temporary)
            ) {
                byte[] buffer = new byte[16384];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }

                output.flush();
                output.getFD().sync();
            }

            String copiedHash = toHex(digest.digest());

            if (!EXPECTED_SHA256.equals(copiedHash) ||
                    temporary.length() != EXPECTED_SIZE) {
                Log.e(TAG, "Asset integrity verification failed.");
                return null;
            }

            if (!temporary.setReadable(true, true) && !temporary.canRead()) {
                Log.e(TAG, "Temporary library is not readable.");
                return null;
            }

            if (destination.exists() && !destination.delete()) {
                Log.e(TAG, "Unable to replace existing library.");
                return null;
            }

            if (!temporary.renameTo(destination)) {
                Log.e(TAG, "Unable to finalize library installation.");
                return null;
            }

            if (!isVerified(destination)) {
                Log.e(TAG, "Final library verification failed.");
                destination.delete();
                return null;
            }

            Log.i(TAG, "Redirector V2 installed and verified.");
            Log.i(TAG, "Library path: " + destination.getAbsolutePath());

            return destination;

        } catch (Exception e) {
            Log.e(TAG, "Redirector installation failed.", e);
            return null;

        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static boolean isVerified(File file) {
        return file.isFile()
                && file.canRead()
                && file.length() == EXPECTED_SIZE
                && EXPECTED_SHA256.equals(sha256(file));
    }

    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }

            return toHex(digest.digest());

        } catch (Exception e) {
            Log.e(TAG, "Unable to calculate SHA256.", e);
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 0x0f];
        }

        return new String(result);
    }
}
