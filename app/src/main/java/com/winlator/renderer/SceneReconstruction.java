package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.util.Log;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial.Uniform;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** One optional prepared scene, owned by the game GL thread. All recognition stays on the GPU. */
final class SceneReconstruction {
    private final int[] textures = new int[3];
    private final int[] scoreFramebuffer = new int[1];
    private final int[] status = new int[1];
    private SceneProfile profile;
    private EGLContext context;
    private Pass recognize, reconstruct;
    private boolean failed;
    private long lastRecognizedTime = Long.MIN_VALUE;
    private int lastX, lastY, lastWidth, lastHeight, surfaceWidth, surfaceHeight;

    private static final class Pass extends ScreenMaterial {
        private final String fragment;
        final Uniform reference = new Uniform("referenceTexture"), structure = new Uniform("structTexture");
        final Uniform score = new Uniform("scoreTexture"), origin = new Uniform("viewOrigin");
        final Uniform extent = new Uniform("viewExtent"), profileStep = new Uniform("profileStep");
        final Uniform levels = new Uniform("levels"), phase = new Uniform("phase");

        Pass(String fragment) { this.fragment = fragment; }

        @Override protected String getVertexShader() {
            return String.join("\n", "out vec2 vUV;", "void main() {",
                    "    vUV=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));",
                    "    gl_Position=vec4(vUV*2.0-1.0,0,1);", "}");
        }
        @Override protected String getFragmentShader() { return fragment; }
    }

    private void compile(Pass pass) {
        pass.use();
        GLES20.glGetProgramiv(pass.programId, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) throw new IllegalStateException(GLES20.glGetProgramInfoLog(pass.programId));
    }

    private static void bind(int unit, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    }

    private static void upload(int texture, int w, int h, ByteBuffer pixels, int filter) {
        bind(0, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
    }

    private void initialize(SceneProfile scene) {
        recognize = new Pass(SceneShaders.getRecognitionFragmentShader());
        reconstruct = new Pass(SceneShaders.getReconstructionFragmentShader());
        compile(recognize);
        compile(reconstruct);
        GLES20.glGenTextures(textures.length, textures, 0);
        if (textures[0] == 0 || textures[1] == 0 || textures[2] == 0) throw new IllegalStateException("texture allocation");
        // Both maps are bounded by the parser; Android accepts array-backed buffers for glTexImage2D.
        upload(textures[0], scene.width, scene.height, ByteBuffer.wrap(scene.reference), GLES20.GL_LINEAR);
        upload(textures[1], scene.width, scene.height, ByteBuffer.wrap(scene.structure), GLES20.GL_LINEAR);
        upload(textures[2], 1, 1, null, GLES20.GL_NEAREST);
        GLES20.glGenFramebuffers(1, scoreFramebuffer, 0);
        if (scoreFramebuffer[0] == 0) throw new IllegalStateException("framebuffer allocation");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scoreFramebuffer[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textures[2], 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE
                || GLES20.glGetError() != GLES20.GL_NO_ERROR)
            throw new IllegalStateException("profile upload or recognition framebuffer unavailable");
    }

    boolean available(SceneProfile requested, int w, int h) {
        return !failed || profile != requested || surfaceWidth != w || surfaceHeight != h
                || !EGL14.eglGetCurrentContext().equals(context);
    }

    private void bindInputs(Pass pass, int input, int x, int y, int w, int h) {
        pass.use();
        pass.setUniformVec2(pass.origin, (float)x/surfaceWidth, (float)y/surfaceHeight);
        pass.setUniformVec2(pass.extent, (float)w/surfaceWidth, (float)h/surfaceHeight);
        pass.setUniformInt(pass.uniforms.screenTexture, 0);
        pass.setUniformInt(pass.reference, 1);
        bind(0, input);
        bind(1, textures[0]);
    }

    boolean draw(SceneProfile scene, int genesis, int lumen, GLRenderer renderer, int input,
                 int outputFbo, int x, int y, int w, int h) {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(EGL14.EGL_NO_CONTEXT)) return false;
        boolean changed = !current.equals(context) || profile != scene
                || surfaceWidth != renderer.surfaceWidth || surfaceHeight != renderer.surfaceHeight;
        if (changed) {
            release();
            context = current;
            profile = scene;
            surfaceWidth = renderer.surfaceWidth;
            surfaceHeight = renderer.surfaceHeight;
        }
        if (failed) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            if (recognize == null) initialize(scene);
            long time = renderer.getSceneContentTime();
            if (time != lastRecognizedTime || x != lastX || y != lastY || w != lastWidth || h != lastHeight) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scoreFramebuffer[0]);
                GLES20.glViewport(0, 0, 1, 1);
                bindInputs(recognize, input, x, y, w, h);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
                lastRecognizedTime = time;
                lastX = x; lastY = y; lastWidth = w; lastHeight = h;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            bindInputs(reconstruct, input, x, y, w, h);
            reconstruct.setUniformVec2(reconstruct.profileStep, 1f/scene.width, 1f/scene.height);
            reconstruct.setUniformVec2(reconstruct.levels, genesis, lumen);
            float phase = (float)((time % 16_000_000_000L) * (2.0 * Math.PI / 16_000_000_000.0));
            reconstruct.setUniformFloat(reconstruct.phase, phase);
            reconstruct.setUniformInt(reconstruct.structure, 2);
            reconstruct.setUniformInt(reconstruct.score, 3);
            bind(2, textures[1]);
            bind(3, textures[2]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IllegalStateException("reconstruction draw failed");
            return true;
        } catch (RuntimeException unavailable) {
            deleteOwnedNames();
            failed = true;
            Log.w("TSOA_FE_DIAG", "Scene reconstruction bypassed: " + unavailable.getMessage());
            return false;
        } finally {
            for (int i = 3; i >= 0; i--) bind(i, 0);
            if (blend) GLES20.glEnable(GLES20.GL_BLEND);
            renderer.viewportNeedsUpdate = true;
        }
    }

    private void deleteOwnedNames() {
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            GLES20.glDeleteFramebuffers(1, scoreFramebuffer, 0);
            GLES20.glDeleteTextures(textures.length, textures, 0);
            if (recognize != null) recognize.destroy();
            if (reconstruct != null) reconstruct.destroy();
        }
        scoreFramebuffer[0] = 0;
        Arrays.fill(textures, 0);
        recognize = reconstruct = null;
    }

    void release() {
        deleteOwnedNames();
        context = null;
        profile = null;
        lastRecognizedTime = Long.MIN_VALUE;
        failed = false;
    }
}
