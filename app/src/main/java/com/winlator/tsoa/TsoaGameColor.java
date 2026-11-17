package com.winlator.tsoa;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.ColorEffect;

/** A game-only SDR preset using Winlator's existing, single color correction effect. */
public final class TsoaGameColor {
    private TsoaGameColor() {}

    /**
     * Apply once before attaching the game surface. The in-game screen-effect dialog
     * can then edit or remove this same ColorEffect for the rest of the session.
     * Disabled adds no pass and leaves any existing screen effects unchanged.
     */
    public static void apply(GLRenderer renderer, int mode) {
        if (renderer == null || (mode != 1 && mode != 2)) return;
        ColorEffect effect = renderer.effectComposer.getEffect(ColorEffect.class);
        if (effect == null) effect = new ColorEffect();

        // A little brightness keeps the contrast operation's minimum above zero.
        // The upstream shader calls pow after contrast, so negative inputs must be
        // avoided. These modest presets also retain separation in dark scenes.
        if (mode == 1) {
            effect.setBrightness(0.020f);
            effect.setContrast(0.030f);
            effect.setGamma(1.020f);
        }
        else {
            effect.setBrightness(0.030f);
            effect.setContrast(0.050f);
            effect.setGamma(1.050f);
        }
        renderer.effectComposer.addEffect(effect);
    }
}
