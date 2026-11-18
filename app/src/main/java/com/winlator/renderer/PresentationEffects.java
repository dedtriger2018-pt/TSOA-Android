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
        final Uniform smoothing = new Uniform("smoothing");
        Pass(String shader) { this.shader = shader; }
        @Override protected String getFragmentShader() { return shader; }
    }

    private final int[] linkStatus = new int[1];
    private EGLContext context;
    private Pass histogram, cinematic, borders;
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

    boolean draw(GLRenderer r, RenderTarget source, int destination, int x, int y, int w, int h,
                 boolean cine, int quality, float amount, float first, float second) {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (!current.equals(context)) {
            release();
            context = current;
        }
        if (cine ? cinematicFailed : borderFailed) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            Pass pass;
            int stats = 0;
            if (cine) {
                if (histogram == null) {
                    histogram = new Pass(PresentationShaders.histogramShader()); compile(histogram);
                    cinematic = new Pass(PresentationShaders.cinematicShader()); compile(cinematic);
                    latest = new RenderTarget(); allocate(latest);
                    previous = new RenderTarget(); allocate(previous);
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
                stats = latest.getTextureId();
                pass = cinematic;
            } else {
                if (borders == null) { borders = new Pass(PresentationShaders.borderShader()); compile(borders); }
                pass = borders;
            }
            prepare(pass, r, destination, r.surfaceWidth, r.surfaceHeight, source.getTextureId(),
                    stats, x, y, w, h);
            pass.setUniformVec2(pass.grade, amount, quality);
            pass.setUniformVec2(pass.style, first, second);
            render(r);
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL 0x" + Integer.toHexString(error));
            return true;
        } catch (RuntimeException failure) {
            if (cine) cinematicFailed = true; else borderFailed = true;
            r.logGraphics((cine ? "Cinematic" : "Beyond Screen") + " bypassed: " + failure.getMessage());
            return false;
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            if (blend) GLES20.glEnable(GLES20.GL_BLEND);
            r.viewportNeedsUpdate = true;
        }
    }

    private static void render(GLRenderer renderer) {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
    }

    private static void allocate(RenderTarget target) {
        target.setFormat(GLES20.GL_RGBA);
        target.allocateFramebuffer(1, 1);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.getFramebuffer());
        if (target.getFramebuffer() == 0
                || GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("histogram framebuffer");
    }

    void release() {
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (histogram != null) histogram.destroy();
            if (cinematic != null) cinematic.destroy();
            if (borders != null) borders.destroy();
            if (latest != null) latest.destroy();
            if (previous != null) previous.destroy();
        }
        histogram = cinematic = borders = null;
        latest = previous = null;
        initialized = cinematicFailed = borderFailed = false;
        context = null;
    }
}
