package com.winlator.tsoa;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.renderer.EffectComposer;

/** Applies independent reconstruction presets only during a dedicated game session. */
public final class TsoaSceneSession implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final SharedPreferences preferences;
    private final TsoaOptions options;
    private final EffectComposer composer;

    public TsoaSceneSession(Context context, EffectComposer composer) {
        preferences = TsoaOptions.prefs(context);
        options = new TsoaOptions(context);
        this.composer = composer;
    }

    public void start() {
        preferences.registerOnSharedPreferenceChangeListener(this);
        apply();
    }

    public void stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        composer.configureReconstruction(0, 0);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null || TsoaOptions.GENESIS.equals(key) || TsoaOptions.LUMEN.equals(key)) apply();
    }

    private void apply() {
        composer.configureReconstruction(options.getGenesisLevel(), options.getLumenLevel());
    }
}
