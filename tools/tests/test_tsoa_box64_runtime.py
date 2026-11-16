#!/usr/bin/env python3
"""Check the Box64 installer with filesystem fixtures and injected failures.

Requires a JDK and --json-jar json-20240303.jar. Its pinned SHA256 is shared
with test_tsoa_runtime_config.py. Android/platform and tar extraction interfaces
are stubbed; org.json, SHA256, copying, permissions and atomic moves are real.
This does not execute the ARM64 binary or replace a device installation.
"""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from test_tsoa_runtime_config import JSON_SHA256, STUBS as RUNTIME_STUBS


STUBS = {
    "com/winlator/xenvironment/RootFS.java": RUNTIME_STUBS["com/winlator/xenvironment/RootFS.java"],
    'android/content/Context.java': r"""
package android.content;
import java.io.File;
import android.content.res.AssetManager;
public class Context {
  private final File cache; private final AssetManager assets;
  public Context(File cache, File assetDir) { this.cache=cache; assets=new AssetManager(assetDir); }
  public File getCacheDir() { return cache; }
  public AssetManager getAssets() { return assets; }
}
""",
    'android/content/res/AssetManager.java': r"""
package android.content.res;
import java.io.*;
public class AssetManager {
  private final File directory;
  public AssetManager(File directory) { this.directory=directory; }
  public InputStream open(String name) throws IOException { return new FileInputStream(new File(directory,name)); }
}
""",
    'android/system/ErrnoException.java': r"""
package android.system;
public class ErrnoException extends Exception { public ErrnoException(String message) { super(message); } }
""",
    'android/system/Os.java': r"""
package android.system;
import java.nio.file.*; import java.nio.file.attribute.*; import java.io.IOException;
public class Os {
  public static boolean failChmod;
  public static void chmod(String path,int mode) throws ErrnoException {
    if(failChmod) throw new ErrnoException("injected chmod failure");
    try { Files.setPosixFilePermissions(Paths.get(path),PosixFilePermissions.fromString("rwxrwx--x")); }
    catch(IOException e) { throw new ErrnoException(e.toString()); }
  }
}
""",
    'android/util/Log.java': r"""
package android.util;
public class Log {
  public static int i(String tag,String text) { return 0; }
  public static int w(String tag,String text) { return 0; }
  public static int w(String tag,String text,Throwable t) { return 0; }
}
""",
    'com/winlator/core/DefaultVersion.java': r"""
package com.winlator.core;
public class DefaultVersion { public static final String BOX64="0.4.4"; }
""",
    'com/winlator/core/FileUtils.java': r"""
package com.winlator.core;
import java.io.File;
public class FileUtils {
  public static boolean delete(File file) {
    if(file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
      File[] children=file.listFiles();
      if(children!=null) for(File child:children) if(!delete(child)) return false;
    }
    return file.delete();
  }
}
""",
    'com/winlator/core/TarCompressorUtils.java': r"""
package com.winlator.core;
import android.content.Context; import java.io.*;
public class TarCompressorUtils {
  public enum Type { ZSTD }
  public interface OnExtractFileListener { File onExtractFile(File destination,long size); }
  public static int extractions;
  public static boolean corrupt;
  public static boolean extract(Type type,Context context,String asset,File destination,OnExtractFileListener listener) {
    extractions++;
    try {
      File out=listener.onExtractFile(new File(destination,"./usr/local/bin/box64"),1);
      try(InputStream in=context.getAssets().open(asset);OutputStream output=new FileOutputStream(out)) {
        if(corrupt) { output.write("invalid payload".getBytes("UTF-8")); return true; }
        byte[] buf=new byte[1024]; int count;
        while((count=in.read(buf))!=-1) output.write(buf,0,count);
      }
      return true;
    } catch(IOException e) { return false; }
  }
}
""",
}

HARNESS = r"""
package com.winlator.core;
import android.content.Context; import android.system.Os;
import com.winlator.xenvironment.RootFS;
import java.io.*; import java.nio.file.*; import java.security.*; import java.util.*;
import org.json.JSONObject;
public class TsoaBox64RuntimeTest {
  private static final byte[] PAYLOAD="relocated Box64 fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] LEGACY="old working Box64".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
  private static String hash(byte[] data) throws Exception {
    StringBuilder out=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x",b&255)); return out.toString();
  }
  private static void metadata(Path file,Path root,boolean wrongPath) throws Exception {
    JSONObject value=new JSONObject();
    value.put("asset_path","box64/box64-0.4.4.tzst"); value.put("binary_sha256",hash(PAYLOAD)); value.put("binary_size",PAYLOAD.length);
    value.put("interpreter",root.resolve("lib/ld-linux-aarch64.so.1").toString()+(wrongPath?"-wrong":""));
    value.put("runpath",root.resolve("lib").toString()); Files.write(file,value.toString().getBytes("UTF-8"));
  }
  private static void expectFailure(Context context,RootFS root,Path binary) throws Exception {
    boolean failed=false; try { TsoaBox64Runtime.install(context,root); } catch(IOException expected) { failed=true; }
    check(failed,"expected installation failure"); check(Arrays.equals(Files.readAllBytes(binary),LEGACY),"old binary must remain intact");
  }
  private static void noTemporaryFiles(Path cache,Path bin) throws Exception {
    try(java.util.stream.Stream<Path> files=Files.list(cache)) { check(files.count()==0,"staging directory leaked"); }
    try(java.util.stream.Stream<Path> files=Files.list(bin)) { check(files.count()==1,"temporary binary leaked"); }
  }
  public static void main(String[] args) throws Exception {
    Path work=java.nio.file.Paths.get(args[0]).resolve("fixture"); Files.createDirectories(work); Path root=work.resolve("rootfs"),cache=work.resolve("cache"),assets=work.resolve("assets");
    Files.createDirectories(root.resolve("usr/local/bin")); Files.createDirectories(cache); Files.createDirectories(assets.resolve("box64"));
    Path binary=root.resolve("usr/local/bin/box64"),meta=assets.resolve("box64/tsoa-relocation.json");
    Files.write(binary,LEGACY); binary.toFile().setExecutable(true,true); Files.write(assets.resolve("box64/box64-0.4.4.tzst"),PAYLOAD);
    Context context=new Context(cache.toFile(),assets.toFile()); RootFS rootFS=new RootFS(root.toFile());
    expectFailure(context,rootFS,binary); noTemporaryFiles(cache,binary.getParent());
    metadata(meta,root,true); expectFailure(context,rootFS,binary); check(TarCompressorUtils.extractions==0,"invalid metadata must fail before extraction");
    metadata(meta,root,false); TarCompressorUtils.corrupt=true; expectFailure(context,rootFS,binary); noTemporaryFiles(cache,binary.getParent());
    TarCompressorUtils.corrupt=false; Os.failChmod=true; expectFailure(context,rootFS,binary); noTemporaryFiles(cache,binary.getParent());
    Os.failChmod=false; TsoaBox64Runtime.install(context,rootFS);
    check(Arrays.equals(Files.readAllBytes(binary),PAYLOAD),"new binary must be installed"); check(binary.toFile().canExecute(),"installed binary must be executable"); noTemporaryFiles(cache,binary.getParent());
    int count=TarCompressorUtils.extractions; TsoaBox64Runtime.install(context,rootFS);
    check(TarCompressorUtils.extractions==count,"valid existing binary must skip extraction"); noTemporaryFiles(cache,binary.getParent());
    System.out.println("PASS: missing metadata, wrong root, corrupt asset, chmod failure preserve original; verified atomic install and idempotence.");
    FileUtils.delete(work.toFile());
  }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    jar = args.json_jar.resolve()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != JSON_SHA256:
        parser.error("org.json jar does not match json-20240303.jar SHA256")
    production = Path(__file__).resolve().parents[2] / "app/src/main/java/com/winlator/core/TsoaBox64Runtime.java"
    with tempfile.TemporaryDirectory(prefix="tsoa-box64-runtime-test-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        core = sources / "com/winlator/core"
        core.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(production, core / production.name)
        (core / "TsoaBox64RuntimeTest.java").write_text(HARNESS)
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options",
            "-cp", str(jar), "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
        ], check=True)
        subprocess.run([
            args.java, "-cp", os.pathsep.join((str(classes), str(jar))),
            "com.winlator.core.TsoaBox64RuntimeTest", str(temporary),
        ], check=True)


if __name__ == "__main__":
    main()
