#!/usr/bin/env python3
"""Run the real runtime-config helper with filesystem fixtures, without an SDK.

Requires a JDK and org.json's json-20240303.jar, passed through --json-jar.
The jar must have SHA256
3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed.
Only Android/platform interfaces are stubbed; JSON parsing and atomic filesystem
operations use their real implementations. No device data is accessed.
"""

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile


JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"

STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {
    private final java.io.File files;
    public Context(java.io.File files) { this.files = files; }
    public java.io.File getFilesDir() { return files; }
}
""",
    "android/util/Log.java": """
package android.util;
public class Log {
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
}
""",
    "androidx/annotation/NonNull.java": """
package androidx.annotation;
public @interface NonNull {}
""",
    "com/winlator/xenvironment/RootFS.java": """
package com.winlator.xenvironment;
public class RootFS {
    private final java.io.File root;
    public RootFS(java.io.File root) { this.root = root; }
    public java.io.File getRootDir() { return root; }
    public java.io.File getLibDir() { return new java.io.File(root, "usr/lib"); }
}
""",
}

HARNESS = r"""
import android.content.Context;
import com.winlator.core.EnvVars;
import com.winlator.core.TsoaRuntimeConfig;
import com.winlator.core.TsoaAudioConfig;
import com.winlator.xenvironment.RootFS;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class RuntimeConfigCheck {
    private static final String SOURCE = "{\"ICD\":{\"api_version\":\"1.4.354\","
        + "\"library_arch\":\"64\",\"library_path\":\"/legacy/libvulkan_freedreno.so\","
        + "\"vendor_property\":\"preserved\"},\"file_format_version\":\"1.0.1\","
        + "\"custom_metadata\":17}";

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject readJson(Path path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private static void noTemporaryFiles(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "Atomic update left a temporary file");
        }
    }

    private static void expectFailure(Context context, RootFS root, EnvVars env,
            Path destination, byte[] previous) throws Exception {
        String before = env.toString();
        try {
            TsoaRuntimeConfig.apply(context, root, env);
            throw new AssertionError("Expected startup to reject an invalid runtime");
        } catch (IOException expected) {
            require(expected.getMessage() != null, "Failure has no diagnostic");
        }
        require(before.equals(env.toString()), "Failure partially changed the environment");
        require(Arrays.equals(previous, Files.readAllBytes(destination)),
                "Failure replaced the last usable manifest");
        noTemporaryFiles(destination.getParent());
    }

    public static void main(String[] args) throws Exception {
        Path files = PathOf(args[0]).resolve("com.example.test/files");
        Path rootPath = files.resolve("rootfs");
        RootFS root = new RootFS(rootPath.toFile());
        Context context = new Context(files.toFile());
        Path source = rootPath.resolve("usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
        Path driver = rootPath.resolve("lib/libvulkan_freedreno.so");
        Path fallback = rootPath.resolve("usr/lib/libvulkan_freedreno.so");
        Path rc = rootPath.resolve("home/xuser/.box64rc");
        Path alsaConfig = rootPath.resolve("etc/alsa/conf.d/android_aserver.conf");
        Path alsaMain = rootPath.resolve("usr/share/alsa/alsa.conf");
        Path alsaPlugins = rootPath.resolve("usr/lib/alsa-lib");
        Path alsaBridge = alsaPlugins.resolve("libasound_module_pcm_android_aserver.so");
        Path destination = files.resolve("tsoa_redirector_v2/freedreno_icd.json");
        write(source, SOURCE);
        write(driver, "driver fixture");
        write(rc, "[*]\nBOX64_ENV4=custom-user-setting\n");
        write(alsaConfig, "pcm.!default { type android_aserver }\n");
        write(alsaMain, "@hooks [{ func load files [\"/data/data/com.winlator/files/rootfs/etc/alsa/conf.d/android_aserver.conf\"] errors false }]\n");
        write(alsaBridge, "bridge plugin fixture");
        byte[] alsaConfigBefore = Files.readAllBytes(alsaConfig);
        byte[] alsaMainBefore = Files.readAllBytes(alsaMain);
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] rcBefore = Files.readAllBytes(rc);
        EnvVars env = new EnvVars();
        env.put("WINEDEBUG", "user-setting");
        env.put("DISPLAY", ":0");
        env.put("WINEESYNC", "1");
        env.put("BOX64_ENV5", "WINEESYNC=0");
        env.put("ANDROID_ALSA_SERVER", rootPath.resolve("tmp/.alsa").toString());
        env.put("ANDROID_ASERVER_USE_SHM", "false");
        env.put("ALSA_CONFIG_PATH", "/data/data/com.winlator/files/rootfs/usr/share/alsa/alsa.conf");
        env.put("ALSA_PLUGIN_DIR", "/data/data/com.winlator/files/rootfs/usr/lib/alsa-lib");
        TsoaRuntimeConfig.apply(context, root, env);
        JSONObject output = readJson(destination);
        require(output.getJSONObject("ICD").getString("library_path").equals(driver.toString()),
                "Copied manifest did not use the current root");
        require(output.getJSONObject("ICD").getString("api_version").equals("1.4.354"),
                "ICD API metadata changed");
        require(output.getJSONObject("ICD").getString("library_arch").equals("64"),
                "ICD architecture metadata changed");
        require(output.getJSONObject("ICD").getString("vendor_property").equals("preserved"),
                "Unknown ICD metadata was discarded");
        require(output.getInt("custom_metadata") == 17, "Top-level metadata was discarded");
        require(Arrays.equals(sourceBefore, Files.readAllBytes(source)), "Original ICD was modified");
        require(Arrays.equals(rcBefore, Files.readAllBytes(rc)), "User's RC was modified");
        require(env.get("DISPLAY").equals(rootPath.resolve("tmp/.X11-unix/X0").toString()),
                "DISPLAY uses a stale package path");
        require(env.get("WINEESYNC").equals("0"), "esync is still enabled");
        require(env.get("VK_DRIVER_FILES").equals(destination.toString()), "Wrong ICD selected");
        require(env.get("WINEDEBUG").equals("user-setting"), "User diagnostics were overwritten");
        require(!env.has("VK_LOADER_DEBUG") && !env.has("DXVK_LOG_LEVEL"),
                "Verbose diagnostics are mandatory");
        require(env.get("ALSA_CONFIG_PATH").equals(alsaConfig.toString()),
                "ALSA still uses the top-level config with its obsolete absolute include");
        require(env.get("ALSA_PLUGIN_DIR").equals(alsaPlugins.toString()),
                "ALSA plugin lookup still uses the old package");
        require(env.get("BOX64_ENV5").equals("WINEESYNC=0"), "Audio replaced a Box64 environment slot");
        require(env.get("ANDROID_ALSA_SERVER").equals(rootPath.resolve("tmp/.alsa").toString()),
                "Audio relocation changed the running bridge server socket");
        require(env.get("ANDROID_ASERVER_USE_SHM").equals("false"), "Audio changed shared-memory policy");
        require(Arrays.equals(alsaConfigBefore, Files.readAllBytes(alsaConfig)), "Bridge configuration was modified");
        require(Arrays.equals(alsaMainBefore, Files.readAllBytes(alsaMain)), "Packaged ALSA main config was modified");
        noTemporaryFiles(destination.getParent());
        System.out.println("PASS: relocation, ALSA bridge paths, metadata, user RC and environment preservation");

        TsoaRuntimeConfig.apply(context, root, env);
        require(readJson(destination).getJSONObject("ICD").getString("library_path")
                .equals(driver.toString()), "Repeated apply failed");
        noTemporaryFiles(destination.getParent());
        System.out.println("PASS: repeated atomic replacement");

        EnvVars pulse = new EnvVars();
        pulse.put("PULSE_SERVER", "explicit-user-server");
        String pulseBefore = pulse.toString();
        require(!TsoaAudioConfig.apply(rootPath.toFile(), pulse), "ALSA settings leaked into a non-ALSA session");
        require(pulseBefore.equals(pulse.toString()), "Non-ALSA environment changed");
        EnvVars incompleteAudio = new EnvVars();
        incompleteAudio.put("ANDROID_ALSA_SERVER", "running-server");
        incompleteAudio.put("ALSA_CONFIG_PATH", "custom-before-failed-selection");
        incompleteAudio.put("BOX64_ENV5", "WINEESYNC=0");
        String incompleteBefore = incompleteAudio.toString();
        Files.delete(alsaBridge);
        require(!TsoaAudioConfig.apply(rootPath.toFile(), incompleteAudio), "Missing audio plugin was accepted");
        require(incompleteBefore.equals(incompleteAudio.toString()), "Missing plugin partially changed audio environment");
        // Missing optional audio assets must not turn a working game into a startup failure.
        TsoaRuntimeConfig.apply(context, root, env);
        write(alsaBridge, "bridge plugin fixture");
        Files.delete(alsaConfig);
        require(!TsoaAudioConfig.apply(rootPath.toFile(), incompleteAudio), "Missing audio config was accepted");
        require(incompleteBefore.equals(incompleteAudio.toString()), "Missing config partially changed audio environment");
        TsoaRuntimeConfig.apply(context, root, env);
        write(alsaConfig, new String(alsaConfigBefore, StandardCharsets.UTF_8));
        System.out.println("PASS: non-ALSA sessions preserved; missing optional config/plugin keep startup usable");

        Files.delete(driver);
        write(fallback, "fallback driver fixture");
        TsoaRuntimeConfig.apply(context, root, env);
        require(readJson(destination).getJSONObject("ICD").getString("library_path")
                .equals(fallback.toString()), "usr/lib fallback did not resolve");
        System.out.println("PASS: usr/lib fallback");

        byte[] lastGood = Files.readAllBytes(destination);
        Files.delete(fallback);
        expectFailure(context, root, env, destination, lastGood);
        write(fallback, "fallback driver fixture");
        write(source, "not JSON");
        expectFailure(context, root, env, destination, lastGood);
        write(source, "{}");
        expectFailure(context, root, env, destination, lastGood);
        Files.delete(source);
        expectFailure(context, root, env, destination, lastGood);
        System.out.println("PASS: missing driver, malformed/missing ICD leave usable state intact");
    }

    private static Path PathOf(String path) { return java.nio.file.Paths.get(path); }
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
    repo = Path(__file__).resolve().parents[2]
    production = repo / "app/src/main/java/com/winlator/core"
    with tempfile.TemporaryDirectory(prefix="tsoa-runtime-test-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        core = sources / "com/winlator/core"
        core.mkdir(parents=True, exist_ok=True)
        for source in ("EnvVars.java", "TsoaAudioConfig.java", "TsoaRuntimeConfig.java"):
            shutil.copyfile(production / source, core / source)
        (sources / "RuntimeConfigCheck.java").write_text(HARNESS)
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options",
            "-cp", str(jar), "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
        ], check=True)
        subprocess.run([
            args.java, "-cp", f"{classes}:{jar}", "RuntimeConfigCheck", str(temporary),
        ], check=True)


if __name__ == "__main__":
    main()
