package com.winlator.renderer;


/** A resolved menu snapshot. Zero per-effect intensity disables that effect. */
public final class CinematicSettings {
    public static final CinematicSettings OFF = new CinematicSettings(0, 0, 0, 0, 0, 0, 0, 35, 30);
    final int preset, amount, anamorphic, focus, halation, grain, vignette, grainSize, grainResponse;

    public CinematicSettings(int preset, int amount, int anamorphic, int focus, int halation,
                             int grain, int vignette, int grainSize, int grainResponse) {
        this.preset = preset; this.amount = amount; this.anamorphic = anamorphic; this.focus = focus;
        this.halation = halation; this.grain = grain; this.vignette = vignette;
        this.grainSize = grainSize; this.grainResponse = grainResponse;
    }

    boolean enabled() {
        return preset > 0 && amount > 0 && (anamorphic > 0 || focus > 0 || halation > 0 || grain > 0 || vignette > 0);
    }

    boolean sameAs(CinematicSettings other) {
        return preset == other.preset && amount == other.amount && anamorphic == other.anamorphic
                && focus == other.focus && halation == other.halation && grain == other.grain
                && vignette == other.vignette && grainSize == other.grainSize && grainResponse == other.grainResponse;
    }
}
