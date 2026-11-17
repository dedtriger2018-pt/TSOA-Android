package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class ColorEffect extends Effect {
    private float brightness = 0.0f;
    private float contrast = 0.0f;
    private float gamma = 1.0f;
    private float hdrStrength = 0.0f;
    private volatile boolean bloomEnabled;
    private boolean bloomTextureAvailable;

    @Override
    public ScreenMaterial createMaterial() {
        final ShaderMaterial.Uniform brightnessUniform = new ShaderMaterial.Uniform("brightness");
        final ShaderMaterial.Uniform contrastUniform = new ShaderMaterial.Uniform("contrast");
        final ShaderMaterial.Uniform gammaUniform = new ShaderMaterial.Uniform("gamma");
        final ShaderMaterial.Uniform hdrStrengthUniform = new ShaderMaterial.Uniform("hdrStrength");
        final ShaderMaterial.Uniform bloomTextureUniform = new ShaderMaterial.Uniform("bloomTexture");
        final ShaderMaterial.Uniform bloomStrengthUniform = new ShaderMaterial.Uniform("bloomStrength");

        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return getColorFragmentShader();
            }

            @Override
            public void use() {
                super.use();

                setUniformFloat(brightnessUniform, brightness);
                setUniformFloat(contrastUniform, contrast);
                setUniformFloat(gammaUniform, gamma);
                setUniformFloat(hdrStrengthUniform, hdrStrength);
                setUniformInt(bloomTextureUniform, 1);
                setUniformFloat(bloomStrengthUniform, bloomEnabled && bloomTextureAvailable ? 0.48f : 0.0f);
            }
        };
    }

    /** Production GLSL is exposed for headless GLES validation. */
    public static String getColorFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform float brightness;",
            "uniform float contrast;",
            "uniform float gamma;",
            "uniform float hdrStrength;",
            "uniform sampler2D bloomTexture;",
            "uniform float bloomStrength;",
            "in vec2 vUV;",

            "layout(location = 0) out vec4 outFragColor;",

            // SDR display enhancement: a bounded lightness curve, then
            // adaptive vibrance. Every operation uses this same texel.
            "vec3 applyHdrLook(vec3 color, float strength) {",
                "const vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);",
                "float lightness = 0.5 * (min(color.r, min(color.g, color.b)) +",
                    "max(color.r, max(color.g, color.b)));",
                // HSL lightness keeps saturated primary peaks intact.
                // Equivalent curve: x + 0.60*s*x*(1-x)*(2*x-1).
                // The factored form needs no division near black/white.
                "float slope = 0.60 * strength * (2.0 * lightness - 1.0);",
                "if (lightness < 0.5) {",
                    "color *= 1.0 + slope * (1.0 - lightness);",
                "} else {",
                    "color = 1.0 - (1.0 - color) * (1.0 - slope * lightness);",
                "}",
                "float luma = dot(color, lumaWeights);",
                "float low = min(color.r, min(color.g, color.b));",
                "float high = max(color.r, max(color.g, color.b));",
                // Chroma expansion follows the original hue. Smoothly
                // approach the gamut boundary instead of clipping it.
                "float room = min(low / max(luma - low, 0.00001),",
                    "(1.0 - high) / max(high - luma, 0.00001));",
                "float vibrance = 0.75 * strength * (1.0 - (high - low));",
                "float boost = vibrance * room / (vibrance + room + 0.00001);",
                "return color + (color - vec3(luma)) * boost;",
            "}",

            "void main() {",
                "vec4 texelColor = texture(screenTexture, vUV);",
                "vec3 color = texelColor.rgb;",

                "color = clamp(color + brightness, 0.0, 1.0);",
                "color = (color - 0.5) * clamp(contrast + 1.0, 0.5, 2.0) + 0.5;",
                // Contrast can make negative channels; pow must receive
                // a bounded base even when HDR enhancement is disabled.
                "color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / max(gamma, 0.001)));",
                "if (hdrStrength > 0.0) color = applyHdrLook(color, hdrStrength);",

                // Bounded screen blend keeps highlights and the original alpha.
                "if (bloomStrength > 0.0) {",
                    "vec3 glow = texture(bloomTexture, vUV).rgb * bloomStrength;",
                    "color += (1.0 - color) * glow;",
                "}",

                "outFragColor = vec4(color, texelColor.a);",
            "}"
        );
    }

    public boolean isBloomEnabled() {
        return bloomEnabled;
    }

    /** The composer suppresses sampling if its private low-resolution target failed. */
    public void setBloomTextureAvailable(boolean available) {
        bloomTextureAvailable = available;
    }

    public void setBloomEnabled(boolean enabled) {
        bloomEnabled = enabled;
    }

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = brightness;
    }

    public float getContrast() {
        return contrast;
    }

    public void setContrast(float contrast) {
        this.contrast = contrast;
    }

    public float getGamma() {
        return gamma;
    }

    public void setGamma(float gamma) {
        this.gamma = gamma;
    }

    public float getHdrStrength() {
        return hdrStrength;
    }

    /** 0 keeps the normal manual color effect; 1 enables the full SDR HDR look. */
    public void setHdrStrength(float hdrStrength) {
        this.hdrStrength = Float.isNaN(hdrStrength) ? 0.0f : Math.max(0.0f, Math.min(1.0f, hdrStrength));
    }
}
