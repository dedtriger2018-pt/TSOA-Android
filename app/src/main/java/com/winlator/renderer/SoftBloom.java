package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/** Three quarter-size passes, allocated only while game bloom is in use. */
public final class SoftBloom {
    private final int[] textures = new int[2];
    private final int[] framebuffers = new int[2];
    private final int[] savedViewport = new int[4];
    private final int[] savedValue = new int[1];
    private EGLContext context;
    private int width;
    private int height;
    private boolean allocationFailed;
    private ScreenMaterial extraction;
    private ScreenMaterial blur;
    private ShaderMaterial.Uniform sourceStep;
    private ShaderMaterial.Uniform sampleOffset;

    public static int quarterDimension(int dimension) {
        return Math.max(1, (dimension + 3) / 4);
    }

    public static String getExtractFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform vec2 sourceStep;",
            "in vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",
            "vec3 bright(vec3 color) {",
                "float peak = max(color.r, max(color.g, color.b));",
                "float knee = clamp(peak - 0.80 + 0.10, 0.0, 0.20);",
                "knee = knee * knee / 0.40001;",
                "float amount = max(peak - 0.80, knee);",
                // Normalize the highlight range so white reaches 1 before the soft .16 blend.
                "return color * (min(amount * 5.0, 1.0) / max(peak, 0.00001));",
            "}",
            "void main() {",
                // Four bilinear samples cover the source's 4x4 footprint.
                "vec3 color = bright(texture(screenTexture, vUV + sourceStep * vec2(-1.0, -1.0)).rgb);",
                "color += bright(texture(screenTexture, vUV + sourceStep * vec2(1.0, -1.0)).rgb);",
                "color += bright(texture(screenTexture, vUV + sourceStep * vec2(-1.0, 1.0)).rgb);",
                "color += bright(texture(screenTexture, vUV + sourceStep * vec2(1.0, 1.0)).rgb);",
                "outFragColor = vec4(color * 0.25, 1.0);",
            "}");
    }

    public static String getBlurFragmentShader() {
        return String.join("\n",
            "precision mediump float;",
            "uniform sampler2D screenTexture;",
            "uniform vec2 sampleOffset;",
            "in vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",
            "void main() {",
                "vec3 color = texture(screenTexture, vUV - 2.0 * sampleOffset).rgb * 0.06136;",
                "color += texture(screenTexture, vUV - sampleOffset).rgb * 0.24477;",
                "color += texture(screenTexture, vUV).rgb * 0.38774;",
                "color += texture(screenTexture, vUV + sampleOffset).rgb * 0.24477;",
                "color += texture(screenTexture, vUV + 2.0 * sampleOffset).rgb * 0.06136;",
                "outFragColor = vec4(color, 1.0);",
            "}");
    }

    private void ensureContext() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(context)) return;
        // A new EGL context owns a different name space: never delete stale IDs.
        context = current;
        textures[0] = textures[1] = 0;
        framebuffers[0] = framebuffers[1] = 0;
        width = height = 0;
        allocationFailed = false;
        sourceStep = new ShaderMaterial.Uniform("sourceStep");
        sampleOffset = new ShaderMaterial.Uniform("sampleOffset");
        extraction = new ScreenMaterial() {
            @Override protected String getFragmentShader() { return getExtractFragmentShader(); }
        };
        blur = new ScreenMaterial() {
            @Override protected String getFragmentShader() { return getBlurFragmentShader(); }
        };
    }

    private boolean allocate(int sourceWidth, int sourceHeight) {
        int targetWidth = quarterDimension(sourceWidth);
        int targetHeight = quarterDimension(sourceHeight);
        if (width == targetWidth && height == targetHeight) return !allocationFailed;
        deleteTargets();
        width = targetWidth;
        height = targetHeight;
        GLES20.glGenTextures(2, textures, 0);
        GLES20.glGenFramebuffers(2, framebuffers, 0);
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
            // These private FBOs are standard RGBA8, independent of native BGRA input.
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textures[i], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                deleteTargets();
                allocationFailed = true;
                return false;
            }
        }
        allocationFailed = false;
        return true;
    }

    private void draw(GLRenderer renderer, ScreenMaterial material, int source, int target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target);
        material.use();
        renderer.quadVertices.bind(material.programId);
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
    }

    /** GL thread only. Restores the caller's framebuffer, viewport, texture and blend state. */
    public int render(GLRenderer renderer, int sourceTexture, int sourceWidth, int sourceHeight) {
        ensureContext();
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedValue, 0);
        int framebuffer = savedValue[0];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, savedValue, 0);
        int activeTexture = savedValue[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, savedValue, 0);
        int texture = savedValue[0];
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, savedValue, 0);
        int program = savedValue[0];
        boolean blending = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            if (!allocate(sourceWidth, sourceHeight)) return 0;
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glViewport(0, 0, width, height);
            extraction.use();
            extraction.setUniformVec2(sourceStep, 1.0f / sourceWidth, 1.0f / sourceHeight);
            draw(renderer, extraction, sourceTexture, framebuffers[0]);
            blur.use();
            blur.setUniformVec2(sampleOffset, 1.0f / width, 0.0f);
            draw(renderer, blur, textures[0], framebuffers[1]);
            blur.setUniformVec2(sampleOffset, 0.0f, 1.0f / height);
            draw(renderer, blur, textures[1], framebuffers[0]);
            return textures[0];
        } finally {
            if (blending) GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glActiveTexture(activeTexture);
            GLES20.glUseProgram(program);
        }
    }

    private void deleteTargets() {
        GLES20.glDeleteFramebuffers(2, framebuffers, 0);
        GLES20.glDeleteTextures(2, textures, 0);
        textures[0] = textures[1] = framebuffers[0] = framebuffers[1] = 0;
    }

    /** GL thread with a current context only; safe after activity pause/context loss. */
    public void release() {
        ensureContext();
        deleteTargets();
        width = height = 0;
        allocationFailed = false;
        extraction.destroy();
        blur.destroy();
        // Recreate uniforms too, whose locations belong to the deleted programs.
        context = null;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getStorageBytes() { return (long)width * height * 4 * 2; }
}
