package com.winlator.tsoa;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.renderer.EffectComposer;
import com.winlator.renderer.SceneProfile;

/** Runs on the main thread. File reads finish asynchronously and only apply to the requesting session. */
public final class TsoaSceneSession implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Context app;
    private final TsoaOptions options;
    private final EffectComposer composer;
    private SceneProfile profile;
    private boolean observing, requested;
    private int generation;

    public TsoaSceneSession(Context context, EffectComposer composer) {
        app = context.getApplicationContext();
        options = new TsoaOptions(app);
        this.composer = composer;
    }

    public void start() {
        if (observing) return;
        observing = true;
        TsoaOptions.prefs(app).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    public void stop() {
        if (!observing) return;
        observing = false;
        TsoaOptions.prefs(app).unregisterOnSharedPreferenceChangeListener(this);
        discard();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (!observing) return;
        if (key == null || TsoaSceneStore.REVISION_KEY.equals(key)) discard();
        else if (!TsoaOptions.GENESIS.equals(key) && !TsoaOptions.LUMEN.equals(key)) return;
        refresh();
    }

    private void discard() {
        ++generation;
        if (profile != null) composer.configureReconstruction(null, 0, 0);
        profile = null;
        requested = false;
    }

    private void refresh() {
        int genesis = options.getGenesisLevel(), lumen = options.getLumenLevel();
        if (genesis == 0 && lumen == 0) {
            discard();
        } else if (profile != null) {
            composer.configureReconstruction(profile, genesis, lumen);
        } else if (!requested) {
            requested = true;
            final int token = generation;
            TsoaSceneStore.read(app, (result, damaged) -> {
                if (!observing || generation != token || result == null) return;
                profile = result;
                refresh();
            });
        }
    }
}
