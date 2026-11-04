package com.winlator.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TsoaRedirectorV2Log {
    private static final String TAG = "TSOA_REDIRECT_V2";
    private static final Object LOCK = new Object();
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;

    private TsoaRedirectorV2Log() {}

    public static File getLogFile(Context context) {
        if (context == null) return null;

        File directory = new File(
                context.getFilesDir(),
                "tsoa_redirector_v2"
        );

        return new File(directory, "redirector.log");
    }

    public static void append(Context context, String message) {
        if (context == null) {
            Log.e(TAG, "Cannot write diagnostic: Context is null.");
            return;
        }

        File logFile = getLogFile(context);
        File parent = logFile.getParentFile();

        synchronized (LOCK) {
            if (parent == null ||
                    (!parent.isDirectory() && !parent.mkdirs())) {
                Log.e(TAG, "Unable to create diagnostic directory.");
                return;
            }

            try {
                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US
                ).format(new Date());

                String record = "[" + timestamp + "] " +
                        (message != null ? message : "") + "\n";
                byte[] bytes = record.getBytes("UTF-8");

                if (!rotateIfNeeded(logFile, bytes.length)) {
                    Log.e(TAG, "Skipping diagnostic record because rotation failed.");
                    return;
                }

                try (FileOutputStream output =
                             new FileOutputStream(logFile, true)) {
                    output.write(bytes);
                    output.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to append diagnostic log.", e);
            }
        }
    }

    private static boolean rotateIfNeeded(File logFile, int incomingBytes) {
        if (incomingBytes > MAX_LOG_BYTES) {
            Log.e(TAG, "Skipping diagnostic record larger than 4 MB.");
            return false;
        }

        if (!logFile.isFile() ||
                logFile.length() + incomingBytes <= MAX_LOG_BYTES) {
            return true;
        }

        File previous = new File(
                logFile.getParentFile(),
                "redirector.log.1"
        );

        if (previous.exists() && !previous.delete()) {
            Log.e(TAG, "Unable to remove previous rotated log.");
            return false;
        }

        if (!logFile.renameTo(previous)) {
            Log.e(TAG, "Unable to rotate diagnostic log.");
            return false;
        }

        return true;
    }
}
