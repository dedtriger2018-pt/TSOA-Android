#!/usr/bin/env python3
"""Run the production save ZIP codec against real files, including hostile archives."""

import argparse
from pathlib import Path
import subprocess
import tempfile


HARNESS = r'''
package com.winlator.tsoa;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class TsoaSaveArchiveTest {
    private static final byte[] ORIGINAL = "original progress".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i=0; i<names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i])); zip.write(contents[i]); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] archive(String name, byte[] data) throws IOException {
        return zip(new String[]{"tsoa-saves.txt", name}, new byte[][]{"TSOA_FE saves v1\n".getBytes("UTF-8"), data});
    }
    private static void unchanged(Path saves) throws Exception {
        check(Arrays.equals(ORIGINAL, Files.readAllBytes(saves.resolve("progress.dat"))), "original progress changed after rejected archive");
        try (java.util.stream.Stream<Path> siblings=Files.list(saves.getParent())) {
            check(siblings.noneMatch(path -> path.getFileName().toString().startsWith(".tsoa-restore-")), "temporary restoration directory leaked");
        }
    }
    private static void rejected(Path saves, byte[] bytes) throws Exception {
        boolean failed = false;
        try { TsoaSaveArchive.restore(saves.toFile(), new ByteArrayInputStream(bytes)); }
        catch (IOException expected) { failed = true; }
        check(failed, "hostile archive should fail"); unchanged(saves);
    }
    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);
        Path saves = directory.resolve("TSOA_FE"); Files.createDirectories(saves.resolve("NaninovelData"));
        Files.write(saves.resolve("progress.dat"), ORIGINAL);
        Files.write(saves.resolve("NaninovelData/save001.nson"), "chapter 8".getBytes("UTF-8"));
        Files.write(saves.resolve("Player.log"), "private runtime diagnostic".getBytes("UTF-8"));
        Path outside = directory.resolve("outside.txt"); Files.write(outside, "outside".getBytes("UTF-8"));
        Files.createSymbolicLink(saves.resolve("outside-link"), outside);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); TsoaSaveArchive.write(saves.toFile(), output);
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry; while ((entry=zip.getNextEntry())!=null) entries.add(entry.getName());
        }
        check(entries.contains("saves/progress.dat") && entries.contains("saves/NaninovelData/save001.nson"), "game progress missing from export");
        check(!entries.contains("saves/Player.log") && !entries.contains("saves/outside-link"), "export followed symlink or included logs");
        rejected(saves, archive("saves/../../outside.txt", "bad".getBytes("UTF-8")));
        check("outside".equals(new String(Files.readAllBytes(outside), "UTF-8")), "ZipSlip changed outside file");
        rejected(saves, archive("saves/C:\\windows\\file", new byte[]{1}));
        rejected(saves, archive("saves//absolute", new byte[]{1}));
        rejected(saves, archive("user.reg", new byte[]{1}));
        rejected(saves, zip(new String[]{"saves/progress.dat"}, new byte[][]{new byte[]{1}}));
        rejected(saves, zip(new String[]{"tsoa-saves.txt", "saves/progress.dat", "saves/PROGRESS.DAT"}, new byte[][]{"TSOA_FE saves v1\n".getBytes("UTF-8"), new byte[]{1}, new byte[]{2}}));
        rejected(saves, new byte[]{0,1,2,3,4});
        // A tiny compressed input must not expand without a bound.
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bomb)) {
            zip.putNextEntry(new ZipEntry("tsoa-saves.txt")); zip.write("TSOA_FE saves v1\n".getBytes("UTF-8")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("saves/bomb.bin"));
            byte[] zeros = new byte[1024*1024]; for (int i=0;i<257;i++) zip.write(zeros); zip.closeEntry();
        }
        rejected(saves, bomb.toByteArray());
        // A good restore preserves the entire former directory, replacing it only after validation.
        Files.write(saves.resolve("progress.dat"), "newer local progress".getBytes("UTF-8"));
        File backup = TsoaSaveArchive.restore(saves.toFile(), new ByteArrayInputStream(output.toByteArray()));
        check(backup!=null && backup.isDirectory(), "existing saves were not backed up");
        check("newer local progress".equals(new String(Files.readAllBytes(backup.toPath().resolve("progress.dat")), "UTF-8")), "backup lost local progress");
        check(Arrays.equals(ORIGINAL, Files.readAllBytes(saves.resolve("progress.dat"))), "round trip did not restore original progress");
        check("chapter 8".equals(new String(Files.readAllBytes(saves.resolve("NaninovelData/save001.nson")), "UTF-8")), "nested save not restored");
        Path fresh = directory.resolve("fresh/TSOA_FE");
        check(TsoaSaveArchive.restore(fresh.toFile(), new ByteArrayInputStream(output.toByteArray()))==null, "fresh restore should have no prior backup");
        check(Files.isRegularFile(fresh.resolve("progress.dat")), "fresh restore failed");
        System.out.println("PASS: scoped save round trip; backup preservation; fresh restore; rejection of ZipSlip, foreign files, bad marker, duplicate/case collisions, malformed ZIP and 257 MB expansion; logs and symlinks excluded.");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / "app/src/main/java/com/winlator/tsoa/TsoaSaveArchive.java"
    with tempfile.TemporaryDirectory(prefix="tsoa-save-archive-") as name:
        work = Path(name)
        harness = work / "TsoaSaveArchiveTest.java"
        harness.write_text(HARNESS)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-Xlint:-options", "-d", str(classes), str(source), str(harness)], check=True)
        fixture = work / "fixture"
        fixture.mkdir()
        subprocess.run([args.java, "-cp", str(classes), "com.winlator.tsoa.TsoaSaveArchiveTest", str(fixture)], check=True)


if __name__ == "__main__":
    main()
