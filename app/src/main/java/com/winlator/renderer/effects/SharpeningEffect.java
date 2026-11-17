package com.winlator.renderer.effects;

import com.winlator.renderer.SpatialShaders;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/** One adaptive pass: CAS alone, or the RCAS stage of FSR1 (never stacked). */
public final class SharpeningEffect extends Effect {
    private final boolean rcas;
    private float strength;
    private final ShaderMaterial.Uniform sharpness = new ShaderMaterial.Uniform("sharpness");

    public SharpeningEffect(boolean rcas) { this.rcas = rcas; }
    public void setStrength(float value) { strength = Math.max(0.0f, Math.min(1.0f, value)); }

    @Override public ScreenMaterial createMaterial() {
        return new ScreenMaterial() {
            @Override protected String getFragmentShader() {
                return rcas ? SpatialShaders.getRcasFragmentShader() : SpatialShaders.getCasFragmentShader();
            }
            @Override public void use() {
                super.use();
                setUniformFloat(sharpness, strength);
            }
        };
    }
}
