package com.winlator.tsoa;

import android.content.Context;
import android.content.SharedPreferences;

/** Launcher preferences, deliberately separate from Wine and runtime compatibility settings. */
public final class TsoaOptions {
    public static final String PREFERENCES = "naninovel_launcher";
    private final SharedPreferences preferences;

    public TsoaOptions(Context context) {
        preferences = prefs(context);
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public String getScreenSize() {
        String size = preferences.getString("screen_size", "960x544");
        return size != null && (size.equals("native") || size.matches("[1-9][0-9]{2,3}x[1-9][0-9]{2,3}"))
                ? size : "960x544";
    }

    public String getAspectMode() {
        return "stretch".equals(preferences.getString("aspect_mode", "fit")) ? "stretch" : "fit";
    }

    public boolean isFullscreen() { return preferences.getBoolean("fullscreen", true); }

    /** Menu-only language; deliberately independent from Android/Wine locale preferences. */
    public String getMenuLanguage() {
        String language = preferences.getString("menu_language", "pt");
        return "en".equals(language) || "zh-CN".equals(language) ? language : "pt";
    }

    /** Optional game-only renderer effect; disabled by default for lower GPU cost. */
    public boolean isGameBloomEnabled() { return preferences.getBoolean("game_bloom_enabled", false); }

    public boolean isGameFxaaEnabled() { return preferences.getBoolean("game_fxaa_enabled", false); }

    /** Extra adaptive sharpening strength, independent from the optional upscaler. */
    public int getGameSharpeningStrength() {
        return Math.max(0, Math.min(100, preferences.getInt("game_sharpening_strength", 0)));
    }

    public boolean isGameFsrEnabled() { return preferences.getBoolean("game_fsr_enabled", false); }

    /** Experimental game-only intermediate frames; opt in after checking device stability. */
    public boolean isGameFrameGenerationEnabled() { return preferences.getBoolean("game_frame_generation_enabled", false); }

    static final String GENESIS = "game_genesis_level";
    static final String LUMEN = "game_lumen_level";

    public int getGenesisLevel() { return getSceneLevel(GENESIS); }
    public int getLumenLevel() { return getSceneLevel(LUMEN); }
    private int getSceneLevel(String key) {
        int level = preferences.getInt(key, 0);
        return level >= 1 && level <= 3 ? level : 0;
    }

    public boolean isOriginalMode() { return enabled("game_original"); }
    public boolean isAdaptiveQuality() { return enabled("game_adaptive"); }
    public boolean isPerformanceVisible() { return enabled("game_performance"); }
    public boolean isPdaEnabled() { return enabled("game_pda"); }
    public int getCinematicLevel() { return getSceneLevel("game_cinematic"); }
    public int getCinematicAmount() { return percent("game_cinematic_amount", 75); }
    public boolean isCinematicVignette() { return enabled("game_cinematic_vignette"); }
    public boolean isCinematicGrain() { return enabled("game_cinematic_grain"); }
    public int getBeyondMode() {
        int mode = preferences.getInt("game_beyond_mode", percent("game_beyond", 0) > 0 ? 2 : 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }
    public int getBeyondBrightness() { return percent("game_beyond_brightness", 72); }
    public int getBeyondAmount() { return percent("game_beyond", 62); }
    public int getBeyondBlur() { return percent("game_beyond_blur", 65); }
    public int getBeyondSaturation() { return percent("game_beyond_saturation", 65); }

    private boolean enabled(String key) { return preferences.getBoolean(key, false); }
    private int percent(String key, int fallback) {
        return Math.max(0, Math.min(100, preferences.getInt(key, fallback)));
    }

    public int getFpsLimit() {
        int value = preferences.getInt("fps_limit", 0);
        return value == 30 || value == 60 ? value : 0;
    }

    /** 0 disables the optional game-only SDR color preset; 1/2 select its strength. */
    public int getGameHdrMode() {
        int value = preferences.getInt("game_hdr_mode", 0);
        return value == 1 || value == 2 ? value : 0;
    }

    public String getControlsMode() {
        String mode = preferences.getString("controls_mode", "esc");
        return "off".equals(mode) || "rts".equals(mode) || "custom".equals(mode) ? mode : "esc";
    }

    public int getProfileId() { return Math.max(0, preferences.getInt("controls_profile_id", 0)); }
    public float getControlsOpacity() { return clamp(preferences.getFloat("controls_opacity", 0.55f), 0.2f, 1.0f); }
    public float getCursorSpeed() { return clamp(preferences.getFloat("cursor_speed", 1.0f), 0.25f, 3.0f); }
    public boolean isTapToClick() { return preferences.getBoolean("tap_to_click", true); }
    public boolean isLongPressRightClick() { return preferences.getBoolean("long_press_right_click", true); }
    public int getVolume() { return Math.max(0, Math.min(100, preferences.getInt("volume", 100))); }
    public boolean isMuted() { return preferences.getBoolean("mute", false); }
    public int getRainIntensity() { return Math.max(1, Math.min(3, preferences.getInt("rain_intensity", 2))); }
    public float getBackgroundBrightness() { return clamp(preferences.getFloat("background_brightness", 0.7f), 0.35f, 1.0f); }
    public boolean isReducedMotion() { return preferences.getBoolean("reduced_motion", false); }
    public boolean isAmbienceEnabled() { return preferences.getBoolean("ambience_enabled", true); }
    public int getAmbienceVolume() { return Math.max(0, Math.min(100, preferences.getInt("ambience_volume", 18))); }
    public boolean isKeepScreenOn() { return preferences.getBoolean("keep_screen_on", true); }
    public boolean isHaptics() { return preferences.getBoolean("haptics", false); }
    public boolean isAutoLaunch() { return preferences.getBoolean("auto_launch", false); }

    private static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
