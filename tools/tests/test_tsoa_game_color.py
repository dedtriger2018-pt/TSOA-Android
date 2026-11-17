#!/usr/bin/env python3
"""Exercise the production game-color lifecycle without Android or GPU dependencies.

Requires Python 3 and JDK 17 or newer. The renderer and material interfaces are
stubbed; ColorEffect, Effect and TsoaGameColor use their actual Java sources.
GLSL compilation and rendering are covered by the separate GLES validation.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "com/winlator/renderer/material/ShaderMaterial.java": """
package com.winlator.renderer.material;
import java.util.HashMap;
import java.util.Map;
public class ShaderMaterial {
    public static class Uniform {
        public final String name;
        public Uniform(String name) { this.name = name; }
    }
    public final Map<String, Float> values = new HashMap<>();
    protected String getFragmentShader() { return ""; }
    public void use() {}
    public void setUniformFloat(Uniform uniform, float value) {
        values.put(uniform.name, value);
    }
}
""",
    "com/winlator/renderer/material/ScreenMaterial.java": """
package com.winlator.renderer.material;
public class ScreenMaterial extends ShaderMaterial {}
""",
    "com/winlator/renderer/GLRenderer.java": """
package com.winlator.renderer;
import com.winlator.renderer.effects.Effect;
import java.util.ArrayList;
public class GLRenderer {
    public final Composer effectComposer = new Composer();
    public static class Composer {
        public final ArrayList<Effect> effects = new ArrayList<>();
        public int renderRequests;
        public <T extends Effect> T getEffect(Class<T> type) {
            for (Effect effect : effects) {
                if (effect.getClass() == type) return type.cast(effect);
            }
            return null;
        }
        public void addEffect(Effect effect) {
            if (!effects.contains(effect)) effects.add(effect);
            renderRequests++;
        }
        public void removeEffect(Effect effect) {
            effects.remove(effect);
            renderRequests++;
        }
    }
}
""",
}


HARNESS = """
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.tsoa.TsoaGameColor;

public class TsoaGameColorCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void neutralControls(ColorEffect effect) {
        require(effect.getBrightness() == 0.0f && effect.getContrast() == 0.0f
                && effect.getGamma() == 1.0f, "Preset changed legacy controls");
    }

    private static void uniform(ScreenMaterial material, String name, float value) {
        require(material.values.containsKey(name), "Missing uniform: " + name);
        require(material.values.get(name) == value, "Wrong uniform: " + name);
    }

    public static void main(String[] args) {
        for (int mode : new int[] {0, 1, 2, -1, 3}) TsoaGameColor.apply(null, mode);
        ColorEffect bounds = new ColorEffect();
        require(bounds.getHdrStrength() == 0.0f, "Manual effects must default to HDR Off");
        bounds.setHdrStrength(-3.0f);
        require(bounds.getHdrStrength() == 0.0f, "Negative strength escaped bounds");
        bounds.setHdrStrength(4.0f);
        require(bounds.getHdrStrength() == 1.0f, "Large strength escaped bounds");
        bounds.setHdrStrength(Float.NaN);
        require(bounds.getHdrStrength() == 0.0f, "NaN reached shader strength");
        bounds.setHdrStrength(Float.POSITIVE_INFINITY);
        require(bounds.getHdrStrength() == 1.0f, "Positive infinity reached shader strength");
        bounds.setHdrStrength(Float.NEGATIVE_INFINITY);
        require(bounds.getHdrStrength() == 0.0f, "Negative infinity reached shader strength");
        ScreenMaterial manualMaterial = bounds.getMaterial();
        manualMaterial.use();
        uniform(manualMaterial, "hdrStrength", 0.0f);
        System.out.println("PASS: manual default Off, bounded/nonfinite strength inputs, null renderer");

        GLRenderer renderer = new GLRenderer();
        Effect otherEffect = new Effect() {};
        renderer.effectComposer.addEffect(otherEffect);
        int beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.effects.size() == 1,
                "Off added a color pass");
        require(renderer.effectComposer.renderRequests == beforeOff,
                "Fresh Off requested an unnecessary render");

        TsoaGameColor.apply(renderer, 1);
        ColorEffect effect = renderer.effectComposer.getEffect(ColorEffect.class);
        require(effect != null && effect.getHdrStrength() == 0.45f,
                "Suave preset strength changed");
        neutralControls(effect);
        ScreenMaterial material = effect.getMaterial();
        material.use();
        uniform(material, "hdrStrength", 0.45f);
        uniform(material, "brightness", 0.0f);
        uniform(material, "contrast", 0.0f);
        uniform(material, "gamma", 1.0f);

        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == effect,
                "Mode switch replaced the existing color effect");
        require(effect.getMaterial() == material, "Mode switch replaced the color material");
        require(effect.getHdrStrength() == 1.0f && renderer.effectComposer.effects.size() == 2,
                "Marcante added another color pass or has wrong strength");
        neutralControls(effect);
        material.use();
        uniform(material, "hdrStrength", 1.0f);
        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.effects.size() == 2,
                "Repeated preset application duplicated the color pass");
        System.out.println("PASS: Suave/Marcante reuse one effect and material; live uniform upload");

        TsoaGameColor.apply(renderer, 0);
        require(effect.getHdrStrength() == 0.0f,
                "Off left automatic HDR strength active");
        require(renderer.effectComposer.effects.size() == 1
                && renderer.effectComposer.effects.contains(otherEffect),
                "Off removed another effect or retained the neutral automatic color pass");

        ColorEffect manual = new ColorEffect();
        manual.setBrightness(0.1f);
        manual.setContrast(-0.2f);
        manual.setGamma(1.2f);
        renderer.effectComposer.addEffect(manual);
        beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual
                && manual.getBrightness() == 0.1f && manual.getContrast() == -0.2f
                && manual.getGamma() == 1.2f,
                "Off changed a manual-only color effect");
        require(renderer.effectComposer.renderRequests == beforeOff,
                "Off touched a manual-only effect");

        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual,
                "Preset failed to reuse the manual color pass");
        neutralControls(manual);
        manual.setBrightness(0.07f);
        manual.setContrast(0.13f);
        manual.setGamma(1.1f);
        beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual
                && manual.getHdrStrength() == 0.0f && manual.getBrightness() == 0.07f
                && manual.getContrast() == 0.13f && manual.getGamma() == 1.1f,
                "Off discarded manual edits while clearing automatic HDR");
        require(renderer.effectComposer.renderRequests > beforeOff,
                "Clearing automatic HDR did not request a redraw");
        require(renderer.effectComposer.effects.contains(otherEffect),
                "Clearing automatic HDR changed an unrelated effect");
        manual.getMaterial().use();
        uniform(manual.getMaterial(), "hdrStrength", 0.0f);
        uniform(manual.getMaterial(), "brightness", 0.07f);
        uniform(manual.getMaterial(), "contrast", 0.13f);
        uniform(manual.getMaterial(), "gamma", 1.1f);

        for (int invalidMode : new int[] {-1, 3, 999}) {
            TsoaGameColor.apply(renderer, 1);
            TsoaGameColor.apply(renderer, invalidMode);
            require(renderer.effectComposer.getEffect(ColorEffect.class) == null,
                    "Unknown mode did not default to Off: " + invalidMode);
            require(renderer.effectComposer.effects.size() == 1
                    && renderer.effectComposer.effects.contains(otherEffect),
                    "Unknown mode changed an unrelated effect");
        }
        System.out.println("PASS: Off clears automatic HDR, preserves manual edits and other effects; unknown modes Off");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    production = repo / "app/src/main/java/com/winlator"
    with tempfile.TemporaryDirectory(prefix="tsoa-game-color-test-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        harness = sources / "TsoaGameColorCheck.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options",
            "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
            str(production / "renderer/effects/Effect.java"),
            str(production / "renderer/effects/ColorEffect.java"),
            str(production / "tsoa/TsoaGameColor.java"),
        ], check=True)
        subprocess.run([
            args.java, "-cp", str(classes), "TsoaGameColorCheck",
        ], check=True)


if __name__ == "__main__":
    main()
