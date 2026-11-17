package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * Optional GLES 3 frame interpolation for two already processed, cursor-free frames.
 *
 * This is an original bounded block-matching implementation. It estimates forward
 * and backward optical flow at two resolutions, then reprojects both real frames.
 * Uncertain matches, occlusions, scene cuts and stationary UI use the current real
 * pixel. No CPU pixel readback, native driver integration, or external algorithm
 * implementation is used. All methods that touch GL must run on the render thread.
 */
public final class FrameInterpolation {
    private static final int FINE_PREVIOUS = 0, FINE_CURRENT = 1;
    private static final int COARSE_PREVIOUS = 2, COARSE_CURRENT = 3;
    private static final int COARSE_FORWARD = 4, COARSE_BACKWARD = 5;
    private static final int FINE_FORWARD = 6, FINE_BACKWARD = 7, SCENE = 8;
    private final int[] textures = new int[9];
    private final int[] framebuffers = new int[9];
    private final State state = new State();
    private EGLContext context;
    private int width, height, sourceWidth, sourceHeight;
    private int previousTexture, currentTexture;
    private boolean allocationFailed, ready;
    private Pass downsample, scene, flow, interpolate;

    /** At most quarter resolution, bounded to 320 x 180, including portrait input. */
    public static int flowWidth(int width, int height) {
        float scale = Math.max(4.0f, Math.max(width / 320.0f, height / 180.0f));
        return Math.max(1, (int)Math.ceil(width / scale));
    }

    public static int flowHeight(int width, int height) {
        float scale = Math.max(4.0f, Math.max(width / 320.0f, height / 180.0f));
        return Math.max(1, (int)Math.ceil(height / scale));
    }

    /** Shader strings omit #version, matching ScreenMaterial's GLES 3 contract. */
    public static String getVertexShader() {
        return String.join("\n",
            "out vec2 vUV;",
            "void main() {",
                "vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));",
                "vUV = p;",
                "gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
            "}");
    }

    public static String getDownsampleFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform vec2 sampleStep;",
            "in vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",
            "void main() {",
                "vec3 c = texture(screenTexture, vUV + sampleStep * vec2(-1.0, -1.0)).rgb;",
                "c += texture(screenTexture, vUV + sampleStep * vec2(1.0, -1.0)).rgb;",
                "c += texture(screenTexture, vUV + sampleStep * vec2(-1.0, 1.0)).rgb;",
                "c += texture(screenTexture, vUV + sampleStep * vec2(1.0, 1.0)).rgb;",
                "outFragColor = vec4(c * 0.25, 1.0);",
            "}");
    }

    /** One 1 x 1 GPU reduction. A large whole-frame change disables synthesis. */
    public static String getSceneFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D previousTexture;",
            "uniform sampler2D currentTexture;",
            "layout(location = 0) out vec4 outFragColor;",
            "void main() {",
                "float difference = 0.0;",
                "for (int y = 0; y < 4; ++y) {",
                    "for (int x = 0; x < 8; ++x) {",
                        "vec2 uv = (vec2(float(x), float(y)) + 0.5) / vec2(8.0, 4.0);",
                        "vec3 d = abs(texture(previousTexture, uv).rgb - texture(currentTexture, uv).rgb);",
                        "difference += dot(d, vec3(0.3333333));",
                    "}",
                "}",
                "outFragColor = vec4(step(0.22, difference / 32.0), 0.0, 0.0, 1.0);",
            "}");
    }

    /**
     * RG encodes signed displacement in this level's pixels: (value * 255 - 128) / 12.
     * B is match confidence, A is the unpenalized photometric error. The coarse
     * search tests a 5 x 5 grid at stride 2 then a 3 x 3 refinement; the fine level
     * refines the doubled coarse estimate with a 3 x 3 search. Maximum source
     * displacement is 32 pixels per axis. All loops have small static bounds.
     */
    public static String getFlowFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D referenceTexture;",
            "uniform sampler2D targetTexture;",
            "uniform sampler2D seedTexture;",
            "uniform sampler2D sceneTexture;",
            "uniform vec2 sourceStep;",
            "uniform vec2 flowLimit;",
            "uniform bool coarsePass;",
            "in vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",
            "vec3 r0 = vec3(0.0), r1 = vec3(0.0), r2 = vec3(0.0), r3 = vec3(0.0), r4 = vec3(0.0);",
            "float radius = 1.0;",
            "float colorError(vec3 a, vec3 b) { return dot(abs(a - b), vec3(0.3333333)); }",
            "float matchCost(vec2 motion) {",
                "vec2 uv = vUV + motion * sourceStep;",
                "float e = colorError(r0, texture(targetTexture, uv).rgb) * 0.4;",
                "e += colorError(r1, texture(targetTexture, uv + vec2(radius, 0.0) * sourceStep).rgb) * 0.15;",
                "e += colorError(r2, texture(targetTexture, uv - vec2(radius, 0.0) * sourceStep).rgb) * 0.15;",
                "e += colorError(r3, texture(targetTexture, uv + vec2(0.0, radius) * sourceStep).rgb) * 0.15;",
                "e += colorError(r4, texture(targetTexture, uv - vec2(0.0, radius) * sourceStep).rgb) * 0.15;",
                "return e;",
            "}",
            "void testMotion(vec2 candidate, inout vec2 best, inout float bestScore, inout float bestError) {",
                "candidate = clamp(candidate, -flowLimit, flowLimit);",
                "vec2 end = vUV + candidate * sourceStep;",
                "if (any(lessThan(end, vec2(0.0))) || any(greaterThan(end, vec2(1.0)))) return;",
                "float error = matchCost(candidate);",
                // A weak zero-motion prior resolves repeating or uniform patterns safely.
                "float score = error + length(candidate) * 0.0006;",
                "if (score < bestScore) { bestScore = score; bestError = error; best = candidate; }",
            "}",
            "void main() {",
                "if (texture(sceneTexture, vec2(0.5)).r > 0.5) { outFragColor = vec4(128.0 / 255.0, 128.0 / 255.0, 0.0, 1.0); return; }",
                "radius = coarsePass ? 1.0 : 2.0;",
                "r0 = texture(referenceTexture, vUV).rgb;",
                "r1 = texture(referenceTexture, vUV + vec2(radius, 0.0) * sourceStep).rgb;",
                "r2 = texture(referenceTexture, vUV - vec2(radius, 0.0) * sourceStep).rgb;",
                "r3 = texture(referenceTexture, vUV + vec2(0.0, radius) * sourceStep).rgb;",
                "r4 = texture(referenceTexture, vUV - vec2(0.0, radius) * sourceStep).rgb;",
                "float contrast = max(max(colorError(r0, r1), colorError(r0, r2)), max(colorError(r0, r3), colorError(r0, r4)));",
                "if (contrast < 0.006) { outFragColor = vec4(128.0 / 255.0, 128.0 / 255.0, 0.0, 0.0); return; }",
                "vec2 best = vec2(0.0);",
                "float bestError = matchCost(best);",
                "float bestScore = bestError;",
                "if (coarsePass) {",
                    "for (int y = -2; y <= 2; ++y) {",
                        "for (int x = -2; x <= 2; ++x) { testMotion(vec2(float(x), float(y)) * 2.0, best, bestScore, bestError); }",
                    "}",
                "} else {",
                    "vec2 seed = (texture(seedTexture, vUV).rg * 255.0 - 128.0) / 12.0 * 2.0;",
                    "testMotion(seed, best, bestScore, bestError);",
                    "vec2 base = seed;",
                    "for (int y = -1; y <= 1; ++y) {",
                        "for (int x = -1; x <= 1; ++x) { testMotion(base + vec2(float(x), float(y)), best, bestScore, bestError); }",
                    "}",
                "}",
                "if (coarsePass) {",
                    "vec2 base = best;",
                    "for (int y = -1; y <= 1; ++y) {",
                        "for (int x = -1; x <= 1; ++x) { testMotion(base + vec2(float(x), float(y)), best, bestScore, bestError); }",
                    "}",
                "}",
                "float confidence = (1.0 - smoothstep(0.025, 0.105, bestError)) * smoothstep(0.006, 0.04, contrast);",
                "outFragColor = vec4((best * 12.0 + 128.0) / 255.0, confidence, clamp(bestError, 0.0, 1.0));",
            "}");
    }

    public static String getInterpolateFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D previousTexture;",
            "uniform sampler2D currentTexture;",
            "uniform sampler2D forwardTexture;",
            "uniform sampler2D backwardTexture;",
            "uniform sampler2D sceneTexture;",
            "uniform vec2 flowStep;",
            "uniform float frameFraction;",
            "in vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",
            "vec2 motion(vec4 f) { return (f.rg * 255.0 - 128.0) / 12.0; }",
            "float error(vec3 a, vec3 b) { return dot(abs(a - b), vec3(0.3333333)); }",
            "bool inside(vec2 p) { return all(greaterThanEqual(p, vec2(0.0))) && all(lessThanEqual(p, vec2(1.0))); }",
            "void main() {",
                "vec4 current = texture(currentTexture, vUV);",
                "if (frameFraction >= 1.0) { outFragColor = current; return; }",
                "if (frameFraction <= 0.0) { outFragColor = texture(previousTexture, vUV); return; }",
                "vec4 previous = texture(previousTexture, vUV);",
                // Preserve stationary UI and unchanging scenery exactly, including alpha.
                "if (texture(sceneTexture, vec2(0.5)).r > 0.5 || error(previous.rgb, current.rgb) < 0.008) { outFragColor = current; return; }",
                "float chroma = max(current.r, max(current.g, current.b)) - min(current.r, min(current.g, current.b));",
                "if (chroma > 0.85 && error(previous.rgb, current.rgb) < 0.08) { outFragColor = current; return; }",
                "vec4 f = texture(forwardTexture, vUV);",
                "vec4 b = texture(backwardTexture, vUV);",
                "vec2 fm = motion(f);",
                "vec2 bm = motion(b);",
                "vec4 oppositeF = texture(backwardTexture, clamp(vUV + fm * flowStep, vec2(0.0), vec2(1.0)));",
                "vec4 oppositeB = texture(forwardTexture, clamp(vUV + bm * flowStep, vec2(0.0), vec2(1.0)));",
                "float fc = f.b * oppositeF.b * (1.0 - smoothstep(0.6, 1.8, length(fm + motion(oppositeF))));",
                "float bc = b.b * oppositeB.b * (1.0 - smoothstep(0.6, 1.8, length(bm + motion(oppositeB))));",
                // A moving surface supplies the hypothesis on either side of its silhouette.
                "float fs = fc * min(length(fm), 1.0);",
                "float bs = bc * min(length(bm), 1.0);",
                "vec2 velocity = fs >= bs ? fm : -bm;",
                "float confidence = max(fs, bs);",
                "vec2 p = vUV - frameFraction * velocity * flowStep;",
                "vec2 c = vUV + (1.0 - frameFraction) * velocity * flowStep;",
                "if (confidence < 0.18 || !inside(p) || !inside(c)) { outFragColor = current; return; }",
                "vec4 a = texture(previousTexture, p);",
                "vec4 z = texture(currentTexture, c);",
                // Rejected correspondences use a real pixel; never cross-fade failed flow.
                "if (error(a.rgb, z.rgb) > 0.09 || abs(a.a - z.a) > 0.15) { outFragColor = current; return; }",
                "outFragColor = mix(a, z, frameFraction);",
            "}");
    }

    private static final class Pass extends ScreenMaterial {
        private final String fragment;
        private final ShaderMaterial.Uniform[] uniforms;
        private boolean linked;
        Pass(String fragment, String... names) {
            this.fragment = fragment;
            uniforms = new ShaderMaterial.Uniform[names.length];
            for (int i = 0; i < names.length; i++) uniforms[i] = new ShaderMaterial.Uniform(names[i]);
        }
        @Override protected String getVertexShader() { return FrameInterpolation.getVertexShader(); }
        @Override protected String getFragmentShader() { return fragment; }
        @Override public void use() {
            super.use();
            if (!linked) {
                int[] status = new int[1];
                GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0);
                if (status[0] == 0) throw new IllegalStateException("Frame interpolation shader link failed: " + GLES20.glGetProgramInfoLog(programId));
                linked = true;
            }
        }
        void integer(int index, int value) { setUniformInt(uniforms[index], value); }
        void vector(int index, float x, float y) { setUniformVec2(uniforms[index], x, y); }
        void scalar(int index, float value) { setUniformFloat(uniforms[index], value); }
    }

    private boolean ensureContext() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(EGL14.EGL_NO_CONTEXT)) return false;
        if (current.equals(context)) return true;
        // Context-owned names must be forgotten, never deleted in another context.
        context = current;
        for (int i = 0; i < textures.length; i++) textures[i] = framebuffers[i] = 0;
        width = height = sourceWidth = sourceHeight = 0;
        ready = allocationFailed = false;
        downsample = new Pass(getDownsampleFragmentShader(), "screenTexture", "sampleStep");
        scene = new Pass(getSceneFragmentShader(), "previousTexture", "currentTexture");
        flow = new Pass(getFlowFragmentShader(), "referenceTexture", "targetTexture", "seedTexture", "sceneTexture", "sourceStep", "flowLimit", "coarsePass");
        interpolate = new Pass(getInterpolateFragmentShader(), "previousTexture", "currentTexture", "forwardTexture", "backwardTexture", "sceneTexture", "flowStep", "frameFraction");
        return true;
    }

    private boolean allocate(int w, int h) {
        int fw = flowWidth(w, h), fh = flowHeight(w, h);
        if (sourceWidth == w && sourceHeight == h && width == fw && height == fh) return !allocationFailed;
        deleteTargets();
        sourceWidth = w; sourceHeight = h; width = fw; height = fh;
        GLES20.glGenTextures(textures.length, textures, 0);
        GLES20.glGenFramebuffers(framebuffers.length, framebuffers, 0);
        for (int i = 0; i < textures.length; i++) {
            int tw = i == SCENE ? 1 : i >= COARSE_PREVIOUS && i <= COARSE_BACKWARD ? Math.max(1, (width + 1) / 2) : width;
            int th = i == SCENE ? 1 : i >= COARSE_PREVIOUS && i <= COARSE_BACKWARD ? Math.max(1, (height + 1) / 2) : height;
            bind(0, textures[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tw, th, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0);
            if (textures[i] == 0 || framebuffers[i] == 0 || GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                deleteTargets();
                allocationFailed = true;
                return false;
            }
        }
        allocationFailed = false;
        return true;
    }

    private static void bind(int unit, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    }

    private void target(int index, int w, int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index]);
        GLES20.glViewport(0, 0, w, h);
    }

    private static void triangle() { GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3); }

    private void reduce(int source, int destination, int w, int h) {
        target(destination, w, h);
        downsample.use();
        downsample.integer(0, 0);
        downsample.vector(1, 0.25f / w, 0.25f / h);
        bind(0, source);
        triangle();
    }

    private void estimate(int reference, int destination, int seed, int output, int w, int h, boolean coarse) {
        target(output, w, h);
        flow.use();
        for (int i = 0; i < 4; i++) flow.integer(i, i);
        flow.vector(4, 1.0f / w, 1.0f / h);
        flow.vector(5, Math.min(coarse ? 4.0f : 8.0f, 32.0f * w / sourceWidth), Math.min(coarse ? 4.0f : 8.0f, 32.0f * h / sourceHeight));
        flow.integer(6, coarse ? 1 : 0);
        bind(0, textures[reference]); bind(1, textures[destination]);
        // A separate, non-attached texture also avoids feedback on the coarse pass.
        bind(2, textures[seed]); bind(3, textures[SCENE]);
        triangle();
    }

    /**
     * Build flow exactly once for a new real-frame pair. Texture IDs may be reused
     * by the caller: every call recomputes their contents. draw() reuses this flow.
     * Returns false on unsupported allocation/programs so the caller can copy current.
     */
    public boolean prepare(int previousTex, int currentTex, int w, int h) {
        ready = false;
        if (previousTex == 0 || currentTex == 0 || w <= 0 || h <= 0 || !ensureContext()) return false;
        state.save();
        try {
            state.makeWritable();
            if (!allocate(w, h)) return false;
            int cw = Math.max(1, (width + 1) / 2), ch = Math.max(1, (height + 1) / 2);
            reduce(previousTex, FINE_PREVIOUS, width, height);
            reduce(currentTex, FINE_CURRENT, width, height);
            reduce(textures[FINE_PREVIOUS], COARSE_PREVIOUS, cw, ch);
            reduce(textures[FINE_CURRENT], COARSE_CURRENT, cw, ch);
            target(SCENE, 1, 1);
            scene.use(); scene.integer(0, 0); scene.integer(1, 1);
            bind(0, textures[COARSE_PREVIOUS]); bind(1, textures[COARSE_CURRENT]); triangle();
            estimate(COARSE_PREVIOUS, COARSE_CURRENT, COARSE_PREVIOUS, COARSE_FORWARD, cw, ch, true);
            estimate(COARSE_CURRENT, COARSE_PREVIOUS, COARSE_CURRENT, COARSE_BACKWARD, cw, ch, true);
            estimate(FINE_PREVIOUS, FINE_CURRENT, COARSE_FORWARD, FINE_FORWARD, width, height, false);
            estimate(FINE_CURRENT, FINE_PREVIOUS, COARSE_BACKWARD, FINE_BACKWARD, width, height, false);
            previousTexture = previousTex; currentTexture = currentTex;
            ready = true;
            return true;
        } catch (RuntimeException unavailable) {
            allocationFailed = true;
            ready = false;
            return false;
        } finally { state.restore(); }
    }

    /** Draw to the caller's currently bound framebuffer; no framebuffer 0 assumption. */
    public boolean draw(int previousTex, int currentTex, int w, int h, float fraction) {
        if (previousTex == 0 || currentTex == 0 || w <= 0 || h <= 0 || Float.isNaN(fraction) || !ensureContext()) return false;
        float t = Math.max(0.0f, Math.min(1.0f, fraction));
        if (t > 0.0f && t < 1.0f && (!ready || previousTexture != previousTex || currentTexture != currentTex || sourceWidth != w || sourceHeight != h)) return false;
        state.save();
        try {
            state.makeWritable();
            GLES20.glViewport(0, 0, w, h);
            interpolate.use();
            for (int i = 0; i < 5; i++) interpolate.integer(i, i);
            interpolate.vector(5, 1.0f / Math.max(1, width), 1.0f / Math.max(1, height));
            interpolate.scalar(6, t);
            bind(0, previousTex); bind(1, currentTex);
            bind(2, textures[FINE_FORWARD]); bind(3, textures[FINE_BACKWARD]); bind(4, textures[SCENE]);
            triangle();
            return true;
        } catch (RuntimeException unavailable) {
            ready = false;
            return false;
        } finally { state.restore(); }
    }

    /** Invalidate history without any GL work or allocation. */
    public void invalidate() { ready = false; previousTexture = currentTexture = 0; }

    private void deleteTargets() {
        GLES20.glDeleteFramebuffers(framebuffers.length, framebuffers, 0);
        GLES20.glDeleteTextures(textures.length, textures, 0);
        for (int i = 0; i < textures.length; i++) textures[i] = framebuffers[i] = 0;
        ready = false;
    }

    /** Delete only names owned by the current EGL context, and allocate nothing. */
    public void release() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (context != null && context.equals(current) && !current.equals(EGL14.EGL_NO_CONTEXT)) {
            deleteTargets();
            if (downsample != null) downsample.destroy();
            if (scene != null) scene.destroy();
            if (flow != null) flow.destroy();
            if (interpolate != null) interpolate.destroy();
        }
        for (int i = 0; i < textures.length; i++) textures[i] = framebuffers[i] = 0;
        context = null;
        downsample = scene = flow = interpolate = null;
        width = height = sourceWidth = sourceHeight = 0;
        ready = allocationFailed = false;
        previousTexture = currentTexture = 0;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getStorageBytes() {
        if (textures[0] == 0) return 0;
        return 4L * (4L * width * height + 4L * ((width + 1) / 2) * ((height + 1) / 2) + 1L);
    }

    /** Only GL state modified by this helper. Vertex attributes/VAOs are untouched. */
    private static final class State {
        private final int[] value = new int[1], viewport = new int[4], bindings = new int[5];
        private final boolean[] colorMask = new boolean[4];
        private final int[] capabilities = { GLES20.GL_BLEND, GLES20.GL_DEPTH_TEST,
                GLES20.GL_STENCIL_TEST, GLES20.GL_SCISSOR_TEST, GLES20.GL_CULL_FACE,
                GLES20.GL_DITHER, GLES20.GL_SAMPLE_ALPHA_TO_COVERAGE,
                GLES20.GL_SAMPLE_COVERAGE, GLES30.GL_RASTERIZER_DISCARD };
        private final boolean[] enabled = new boolean[capabilities.length];
        private int drawFramebuffer, readFramebuffer, activeTexture, program;
        void save() {
            GLES20.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, value, 0); drawFramebuffer = value[0];
            GLES20.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, value, 0); readFramebuffer = value[0];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
            GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, value, 0); activeTexture = value[0];
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, value, 0); program = value[0];
            GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, colorMask, 0);
            for (int i = 0; i < bindings.length; i++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, value, 0); bindings[i] = value[0];
            }
            for (int i = 0; i < capabilities.length; i++) enabled[i] = GLES20.glIsEnabled(capabilities[i]);
        }
        void makeWritable() {
            for (int capability : capabilities) GLES20.glDisable(capability);
            GLES20.glColorMask(true, true, true, true);
        }
        void restore() {
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            for (int i = 0; i < bindings.length; i++) bind(i, bindings[i]);
            GLES20.glActiveTexture(activeTexture);
            GLES20.glUseProgram(program);
            GLES20.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            for (int i = 0; i < capabilities.length; i++) {
                if (enabled[i]) GLES20.glEnable(capabilities[i]); else GLES20.glDisable(capabilities[i]);
            }
        }
    }
}
