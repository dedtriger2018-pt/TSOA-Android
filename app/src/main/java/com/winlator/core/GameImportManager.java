package com.winlator.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Imports a complete Unity Windows game folder selected with ACTION_OPEN_DOCUMENT_TREE. */
public final class GameImportManager {
    public interface ProgressListener {
        void onProgress(String currentFile, long copiedBytes, long totalBytes);
    }

    private static final String[] CHILD_COLUMNS = new String[]{
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    };
    // Keep headroom for filesystem metadata, temporary files and runtime updates.
    private static final long MINIMUM_FREE_HEADROOM = 512L * 1024L * 1024L;
    private static final long LOW_SPACE_ABORT_THRESHOLD = 256L * 1024L * 1024L;

    private GameImportManager() {}

    public static File getGameDirectory(Context context) {
        return new File(context.getFilesDir(), "game");
    }

    public static boolean isValidGameDirectory(File gameDir) {
        File exe = new File(gameDir, "TSOA.exe");
        File unity = new File(gameDir, "UnityPlayer.dll");
        File assembly = new File(gameDir, "GameAssembly.dll");
        File data = new File(gameDir, "TSOA_Data");
        return exe.isFile() && unity.isFile() && assembly.isFile() &&
            new File(gameDir, "baselib.dll").isFile() &&
            new File(data, "boot.config").isFile() &&
            new File(data, "resources.assets").isFile() &&
            new File(data, "resources.assets.resS").isFile() &&
            new File(data, "il2cpp_data").isDirectory() &&
            new File(data, "Plugins").isDirectory() &&
            new File(data, "StreamingAssets").isDirectory();
    }

    public static long availableBytes(File directory) {
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        }
        catch (Exception e) {
            return -1;
        }
    }

    public static void importTree(Context context, Uri treeUri, ProgressListener listener) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        File gameDir = getGameDirectory(context);
        File stagingDir = new File(context.getFilesDir(), "game.importing");
        File backupDir = new File(context.getFilesDir(), "game.previous");

        deleteRecursively(stagingDir);
        deleteRecursively(backupDir);
        if (!stagingDir.mkdirs()) throw new IOException("Não foi possível criar a pasta temporária de importação.");

        long totalBytes = calculateTotalBytes(resolver, treeUri, rootDocumentId);
        long freeBytes = availableBytes(context.getFilesDir());
        if (freeBytes > 0 && totalBytes > 0 && freeBytes < totalBytes + MINIMUM_FREE_HEADROOM) {
            deleteRecursively(stagingDir);
            throw new IOException(String.format(Locale.ROOT,
                "Espaço insuficiente. A cópia exige aproximadamente %.2f GB e pelo menos %.2f GB livres foram reservados para segurança. Disponível: %.2f GB.",
                totalBytes / 1073741824.0, MINIMUM_FREE_HEADROOM / 1073741824.0,
                freeBytes / 1073741824.0));
        }
        if (freeBytes > 0 && totalBytes <= 0 && freeBytes < LOW_SPACE_ABORT_THRESHOLD) {
            deleteRecursively(stagingDir);
            throw new IOException("O armazenamento livre está muito baixo para iniciar a importação com segurança.");
        }

        long[] copied = new long[]{0};
        long[] lastReported = new long[]{0};
        try {
            copyDirectory(context, resolver, treeUri, rootDocumentId, stagingDir, totalBytes, copied, lastReported, listener);
            if (!isValidGameDirectory(stagingDir)) {
                throw new IOException("A pasta selecionada não parece ser a instalação completa do TSOA. Selecione a pasta que contém TSOA.exe e TSOA_Data.");
            }

            if (gameDir.exists() && !gameDir.renameTo(backupDir)) {
                throw new IOException("Não foi possível preservar a instalação anterior.");
            }
            if (!stagingDir.renameTo(gameDir)) {
                if (backupDir.exists()) backupDir.renameTo(gameDir);
                throw new IOException("Não foi possível finalizar a importação.");
            }
            deleteRecursively(backupDir);
        }
        catch (IOException e) {
            deleteRecursively(stagingDir);
            throw e;
        }
    }

    private static long calculateTotalBytes(ContentResolver resolver, Uri treeUri, String documentId) {
        long total = 0;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) return -1;
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String mimeType = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    long nested = calculateTotalBytes(resolver, treeUri, childId);
                    if (nested < 0) return -1;
                    total += nested;
                }
                else {
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    if (size < 0) return -1;
                    total += size;
                }
            }
        }
        catch (Exception e) {
            return -1;
        }
        return total;
    }

    private static void copyDirectory(Context context, ContentResolver resolver, Uri treeUri, String documentId,
                                      File destination, long totalBytes, long[] copied, long[] lastReported,
                                      ProgressListener listener) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("O provedor de arquivos não retornou o conteúdo da pasta.");
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (!isSafeName(displayName)) throw new IOException("Nome de arquivo inválido na instalação: " + displayName);

                File target = new File(destination, displayName);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Não foi possível criar: " + displayName);
                    copyDirectory(context, resolver, treeUri, childId, target, totalBytes, copied, lastReported, listener);
                }
                else {
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    try (InputStream input = resolver.openInputStream(documentUri);
                         FileOutputStream output = new FileOutputStream(target)) {
                        if (input == null) throw new IOException("Não foi possível ler: " + displayName);
                        byte[] buffer = new byte[1024 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            copied[0] += count;
                            if ((copied[0] & ((64L * 1024L * 1024L) - 1)) < count) {
                                long remaining = availableBytes(context.getFilesDir());
                                if (remaining > 0 && remaining < LOW_SPACE_ABORT_THRESHOLD) {
                                    throw new IOException("A importação foi interrompida para evitar que o armazenamento do celular fique sem espaço.");
                                }
                            }
                            if (listener != null && copied[0] - lastReported[0] >= 16L * 1024L * 1024L) {
                                lastReported[0] = copied[0];
                                listener.onProgress(displayName, copied[0], totalBytes);
                            }
                        }
                        output.getFD().sync();
                        if (listener != null) {
                            lastReported[0] = copied[0];
                            listener.onProgress(displayName, copied[0], totalBytes);
                        }
                    }
                }
            }
        }
        catch (SecurityException e) {
            throw new IOException("Sem permissão para ler a pasta selecionada.", e);
        }
    }

    private static boolean isSafeName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") &&
            name.indexOf('/') == -1 && name.indexOf('\\') == -1 && name.indexOf(':') == -1;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
