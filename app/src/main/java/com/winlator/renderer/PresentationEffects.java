package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial.Uniform;

/** Game-space color adaptation and display-space ambient borders. GL-thread owned. */
final class PresentationEffects {
    private static class Pass extends ScreenMaterial {
        final String shader;
        final Uniform statistics = new Uniform("statistics"), origin = new Uniform("origin");
        final Uniform extent = new Uniform("extent"), grade = new Uniform("grade"), style = new Uniform("style");
        final Uniform stepUV = new Uniform("stepUV");
        final Uniform smoothing = new Uniform("smoothing");
        Pass(String shader) { this.shader = shader; }
        @Override protected String getFragmentShader() { return shader; }
    }

    private final int[] linkStatus = new int[1];
    private EGLContext context;
    private Pass histogram, cinematic, borders, ambientCapture, ambientGaussian;
    private RenderTarget ambient, ambientScratch;
    private int ambientWidth, ambientHeight, ambientX, ambientY, ambientViewWidth, ambientViewHeight;
    private long ambientTime, ambientRevision;
    private float ambientBlur;
    private boolean ambientReady;
    private RenderTarget latest, previous;
    private int lastX, lastY, lastWidth, lastHeight;
    private long lastAnalysis;
    private boolean initialized, cinematicFailed, borderFailed;

    private void compile(Pass pass) {
        pass.use();
        GLES20.glGetProgramiv(pass.programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) throw new IllegalStateException("program link");
    }

    private void prepare(Pass pass, GLRenderer renderer, int framebuffer, int width, int height,
                         int input, int stats, int x, int y, int w, int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
        pass.use();
        renderer.quadVertices.bind(pass.programId);
        pass.setUniformVec2(pass.origin, x/(float)renderer.surfaceWidth, y/(float)renderer.surfaceHeight);
        pass.setUniformVec2(pass.extent, w/(float)renderer.surfaceWidth, h/(float)renderer.surfaceHeight);
        pass.setUniformInt(pass.uniforms.screenTexture, 0);
        pass.setUniformInt(pass.statistics, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stats);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input);
    }

    private void useContext() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (!current.equals(context)) {
            release();
            context = current;
        }
    }

    boolean draw(GLRenderer r, RenderTarget source, int destination, int x, int y, int w, int h,
                 int quality, float amount, float first, float second) {
        useContext();
        if (cinematicFailed) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            if (histogram == null) {
                histogram = new Pass(PresentationShaders.histogramShader()); compile(histogram);
                cinematic = new Pass(PresentationShaders.cinematicShader()); compile(cinematic);
                latest = new RenderTarget(); allocate(latest, 1, 1);
                previous = new RenderTarget(); allocate(previous, 1, 1);
            }
            if (lastX != x || lastY != y || lastWidth != w || lastHeight != h) initialized = false;
            lastX = x; lastY = y; lastWidth = w; lastHeight = h;
            long now = r.getSceneContentTime();
            long elapsed = now - lastAnalysis;
            if (!initialized || elapsed >= (5 - quality) * 65_000_000L || elapsed < 0) {
                prepare(histogram, r, previous.getFramebuffer(), 1, 1, source.getTextureId(),
                        initialized ? latest.getTextureId() : 0, x, y, w, h);
                histogram.setUniformFloat(histogram.smoothing, initialized
                        ? (float)Math.exp(-Math.max(0, elapsed) / 600_000_000.0) : 0);
                render(r);
                RenderTarget old = latest; latest = previous; previous = old;
                lastAnalysis = now;
                initialized = true;
            }
            prepare(cinematic, r, destination, r.surfaceWidth, r.surfaceHeight, source.getTextureId(),
                    latest.getTextureId(), x, y, w, h);
            cinematic.setUniformVec2(cinematic.grade, amount, quality);
            cinematic.setUniformVec2(cinematic.style, first, second);
            render(r);
            checkDraw();
            return true;
        } catch (RuntimeException failure) {
            cinematicFailed = true;
            r.logGraphics("Cinematic bypassed: " + failure.getMessage());
            return false;
        } finally { restoreState(r, blend); }
    }

    boolean drawBorders(GLRenderer r, RenderTarget source, int destination, int x, int y,
                        int w, int h, int quality, long revision,
                        float amount, float blur, float brightness, float saturation) {
        useContext();
        if (borderFailed) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            if (borders == null) { borders = new Pass(PresentationShaders.borderShader()); compile(borders); }
            int texture = 0;
            if (amount > 0 && brightness > 0) {
                if (ambientCapture == null) {
                    ambientCapture = new Pass(PresentationShaders.ambientCaptureShader()); compile(ambientCapture);
                    ambientGaussian = new Pass(PresentationShaders.ambientGaussianShader()); compile(ambientGaussian);
                }
                int span = 64 + quality * 16;
                int aw = Math.max(1, Math.round(span * (float)w / Math.max(w,h)));
                int ah = Math.max(1, Math.round(span * (float)h / Math.max(w,h)));
                if (ambient == null || aw != ambientWidth || ah != ambientHeight) {
                    releaseAmbientTargets();
                    ambient = new RenderTarget(); allocate(ambient, aw, ah);
                    ambientScratch = new RenderTarget(); allocate(ambientScratch, aw, ah);
                    ambientWidth = aw; ambientHeight = ah;
                }
                long time = r.getSceneContentTime();
                if (!ambientReady || time != ambientTime || revision != ambientRevision || blur != ambientBlur
                        || x != ambientX || y != ambientY || w != ambientViewWidth || h != ambientViewHeight) {
                    prepare(ambientCapture, r, ambient.getFramebuffer(), aw, ah, source.getTextureId(), 0, x, y, w, h);
                    ambientCapture.setUniformVec2(ambientCapture.stepUV, 0.31f/aw, 0.31f/ah);
                    render(r);
                    float sigma = 2.35f + 3.4f * blur;
                    prepare(ambientGaussian, r, ambientScratch.getFramebuffer(), aw, ah, ambient.getTextureId(), 0, x, y, w, h);
                    ambientGaussian.setUniformVec2(ambientGaussian.stepUV, 1f/aw, 0f);
                    ambientGaussian.setUniformFloat(ambientGaussian.smoothing, sigma);
                    render(r);
                    prepare(ambientGaussian, r, ambient.getFramebuffer(), aw, ah, ambientScratch.getTextureId(), 0, x, y, w, h);
                    ambientGaussian.setUniformVec2(ambientGaussian.stepUV, 0f, 1f/ah);
                    ambientGaussian.setUniformFloat(ambientGaussian.smoothing, sigma);
                    render(r);
                    ambientTime = time; ambientRevision = revision; ambientBlur = blur;
                    ambientX = x; ambientY = y; ambientViewWidth = w; ambientViewHeight = h;
                    ambientReady = true;
                }
                texture = ambient.getTextureId();
            } else releaseAmbientTargets();
            prepare(borders, r, destination, r.surfaceWidth, r.surfaceHeight, source.getTextureId(), texture, x, y, w, h);
            borders.setUniformVec2(borders.grade, amount, 0);
            borders.setUniformVec2(borders.style, brightness, saturation);
            render(r);
            checkDraw();
            return true;
        } catch (RuntimeException failure) {
            borderFailed = true;
            releaseAmbientTargets();
            r.logGraphics("Beyond Screen bypassed: " + failure.getMessage());
            return false;
        } finally { restoreState(r, blend); }
    }

    private static void checkDraw() {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL 0x" + Integer.toHexString(error));
    }

    private static void restoreState(GLRenderer renderer, boolean blend) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        if (blend) GLES20.glEnable(GLES20.GL_BLEND);
        renderer.viewportNeedsUpdate = true;
    }

    private static void render(GLRenderer renderer) {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
    }

    private static void allocate(RenderTarget target, int width, int height) {
        target.setFormat(GLES20.GL_RGBA);
        target.allocateFramebuffer(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.getFramebuffer());
        if (target.getFramebuffer() == 0
                || GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("presentation framebuffer");
    }

    private void releaseAmbientTargets() {
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (ambient != null) ambient.destroy();
            if (ambientScratch != null) ambientScratch.destroy();
        }
        ambient = ambientScratch = null;
        ambientReady = false;
    }

    void release() {
        releaseAmbientTargets();
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (histogram != null) histogram.destroy();
            if (cinematic != null) cinematic.destroy();
            if (borders != null) borders.destroy();
            if (ambientCapture != null) ambientCapture.destroy();
            if (ambientGaussian != null) ambientGaussian.destroy();
            if (latest != null) latest.destroy();
            if (previous != null) previous.destroy();
        }
        histogram = cinematic = borders = ambientCapture = ambientGaussian = null;
        latest = previous = null;
        initialized = cinematicFailed = borderFailed = false;
        context = null;
    }
}
