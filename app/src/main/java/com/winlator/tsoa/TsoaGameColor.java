package com.winlator.tsoa;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.ColorEffect;

/** Game-only soft bloom and an HDR-style SDR preset in Winlator's existing color correction pass. */
public final class TsoaGameColor {
    private TsoaGameColor() {}

    /**
     * Apply before attaching the game surface, or when a changed lobby mode is
     * resumed. Between mode changes the in-game screen-effect dialog can edit or
     * remove this same ColorEffect for the rest of the session.
     * Disabled adds no pass, clears only this automatic look, and preserves manual
     * color adjustments as well as every other screen effect.
     */
    public static void apply(GLRenderer renderer, int mode) {
        if (renderer == null) return;
        ColorEffect effect = renderer.effectComposer.getEffect(ColorEffect.class);
        if (mode != 1 && mode != 2) {
            if (effect != null && effect.getHdrStrength() > 0.0f) {
                effect.setHdrStrength(0.0f);
                if (isNeutral(effect)) {
                    renderer.effectComposer.removeEffect(effect);
                }
                else renderer.effectComposer.addEffect(effect);
            }
            return;
        }
        if (effect == null) effect = new ColorEffect();

        // Keep the legacy controls neutral: depth and color come from the bounded
        // S curve and vibrance, without lifting black or washing out highlights.
        effect.setBrightness(0.0f);
        effect.setContrast(0.0f);
        effect.setGamma(1.0f);
        effect.setHdrStrength(mode == 1 ? 0.45f : 1.0f);
        renderer.effectComposer.addEffect(effect);
    }

    /** Bloom is independent: changing it never resets HDR or manual color controls. */
    public static void applyBloom(GLRenderer renderer, boolean enabled) {
        if (renderer == null) return;
        ColorEffect effect = renderer.effectComposer.getEffect(ColorEffect.class);
        if (enabled) {
            if (effect == null) effect = new ColorEffect();
            effect.setBloomEnabled(true);
            renderer.effectComposer.addEffect(effect);
        } else if (effect != null && effect.isBloomEnabled()) {
            effect.setBloomEnabled(false);
            if (isNeutral(effect)) renderer.effectComposer.removeEffect(effect);
            else renderer.effectComposer.addEffect(effect);
            renderer.effectComposer.releaseBloomResources();
        }
    }

    private static boolean isNeutral(ColorEffect effect) {
        return !effect.isBloomEnabled() && effect.getHdrStrength() == 0.0f
                && effect.getBrightness() == 0.0f && effect.getContrast() == 0.0f
                && effect.getGamma() == 1.0f;
    }

}
