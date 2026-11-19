package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial.Uniform;

import java.nio.ByteBuffer;

/** Lens highlights share one reduced atlas. The final pass preserves the existing borders. */
final class OpticalEffects {
    static final int SKIPPED = 0, PRESENTED = 1, NEEDS_COPY = 2;
    private static final int PROBE_WIDTH = 10, PROBE_HEIGHT = 6;
    private final ByteBuffer probeData = ByteBuffer.allocateDirect(PROBE_WIDTH * PROBE_HEIGHT * 4);
    private final int[] status = new int[1];
    private EGLContext context;
    private Pass capture, horizontal, vertical, composite, diagnose;
    private RenderTarget atlas, scratch, probe;
    private int aw, ah, lastX, lastY, lastWidth, lastHeight, lastSurfaceWidth, lastSurfaceHeight;
    private long captureTime, revision, probeTime, probeRevision = Long.MIN_VALUE;
    private boolean captured, failed;
    private int x, y, width, height;

    private static final class Pass extends ScreenMaterial {
        final String shader;
        final Uniform auxiliary = new Uniform("auxiliary"), sceneMap = new Uniform("sceneMap"), sceneScore = new Uniform("sceneScore");
        final Uniform origin = new Uniform("origin"), extent = new Uniform("extent"), stepUV = new Uniform("stepUV");
        final Uniform aperture = new Uniform("aperture"), grade = new Uniform("grade"), lightMix = new Uniform("lightMix");
        final Uniform focusVignette = new Uniform("focusVignette"), grainTuning = new Uniform("grainTuning");
        final Uniform grainAmount = new Uniform("grainAmount"), hasBloom = new Uniform("hasBloom");
        Pass(String shader) { this.shader = shader; }
        @Override protected String getFragmentShader() { return shader; }
    }

    private Pass create(String fragment) {
        Pass pass = new Pass(fragment);
        pass.use();
        GLES20.glGetProgramiv(pass.programId, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == GLES20.GL_TRUE) return pass;
        pass.destroy();
        throw new IllegalStateException("optics program link");
    }

    private static void bind(int unit, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    }

    private void prepare(GLRenderer r, Pass pass, int framebuffer, int w, int h, int input, int auxiliary) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, w, h);
        pass.use();
        r.quadVertices.bind(pass.programId);
        pass.setUniformVec2(pass.origin, x/(float)r.surfaceWidth, y/(float)r.surfaceHeight);
        pass.setUniformVec2(pass.extent, width/(float)r.surfaceWidth, height/(float)r.surfaceHeight);
        pass.setUniformInt(pass.uniforms.screenTexture, 0);
        pass.setUniformInt(pass.auxiliary, 1);
        bind(0,input); bind(1,auxiliary);
    }

    private static void drawQuad(GLRenderer r) {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, r.quadVertices.count());
    }

    private void allocate(RenderTarget target, int w, int h) {
        target.setFormat(GLES20.GL_RGBA);
        target.allocateFramebuffer(w,h);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.getFramebuffer());
        if (target.getFramebuffer() == 0 || GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("optics framebuffer");
    }

    private void highlights(GLRenderer r, int input, int bloom, int quality, CinematicSettings settings, long now, long visualRevision) {
        if (capture == null) {
            capture = create(OpticalShaders.capture());
            horizontal = create(OpticalShaders.spread(true));
            vertical = create(OpticalShaders.spread(false));
        }
        int span = 96 + quality * 32, edge = Math.max(width,height);
        int w = Math.max(1, Math.round(span * (float)width/edge));
        int h = Math.max(1, Math.round(span * (float)height/edge));
        if (atlas == null || aw != w || ah != h) {
            releaseAtlas();
            atlas = new RenderTarget(); allocate(atlas,w,h);
            scratch = new RenderTarget(); allocate(scratch,w,h);
            aw=w; ah=h;
        }
        if (captured && captureTime == now && revision == visualRevision
                && x == lastX && y == lastY && width == lastWidth && height == lastHeight
                && r.surfaceWidth == lastSurfaceWidth && r.surfaceHeight == lastSurfaceHeight) return;
        prepare(r,capture,atlas.getFramebuffer(),w,h,input,bloom);
        capture.setUniformVec2(capture.stepUV,1f/w,1f/h);
        capture.setUniformFloat(capture.hasBloom,bloom == 0 ? 0f : 1f);
        drawQuad(r);
        float spread = 0.75f + 0.35f*quality;
        prepare(r,horizontal,scratch.getFramebuffer(),w,h,atlas.getTextureId(),0);
        horizontal.setUniformVec2(horizontal.grade,1f,quality);
        horizontal.setUniformVec2(horizontal.lightMix,settings.anamorphic,settings.halation);
        horizontal.setUniformVec2(horizontal.stepUV,spread/w,0f);
        drawQuad(r);
        prepare(r,vertical,atlas.getFramebuffer(),w,h,scratch.getTextureId(),0);
        vertical.setUniformVec2(vertical.stepUV,0f,spread/h);
        vertical.setUniformVec2(vertical.lightMix,settings.anamorphic,settings.halation);
        drawQuad(r);
        captured=true;
        captureTime=now; revision=visualRevision;
        lastX=x; lastY=y; lastWidth=width; lastHeight=height;
        lastSurfaceWidth=r.surfaceWidth; lastSurfaceHeight=r.surfaceHeight;
    }

    int draw(GLRenderer r, RenderTarget source, RenderTarget alternate, int destination,
             int x, int y, int width, int height, CinematicSettings settings, int quality,
             int depth, int score, int bloom, long visualRevision) {
        EGLContext current=EGL14.eglGetCurrentContext();
        if (!current.equals(context)) {
            release();
            context=current;
        }
        if (failed) return SKIPPED;
        this.x=x; this.y=y; this.width=width; this.height=height;
        long now=r.getSceneContentTime();
        boolean measure=probeRevision != visualRevision || now-probeTime >= 5_000_000_000L;
        boolean blend=GLES20.glIsEnabled(GLES20.GL_BLEND);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            if (composite == null) composite=create(OpticalShaders.composite());
            if (settings.anamorphic > 0 || settings.halation > 0)
                highlights(r,source.getTextureId(),bloom,quality,settings,now,visualRevision);
            int target=measure ? alternate.getFramebuffer() : destination;
            prepare(r,composite,target,r.surfaceWidth,r.surfaceHeight,
                    source.getTextureId(),atlas == null ? 0 : atlas.getTextureId());
            composite.setUniformInt(composite.sceneMap,2);
            composite.setUniformInt(composite.sceneScore,3);
            bind(2,depth); bind(3,score);
            composite.setUniformVec2(composite.grade,settings.amount/100f,quality);
            composite.setUniformVec2(composite.lightMix,settings.anamorphic/100f,settings.halation/100f);
            composite.setUniformVec2(composite.focusVignette,depth == 0 || score == 0 ? 0f : settings.focus/100f,
                    settings.vignette/100f);
            composite.setUniformFloat(composite.grainAmount,settings.grain/100f);
            composite.setUniformVec2(composite.grainTuning,0.8f+settings.grainSize*0.030f,settings.grainResponse/100f);
            float ratio=width/(float)height, standard=16f/9f;
            // Preserve the entire fitted viewport for ordinary 16:9 rounding.
            boolean fitted=Math.abs(ratio-standard)<0.025f;
            composite.setUniformVec2(composite.aperture,fitted ? 1f : Math.min(1f,standard/ratio),
                    fitted ? 1f : Math.min(1f,ratio/standard));
            composite.setUniformFloat(composite.hasBloom,bloom == 0 ? 0f : 1f);
            drawQuad(r);
            int error=GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("optics glError=0x"+Integer.toHexString(error));
            if (!measure) return PRESENTED;
            sample(r,source.getTextureId(),alternate.getTextureId(),settings,quality,depth);
            probeTime=now; probeRevision=visualRevision;
            return NEEDS_COPY;
        } catch (RuntimeException error) {
            r.logGraphics("Optics bypassed: " + error.getMessage());
            release();
            context=current;
            failed=true;
            return SKIPPED;
        } finally {
            for (int i=3;i>=0;i--) bind(i,0);
            if (blend) GLES20.glEnable(GLES20.GL_BLEND);
            r.viewportNeedsUpdate=true;
        }
    }

    private void sample(GLRenderer r, int before, int after, CinematicSettings settings, int quality, int depth) {
        try {
            if (diagnose == null) {
                diagnose=create(OpticalShaders.probe());
                probe=new RenderTarget(); allocate(probe,PROBE_WIDTH,PROBE_HEIGHT);
            }
            prepare(r,diagnose,probe.getFramebuffer(),PROBE_WIDTH,PROBE_HEIGHT,before,after);
            drawQuad(r);
            probeData.position(0);
            GLES20.glReadPixels(0,0,PROBE_WIDTH,PROBE_HEIGHT,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,probeData);
            int error=GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("readback 0x"+Integer.toHexString(error));
            int max=0, changed=0;
            for (int i=0;i<probeData.capacity();i+=4) {
                int delta=probeData.get(i)&255;
                max=Math.max(max,delta);
                if (delta>1) changed++;
            }
            r.logGraphics("Optics GPU draw p=" + quality + " amount=" + settings.amount
                    + " lens=" + settings.anamorphic + " focus=" + settings.focus + " depth=" + (depth!=0)
                    + " halation=" + settings.halation + " grain=" + settings.grain
                    + " vignette=" + settings.vignette + " changedCells=" + changed + "/" + (PROBE_WIDTH*PROBE_HEIGHT)
                    + " maxDelta8=" + max + " program=" + composite.programId);
        } catch (RuntimeException error) { r.logGraphics("Optics GPU probe unavailable: " + error.getMessage()); }
    }

    private void releaseAtlas() {
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (atlas != null) atlas.destroy();
            if (scratch != null) scratch.destroy();
        }
        atlas=scratch=null;
        captured=false;
    }

    void release() {
        releaseAtlas();
        if (context != null && context.equals(EGL14.eglGetCurrentContext())) {
            if (probe != null) probe.destroy();
            for (Pass pass : new Pass[]{capture,horizontal,vertical,composite,diagnose})
                if (pass != null) pass.destroy();
        }
        capture=horizontal=vertical=composite=diagnose=null;
        probe=null; context=null;
        probeRevision=Long.MIN_VALUE;
        failed=false;
    }
}
