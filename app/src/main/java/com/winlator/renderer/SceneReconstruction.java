package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial.Uniform;

import java.nio.ByteBuffer;

/** Shared GPU analysis and composition of real game frames. */
final class SceneReconstruction {
    private static final int PROBE_WIDTH = 8, PROBE_HEIGHT = 4;
    private final int[] linkStatus = new int[1];
    private final ByteBuffer pixels = ByteBuffer.allocateDirect(PROBE_WIDTH * PROBE_HEIGHT * 4);
    private EGLContext context;
    private RenderTarget reference, nextReference, structure, nextStructure, score, probe, smart, nextSmart;
    private Pass capture, recognize, estimate, reconstruct, measure, estimateLumen;
    private boolean lumenReady, highFailed;
    private int detailWidth, detailHeight;
    private int surfaceWidth, surfaceHeight, viewX, viewY, viewWidth, viewHeight, mapWidth, mapHeight;
    private long lastSource, lastAnalysis, lastProbe;
    private int analyses, frames;
    private boolean failed;

    private static final class Pass extends ScreenMaterial {
        private final String fragment;
        final Uniform reference = new Uniform("referenceTexture"), structure = new Uniform("structureTexture");
        final Uniform detailStep = new Uniform("detailStep");
        final Uniform smart = new Uniform("smartTexture");
        final Uniform score = new Uniform("scoreTexture"), origin = new Uniform("viewOrigin");
        final Uniform extent = new Uniform("viewExtent"), step = new Uniform("mapStep");
        final Uniform levels = new Uniform("levels"), phase = new Uniform("phase"), history = new Uniform("history");

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
        GLES20.glGetProgramiv(pass.programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) throw new IllegalStateException(GLES20.glGetProgramInfoLog(pass.programId));
    }

    private void allocate(RenderTarget target, int width, int height) {
        target.setFormat(GLES20.GL_RGBA);
        target.allocateFramebuffer(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.getFramebuffer());
        if (target.getFramebuffer() == 0
                || GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("reconstruction framebuffer " + width + "x" + height);
    }

    private void initialize() {
        int span = Math.max(viewWidth, viewHeight);
        mapWidth = Math.max(1, Math.round(192f * viewWidth / span));
        mapHeight = Math.max(1, Math.round(192f * viewHeight / span));
        reference = new RenderTarget(); nextReference = new RenderTarget();
        structure = new RenderTarget(); nextStructure = new RenderTarget();
        score = new RenderTarget(); probe = new RenderTarget();
        allocate(reference, mapWidth, mapHeight); allocate(nextReference, mapWidth, mapHeight);
        allocate(structure, mapWidth, mapHeight); allocate(nextStructure, mapWidth, mapHeight);
        allocate(score, 1, 1); allocate(probe, PROBE_WIDTH, PROBE_HEIGHT);
        capture = new Pass(SceneShaders.capture()); compile(capture);
        recognize = new Pass(SceneShaders.recognize()); compile(recognize);
        estimate = new Pass(SceneShaders.estimate()); compile(estimate);
        reconstruct = new Pass(SceneShaders.reconstruct()); compile(reconstruct);
        measure = new Pass(SceneShaders.probe()); compile(measure);
    }

    int depthTexture() { return structure.getTextureId(); }
    int confidenceTexture() { return score.getTextureId(); }

    boolean available(int width, int height) {
        return !failed || surfaceWidth != width || surfaceHeight != height
                || !EGL14.eglGetCurrentContext().equals(context);
    }

    private static void bind(int unit, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    }

    private void prepare(Pass pass, RenderTarget target, int width, int height,
                         int input, int ref, int map, int confidence) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.getFramebuffer());
        GLES20.glViewport(0, 0, width, height);
        pass.use();
        pass.setUniformVec2(pass.origin, (float)viewX/surfaceWidth, (float)viewY/surfaceHeight);
        pass.setUniformVec2(pass.extent, (float)viewWidth/surfaceWidth, (float)viewHeight/surfaceHeight);
        pass.setUniformVec2(pass.step, 1f/mapWidth, 1f/mapHeight);
        pass.setUniformInt(pass.uniforms.screenTexture, 0);
        pass.setUniformInt(pass.reference, 1);
        pass.setUniformInt(pass.structure, 2);
        pass.setUniformInt(pass.score, 3);
        bind(0, input); bind(1, ref); bind(2, map); bind(3, confidence);
    }

    private static void render() { GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3); }

    private void recognize(int input, boolean previous) {
        prepare(recognize, score, 1, 1, input, reference.getTextureId(), 0, 0);
        recognize.setUniformFloat(recognize.history, previous ? 1f : 0f);
        render();
    }

    private void prepareLumen() {
        estimateLumen = new Pass(SceneShaders.lumenMap());
        compile(estimateLumen);
        int span = Math.max(viewWidth, viewHeight);
        int limit = Math.min(320, span);
        detailWidth = Math.max(1, Math.round(limit * (float)viewWidth/span));
        detailHeight = Math.max(1, Math.round(limit * (float)viewHeight/span));
        smart = new RenderTarget(); nextSmart = new RenderTarget();
        allocate(smart, detailWidth, detailHeight); allocate(nextSmart, detailWidth, detailHeight);
    }

    private boolean analyzeLumen(GLRenderer renderer, int input) {
        if (highFailed) return false;
        try {
            if (estimateLumen == null) prepareLumen();
            prepare(estimateLumen, nextSmart, detailWidth, detailHeight, input,
                    0, structure.getTextureId(), score.getTextureId());
            estimateLumen.setUniformInt(estimateLumen.smart, 4);
            estimateLumen.setUniformFloat(estimateLumen.history, lumenReady ? 1f : 0f);
            estimateLumen.setUniformVec2(estimateLumen.detailStep, 1f/detailWidth, 1f/detailHeight);
            bind(4, smart.getTextureId());
            render();
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR)
                throw new IllegalStateException("reflection map glError=0x" + Integer.toHexString(error));
            RenderTarget oldSmart = smart;
            smart = nextSmart; nextSmart = oldSmart;
            lumenReady = true;
            return true;
        } catch (RuntimeException unavailable) {
            releaseLumen();
            for (int i = 0; i < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) {}
            highFailed = true;
            renderer.logGraphics("Lumen Ultra+ fallback to Ultra: " + unavailable.getMessage());
            return false;
        }
    }

    private void releaseLumen() {
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (smart != null) smart.destroy();
            if (nextSmart != null) nextSmart.destroy();
            if (estimateLumen != null) estimateLumen.destroy();
        }
        smart = nextSmart = null;
        estimateLumen = null;
        lumenReady = false;
    }

    private void analyze(int input) {
        prepare(capture, nextReference, mapWidth, mapHeight, input, 0, 0, 0);
        render();
        prepare(estimate, nextStructure, mapWidth, mapHeight, nextReference.getTextureId(),
                reference.getTextureId(), structure.getTextureId(), score.getTextureId());
        render();
        RenderTarget oldReference = reference, oldStructure = structure;
        reference = nextReference; structure = nextStructure;
        nextReference = oldReference; nextStructure = oldStructure;
        analyses++;
    }

    private void diagnose(GLRenderer renderer, int input, RenderTarget output, int genesis, int lumen) {
        prepare(measure, probe, PROBE_WIDTH, PROBE_HEIGHT, input, output.getTextureId(), 0, score.getTextureId());
        render();
        pixels.position(0);
        GLES20.glReadPixels(0, 0, PROBE_WIDTH, PROBE_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            renderer.logGraphics("Scene GPU probe failed glError=0x" + Integer.toHexString(error));
            return;
        }
        int changed = 0, maxDelta = 0;
        for (int i = 0; i < pixels.capacity(); i += 4) {
            int delta = pixels.get(i) & 255;
            if (delta > 1) changed++;
            maxDelta = Math.max(maxDelta, delta);
        }
        renderer.logGraphics("Scene GPU draw g=" + genesis + " l=" + lumen + " realFrames=" + frames
                + " analyses=" + analyses + " map=" + mapWidth + "x" + mapHeight
                + " confidence8=" + (pixels.get(1) & 255) + " cut8=" + (pixels.get(2) & 255)
                + " changedCells=" + changed + "/" + (PROBE_WIDTH * PROBE_HEIGHT) + " maxDelta8=" + maxDelta
                + " reflections=" + (lumen == 4 ? detailWidth + "x" + detailHeight : "off")
                + " program=" + reconstruct.programId);
    }

    boolean draw(int genesis, int lumen, GLRenderer renderer, int input, RenderTarget output,
                 int x, int y, int width, int height) {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(EGL14.EGL_NO_CONTEXT)) return false;
        if (!current.equals(context) || surfaceWidth != renderer.surfaceWidth || surfaceHeight != renderer.surfaceHeight
                || viewX != x || viewY != y || viewWidth != width || viewHeight != height) {
            release();
            context = current;
            surfaceWidth = renderer.surfaceWidth;
            surfaceHeight = renderer.surfaceHeight;
            viewX = x; viewY = y; viewWidth = width; viewHeight = height;
        }
        if (failed) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            if (capture == null) initialize();
            if (lumen < 4 && estimateLumen != null) releaseLumen();
            if (highFailed && lumen == 4) lumen = 3;
            long time = renderer.getSceneContentTime();
            boolean fresh = frames == 0 || time != lastSource;
            if (fresh) {
                boolean previous = analyses > 0;
                recognize(input, previous);
                int quality = Math.max(genesis, lumen);
                // Reuse maps on repeated draws; warm up sooner and update high tiers more frequently.
                long age = time - lastAnalysis;
                long interval = analyses < 3 ? 48_000_000L : 166_000_000L - quality * 26_000_000L;
                if (!previous || age >= interval || age < 0) {
                    analyze(input);
                    if (lumen == 4 && !analyzeLumen(renderer, input)) lumen = 3;
                    lastAnalysis = time;
                    if (!previous) recognize(input, true);
                }
                lastSource = time;
                frames++;
            }

            if (output == null) return true; // Adaptive Focus reuses analysis without moving or relighting pixels.
            if (lumen == 4 && !lumenReady && !analyzeLumen(renderer, input)) lumen = 3;
            prepare(reconstruct, output, surfaceWidth, surfaceHeight, input, reference.getTextureId(),
                    structure.getTextureId(), score.getTextureId());
            reconstruct.setUniformInt(reconstruct.smart, 4);
            bind(4, lumen == 4 ? smart.getTextureId() : 0);
            reconstruct.setUniformVec2(reconstruct.levels, genesis, lumen);
            reconstruct.setUniformFloat(reconstruct.phase,
                    (float)((time % 9_000_000_000L) * (2.0 * Math.PI / 9_000_000_000.0)));
            render();
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR)
                throw new IllegalStateException("reconstruction draw glError=0x" + Integer.toHexString(error));
            if (fresh && (frames == 1 || time - lastProbe >= 5_000_000_000L)) {
                lastProbe = time;
                diagnose(renderer, input, output, genesis, lumen);
            }
            return true;
        } catch (RuntimeException unavailable) {
            release();
            context = current;
            failed = true;
            renderer.logGraphics("Scene GPU bypassed: " + unavailable.getMessage());
            return false;
        } finally {
            for (int i = 4; i >= 0; i--) bind(i, 0);
            if (blend) GLES20.glEnable(GLES20.GL_BLEND);
            renderer.viewportNeedsUpdate = true;
        }
    }

    void release() {
        releaseLumen();
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            for (RenderTarget target : new RenderTarget[]{reference, nextReference, structure, nextStructure, score, probe})
                if (target != null) target.destroy();
            for (Pass pass : new Pass[]{capture, recognize, estimate, reconstruct, measure})
                if (pass != null) pass.destroy();
        }
        reference = nextReference = structure = nextStructure = score = probe = null;
        capture = recognize = estimate = reconstruct = measure = null;
        context = null;
        frames = analyses = 0;
        failed = highFailed = false;
    }
}
