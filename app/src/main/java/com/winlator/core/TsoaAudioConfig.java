package com.winlator.core;

import java.io.File;

/** Selects the packaged Android ALSA bridge without libasound's old package paths. */
public final class TsoaAudioConfig {
    private TsoaAudioConfig() {}

    /**
     * libasound is native/wrapped, so the x86_64 redirector cannot fix its opens.
     * The packaged top-level alsa.conf also includes an absolute com.winlator path.
     * Select its complete bridge configuration directly, and relocate the plugin
     * directory. Missing optional audio files must not prevent an otherwise usable
     * game from starting; leave the audio environment untouched in that case.
     */
    public static boolean apply(File rootDir, EnvVars envVars) {
        if (envVars.get("ANDROID_ALSA_SERVER").isEmpty()) return false;

        File configuration = new File(rootDir, "etc/alsa/conf.d/android_aserver.conf");
        File plugins = new File(rootDir, "usr/lib/alsa-lib");
        File bridge = new File(plugins, "libasound_module_pcm_android_aserver.so");
        if (!configuration.isFile() || !configuration.canRead()
                || !bridge.isFile() || !bridge.canRead()) return false;

        envVars.put("ALSA_CONFIG_PATH", configuration.getAbsolutePath());
        envVars.put("ALSA_PLUGIN_DIR", plugins.getAbsolutePath());
        return true;
    }
}
