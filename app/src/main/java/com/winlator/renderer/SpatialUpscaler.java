package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/** Captures the guest before display scaling, then runs FSR1 EASU into the compositor. */
public final class SpatialUpscaler {
    private EGLContext context;
    private RenderTarget source;
    private ScreenMaterial easu;
    private ShaderMaterial.Uniform inputSize;
    private int width, height;
    private boolean failed;

    public static boolean canUpscale(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        return inputWidth > 0 && inputHeight > 0
                && outputWidth > inputWidth && outputHeight > inputHeight;
    }

    private void ensureContext() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(context)) return;
        // GL object names from a lost context must never be deleted in its successor.
        context = current;
        source = null;
        width = height = 0;
        failed = false;
        inputSize = new ShaderMaterial.Uniform("inputSize");
        easu = new ScreenMaterial() {
            @Override protected String getFragmentShader() { return SpatialShaders.getEasuFragmentShader(); }
        };
    }

    private boolean allocate(int inputWidth, int inputHeight) {
        if (width == inputWidth && height == inputHeight) return !failed;
        if (source != null) source.destroy();
        width = inputWidth;
        height = inputHeight;
        source = new RenderTarget();
        source.setFormat(GLES20.GL_RGBA);
        source.allocateFramebuffer(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, source.getFramebuffer());
        failed = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE;
        if (failed) { source.destroy(); source = null; }
        return !failed;
    }

    /** GL thread only. Output contains the same bars/aspect mapping as the normal game draw. */
    public boolean render(GLRenderer renderer, RenderTarget output, int x, int y, int outWidth, int outHeight) {
        int inWidth = renderer.getGameWidth(), inHeight = renderer.getGameHeight();
        if (!canUpscale(inWidth, inHeight, outWidth, outHeight)) return false;
        ensureContext();
        boolean blending = GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            if (!allocate(inWidth, inHeight)) return false;
            // Capture real guest pixels: EASU never receives the already bilinear-scaled display.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, source.getFramebuffer());
            renderer.drawNativeFrame();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.getFramebuffer());
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(x, y, outWidth, outHeight);
            GLES20.glDisable(GLES20.GL_BLEND);
            easu.use();
            easu.setUniformVec2(inputSize, inWidth, inHeight);
            easu.setUniformVec2(easu.uniforms.resolution, outWidth, outHeight);
            easu.setUniformInt(easu.uniforms.screenTexture, 0);
            renderer.quadVertices.bind(easu.programId);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.getTextureId());
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            return true;
        } finally {
            if (blending) GLES20.glEnable(GLES20.GL_BLEND);
            renderer.viewportNeedsUpdate = true;
        }
    }

    /** Current context only, including a deferred disable after resume. */
    public void release() {
        ensureContext();
        if (source != null) source.destroy();
        if (easu != null) easu.destroy();
        source = null;
        context = null;
        width = height = 0;
    }
}
