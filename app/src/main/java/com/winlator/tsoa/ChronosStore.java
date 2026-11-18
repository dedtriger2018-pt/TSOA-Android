package com.winlator.tsoa;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable local save snapshots. The filename records the actual parent checkpoint. */
final class ChronosStore {
    private static final String ID = "[0-9]{13}-[0-9a-f]{8}";
    private static final String FILENAME = ID + "__(root|" + ID + ")\\.zip";
    static final int MAX_CHECKPOINTS = 64;
    private static final long MAX_STORAGE = 512L * 1024 * 1024;
    private final File folder;

    static final class Point {
        final File file;
        final String id, parent;
        final long time;

        Point(File file) {
            this.file = file;
            String[] fields = file.getName().substring(0, file.getName().length() - 4).split("__");
            id = fields[0]; parent = fields[1];
            time = Long.parseLong(id.substring(0,13));
        }
    }

    ChronosStore(File folder) { this.folder = folder; }

    ArrayList<Point> list() {
        ArrayList<Point> points = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().matches(FILENAME) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                points.add(new Point(file));
        }
        points.sort(Comparator.comparing((Point item) -> item.id).reversed());
        return points;
    }

    synchronized Point create(File saves, String parent) throws IOException {
        ArrayList<Point> points = list();
        if (points.size() >= MAX_CHECKPOINTS) throw new IOException("Chronos capacity");
        long available = MAX_STORAGE;
        for (Point point : points) available -= point.file.length();
        if (available <= 0) throw new IOException("Chronos capacity");
        boolean knownParent = false;
        for (Point point : points) if (point.id.equals(parent)) knownParent = true;
        if ((!folder.isDirectory() && !folder.mkdirs()) || Files.isSymbolicLink(folder.toPath()))
            throw new IOException("Chronos directory");
        Map<String,String> before = snapshot(saves);
        String id = String.format(Locale.ROOT,"%013d-%s",System.currentTimeMillis(), UUID.randomUUID().toString().substring(0,8));
        File file = new File(folder, id + "__" + (knownParent ? parent : "root") + ".zip");
        File temp = File.createTempFile(".chronos-", ".tmp", folder);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                TsoaSaveArchive.write(saves, new BoundedWriter(stream, available));
                stream.getFD().sync();
            }
            if (!before.equals(snapshot(saves))) throw new IOException("Saves changed during checkpoint");
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return new Point(file);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }

    synchronized File restore(File saves, Point point) throws IOException {
        Point current = find(point.id);
        try (FileInputStream input = new FileInputStream(current.file)) {
            return TsoaSaveArchive.restore(saves, input);
        }
    }

    synchronized void delete(Point point) throws IOException {
        for (Point child : list()) if (point.id.equals(child.parent)) throw new IOException("Checkpoint has branches");
        Files.delete(find(point.id).file.toPath());
    }

    private Point find(String id) throws IOException {
        for (Point point : list()) if (point.id.equals(id)) return point;
        throw new IOException("Missing checkpoint");
    }

    private static Map<String,String> snapshot(File saves) throws IOException {
        TreeMap<String,String> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(saves.toPath(), 32)) {
            java.util.Iterator<Path> path = files.iterator();
            while (path.hasNext()) {
                Path file = path.next();
                BasicFileAttributes info = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!info.isRegularFile() || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log")) continue;
                if (result.size() == 8192) throw new IOException("Checkpoint file limit");
                result.put(saves.toPath().relativize(file).toString(), info.size() + "/" + info.lastModifiedTime());
            }
        }
        return result;
    }

    private static final class BoundedWriter extends FilterOutputStream {
        private long remaining;
        BoundedWriter(FileOutputStream destination, long remaining) { super(destination); this.remaining = remaining; }
        @Override public void write(int value) throws IOException {
            if (remaining-- <= 0) throw new IOException("Chronos capacity");
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if ((remaining -= length) < 0) throw new IOException("Chronos capacity");
            out.write(bytes, offset, length);
        }
        @Override public void close() throws IOException { flush(); }
    }
}
