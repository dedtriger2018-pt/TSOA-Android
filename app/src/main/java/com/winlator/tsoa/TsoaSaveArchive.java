package com.winlator.tsoa;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** A deliberately scoped, bounded save format. It never imports a Wine prefix. */
public final class TsoaSaveArchive {
    private static final String MARKER = "TSOA_FE saves v1\n";
    private static final int MAX_ENTRIES = 8192;
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_DEPTH = 32;

    private TsoaSaveArchive() {}

    /** The caller owns the stream; all exported paths belong to this game's save directory. */
    public static void write(File saveDirectory, OutputStream output) throws IOException {
        if (!saveDirectory.isDirectory() || Files.isSymbolicLink(saveDirectory.toPath())) {
            throw new IOException("Ainda não há uma pasta de saves do TSOA neste aparelho.");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.putNextEntry(new ZipEntry("tsoa-saves.txt"));
            zip.write(MARKER.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            long[] count = new long[2];
            addDirectory(zip, saveDirectory, saveDirectory, "", count, 0);
            if (count[0] == 0) throw new IOException("Nenhum save ou configuração foi encontrado. Jogue e salve antes de exportar.");
        }
    }

    private static void addDirectory(ZipOutputStream zip, File root, File directory, String relative,
                                     long[] count, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("A pasta de saves contém subpastas demais.");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Não foi possível ler a pasta de saves.");
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) continue;
            String name = relative + file.getName();
            validateRelative(name);
            if (!inside(root, file)) throw new IOException("Caminho fora da pasta de saves.");
            if (file.isDirectory()) {
                addDirectory(zip, root, file, name + "/", count, depth + 1);
            }
            else if (file.isFile() && !file.getName().toLowerCase(Locale.ROOT).endsWith(".log")) {
                if (++count[0] > MAX_ENTRIES) throw new IOException("A pasta de saves contém arquivos demais.");
                zip.putNextEntry(new ZipEntry("saves/" + name));
                try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    copyBounded(input, zip, count);
                }
                zip.closeEntry();
            }
        }
    }

    /** Validates into a new directory, then atomically swaps the old directory into a backup. */
    public static File restore(File saveDirectory, InputStream source) throws IOException {
        File parent = saveDirectory.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Não foi possível criar a pasta de saves.");
        }
        if (Files.isSymbolicLink(saveDirectory.toPath())) throw new IOException("A pasta de saves não pode ser um link.");
        File staging = new File(parent, ".tsoa-restore-" + UUID.randomUUID());
        File incoming = new File(staging, "saves");
        if (!incoming.mkdirs()) throw new IOException("Não foi possível preparar a restauração.");
        File backup = null;
        boolean installed = false;
        try {
            readArchive(incoming, source);
            if (saveDirectory.exists()) {
                backup = new File(parent, "TSOA_FE.backup-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
                if (!saveDirectory.renameTo(backup)) throw new IOException("Não foi possível preservar os saves atuais; nada foi alterado.");
            }
            if (!incoming.renameTo(saveDirectory)) {
                if (backup != null && !backup.renameTo(saveDirectory)) {
                    throw new IOException("Restauração interrompida. Seus saves anteriores estão preservados em " + backup.getName());
                }
                throw new IOException("Não foi possível instalar os saves. A versão anterior foi mantida.");
            }
            installed = true;
            return backup;
        }
        finally {
            // Only the private temporary directory is removed; existing saves/backups are kept.
            deleteTemporary(staging);
            if (!installed && backup != null && !saveDirectory.exists() && backup.exists()) backup.renameTo(saveDirectory);
        }
    }

    private static void readArchive(File incoming, InputStream source) throws IOException {
        Set<String> seen = new HashSet<>();
        long[] count = new long[2];
        int entries = 0;
        boolean hasMarker = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES + 1) throw new IOException("O backup contém entradas demais.");
                String name = entry.getName();
                if (name == null || !seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Entrada repetida no backup.");
                if ("tsoa-saves.txt".equals(name) && !entry.isDirectory()) {
                    byte[] expected = MARKER.getBytes(StandardCharsets.UTF_8);
                    byte[] actual = new byte[expected.length + 1];
                    int offset = 0;
                    while (offset < actual.length) {
                        int read = zip.read(actual, offset, actual.length - offset);
                        if (read == -1) break;
                        offset += read;
                    }
                    if (offset != expected.length || !Arrays.equals(expected, Arrays.copyOf(actual, offset))) {
                        throw new IOException("Este arquivo não é um backup de saves do TSOA.");
                    }
                    hasMarker = true;
                }
                else {
                    if (!name.startsWith("saves/")) throw new IOException("O backup contém arquivos fora da pasta de saves.");
                    String relative = name.substring(6);
                    if (entry.isDirectory() && relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
                    validateRelative(relative);
                    File file = new File(incoming, relative);
                    if (!inside(incoming, file)) throw new IOException("Caminho inválido no backup.");
                    if (entry.isDirectory()) {
                        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Não foi possível criar uma subpasta.");
                    }
                    else {
                        if (entry.getSize() > MAX_BYTES) throw new IOException("Um arquivo do backup excede o limite de 256 MB.");
                        File directory = file.getParentFile();
                        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível criar uma subpasta.");
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            copyBounded(zip, output, count);
                            output.getFD().sync();
                        }
                        count[0]++;
                    }
                }
                zip.closeEntry();
            }
        }
        if (!hasMarker || count[0] == 0) throw new IOException("O arquivo não contém um backup válido de saves do TSOA.");
    }

    private static void copyBounded(InputStream input, OutputStream output, long[] count) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = input.read(buffer)) != -1) {
            count[1] += read;
            if (count[1] > MAX_BYTES) throw new IOException("O backup excede o limite de 256 MB descompactados.");
            output.write(buffer, 0, read);
        }
    }

    private static void validateRelative(String name) throws IOException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("Nome de arquivo inválido no backup.");
        }
        String[] parts = name.split("/", -1);
        if (parts.length > MAX_DEPTH) throw new IOException("O backup contém subpastas demais.");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Caminho inválido no backup.");
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) < 32) throw new IOException("Nome de arquivo inválido no backup.");
        }
    }

    private static boolean inside(File directory, File file) throws IOException {
        return file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator);
    }

    private static void deleteTemporary(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTemporary(child);
        }
        file.delete();
    }
}
