package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.util.Log;

import com.winlator.core.TsoaRedirectorV2Log;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.effects.FXAAEffect;
import com.winlator.renderer.effects.SharpeningEffect;
import com.winlator.renderer.material.ScreenMaterial;

import java.util.ArrayList;

public class EffectComposer {
    private final GLRenderer renderer;
    private RenderTarget readBuffer = null;
    private RenderTarget writeBuffer = null;
    private final ArrayList<Effect> effects = new ArrayList<>();
    private boolean sourceFramebufferChecked;
    private int sourceFramebufferStatus;
    private ColorEffect lastLoggedColorEffect;
    private float lastLoggedHdrStrength = Float.NaN;
    private int lastLoggedColorProgram = -1;
    private boolean lastLoggedBloom;
    private SoftBloom softBloom;
    private boolean bloomReleaseRequested;

    private long visualRevision;
    public synchronized long getVisualRevision() { return visualRevision; }

    private boolean gameFxaa, gameFsr;
    private int gameSharpness;
    private SpatialUpscaler upscaler;
    private FXAAEffect automaticFxaa;
    private SharpeningEffect cas, rcas;
    private final ArrayList<Effect> frameEffects = new ArrayList<>();
    private EGLContext bufferContext;
    private int bufferWidth, bufferHeight;
    private boolean loggedFxaa, loggedFsr, loggedFsrActive;
    private int loggedSharpness = -1, loggedOutputWidth, loggedOutputHeight, loggedInputWidth, loggedInputHeight;

    private boolean original, vignette, grain, presentationChanged;
    private int cinematic, cinematicAmount, beyond, blur, saturation, qualityLimit = 3;
    private PresentationEffects presentation;
    private boolean presentationOnlyBuffers;

    public synchronized void configurePresentation(boolean original, int cinematic, int cinematicAmount,
            boolean vignette, boolean grain, int beyond, int blur, int saturation) {
        if (this.original == original && this.cinematic == cinematic && this.cinematicAmount == cinematicAmount
                && this.vignette == vignette && this.grain == grain && this.beyond == beyond
                && this.blur == blur && this.saturation == saturation) return;
        presentationChanged |= this.original != original || this.cinematic != cinematic
                || (this.cinematicAmount > 0) != (cinematicAmount > 0)
                || (this.beyond > 0) != (beyond > 0);
        this.original = original;
        this.cinematic = cinematic; this.cinematicAmount = cinematicAmount;
        this.vignette = vignette; this.grain = grain;
        this.beyond = beyond; this.blur = blur; this.saturation = saturation;
        visualRevision++;
        renderer.xServerView.queueEvent(this::releaseChangedPresentation);
        renderer.xServerView.requestRender();
    }

    synchronized void setQualityLimit(int limit) {
        if (limit == qualityLimit) return;
        qualityLimit = limit;
        visualRevision++;
    }

    private synchronized void releaseChangedPresentation() {
        if (!presentationChanged || EGL14.eglGetCurrentContext().equals(EGL14.EGL_NO_CONTEXT)) return;
        if (presentation != null) presentation.release();
        presentation = null;
        if (original || (presentationOnlyBuffers && (cinematic == 0 || cinematicAmount == 0) && beyond == 0 && !canUseScene()
                && !gameFxaa && !gameFsr && gameSharpness == 0 && effects.isEmpty())) {
            if (EGL14.eglGetCurrentContext().equals(bufferContext)) {
                if (readBuffer != null) readBuffer.destroy();
                if (writeBuffer != null) writeBuffer.destroy();
            }
            readBuffer = writeBuffer = null;
            sourceFramebufferChecked = sceneTargetsChecked = false;
            presentationOnlyBuffers = reconstructionOnlyBuffers = false;
        }
        presentationChanged = false;
    }

    private boolean canUseBeyond() {
        return beyond > 0 && !renderer.isFullscreen()
                && (renderer.viewTransformation.viewWidth < renderer.surfaceWidth
                    || renderer.viewTransformation.viewHeight < renderer.surfaceHeight);
    }

    private int genesisLevel, lumenLevel;
    private SceneReconstruction reconstruction;
    private boolean sceneChanged;
    private boolean reconstructionOnlyBuffers;
    private boolean sceneTargetsChecked, sceneTargetsComplete;

    /** Safe on the settings thread. Uploads and releases run only under the owning EGL context. */
    public synchronized void configureReconstruction(int genesis, int lumen) {
        genesis = Math.max(0, Math.min(3, genesis));
        lumen = Math.max(0, Math.min(3, lumen));
        if (genesis == genesisLevel && lumen == lumenLevel) return;
        sceneChanged = true;
        genesisLevel = genesis;
        lumenLevel = lumen;
        visualRevision++;
        renderer.xServerView.queueEvent(this::releaseChangedScene);
        renderer.xServerView.requestRender();
    }

    private synchronized void releaseChangedScene() {
        if (!sceneChanged || EGL14.eglGetCurrentContext().equals(EGL14.EGL_NO_CONTEXT)) return;
        if (reconstruction != null) reconstruction.release();
        reconstruction = null;
        if (genesisLevel == 0 && lumenLevel == 0 && reconstructionOnlyBuffers && !gameFxaa && !gameFsr
                && gameSharpness == 0 && effects.isEmpty()) {
            if (EGL14.eglGetCurrentContext().equals(bufferContext)) {
                if (readBuffer != null) readBuffer.destroy();
                if (writeBuffer != null) writeBuffer.destroy();
            }
            readBuffer = writeBuffer = null;
            sourceFramebufferChecked = false;
            reconstructionOnlyBuffers = false;
        }
        sceneTargetsChecked = false;
        sceneChanged = false;
    }

    private boolean canUseScene() {
        return !original && (genesisLevel > 0 || lumenLevel > 0)
                && renderer.getMagnifierZoom() == 1f && !renderer.isScreenOffsetYRelativeToCursor()
                && (reconstruction == null || reconstruction.available(renderer.surfaceWidth, renderer.surfaceHeight));
    }

    synchronized boolean reconstructsGameScene() { return canUseScene(); }

    /** Preferences are applied independently of HDR/manual color controls. */
    public synchronized void configureSpatialFilters(boolean fxaa, boolean fsr, int sharpness) {
        visualRevision++;
        gameFxaa = fxaa;
        gameFsr = fsr;
        gameSharpness = Math.max(0, Math.min(100, sharpness));
        renderer.xServerView.requestRender();
        renderer.xServerView.queueEvent(this::releaseUnusedSpatialResources);
    }

    private void releaseUnusedSpatialResources() {
        synchronized (this) {
            EGLContext current = EGL14.eglGetCurrentContext();
            if (current.equals(EGL14.EGL_NO_CONTEXT)) return;
            if (!current.equals(bufferContext)) {
                automaticFxaa = null;
                cas = rcas = null;
                copyMaterial = null;
            }
            if ((!gameFsr || !canUseFsr()) && upscaler != null) { upscaler.release(); upscaler = null; }
            if (!gameFxaa && automaticFxaa != null) { automaticFxaa.getMaterial().destroy(); automaticFxaa = null; }
            if (gameSharpness == 0 && cas != null) { cas.getMaterial().destroy(); cas = null; }
            if (!gameFsr && rcas != null) { rcas.getMaterial().destroy(); rcas = null; }
        }
    }

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        visualRevision++;
        renderer.xServerView.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        effects.remove(effect);
        visualRevision++;
        releaseBloomResources();
        renderer.xServerView.requestRender();
    }

    public synchronized  <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        releasePendingBloomResources();
        releaseUnusedSpatialResources();
        releaseChangedScene();
        releaseChangedPresentation();
        if (original) return false;
        return (cinematic > 0 && cinematicAmount > 0) || canUseBeyond() || canUseScene() || gameFxaa || (gameFsr && canUseFsr()) || gameSharpness > 0 || !effects.isEmpty();
    }

    /** Schedule resource destruction while the game surface's EGL context is current. */
    public synchronized void releaseBloomResources() {
        bloomReleaseRequested = true;
        renderer.xServerView.queueEvent(() -> {
            synchronized (EffectComposer.this) {
                releasePendingBloomResources();
            }
        });
        renderer.xServerView.requestRender();
    }

    private void releasePendingBloomResources() {
        if (!bloomReleaseRequested) return;
        ColorEffect color = getEffect(ColorEffect.class);
        if (color != null && color.isBloomEnabled()) {
            bloomReleaseRequested = false;
        } else if (softBloom == null) {
            bloomReleaseRequested = false;
        } else if (!EGL14.eglGetCurrentContext().equals(EGL14.EGL_NO_CONTEXT)) {
            softBloom.release();
            softBloom = null;
            bloomReleaseRequested = false;
        }
        // A queued event may run before EGL resume. hasEffects() retries at the
        // next actual draw even if there are no post-processing effects left.
    }

    private boolean canUseFsr() {
        int outWidth = renderer.isFullscreen() ? renderer.surfaceWidth : renderer.viewTransformation.viewWidth;
        int outHeight = renderer.isFullscreen() ? renderer.surfaceHeight : renderer.viewTransformation.viewHeight;
        return SpatialUpscaler.canUpscale(renderer.getGameWidth(), renderer.getGameHeight(), outWidth, outHeight);
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect) {
        int bloomTexture = 0;
        if (effect instanceof ColorEffect) {
            ColorEffect color = (ColorEffect)effect;
            if (color.isBloomEnabled()) {
                if (softBloom == null) softBloom = new SoftBloom();
                bloomTexture = softBloom.render(renderer, readBuffer.getTextureId(),
                        renderer.surfaceWidth, renderer.surfaceHeight);
            }
            color.setBloomTextureAvailable(bloomTexture != 0);
        }
        ScreenMaterial material = effect.getMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);


        if (bloomTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexture);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        if (bloomTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        if (effect instanceof ColorEffect) {
            ColorEffect color = (ColorEffect)effect;
            float strength = color.getHdrStrength();
            if (color != lastLoggedColorEffect || strength != lastLoggedHdrStrength ||
                    material.programId != lastLoggedColorProgram || color.isBloomEnabled() != lastLoggedBloom) {
                lastLoggedColorEffect = color;
                lastLoggedHdrStrength = strength;
                lastLoggedColorProgram = material.programId;
                lastLoggedBloom = color.isBloomEnabled();
                final String diagnostic = "Game color pass drawn: hdrStrength=" + strength
                        + " bloom=" + color.isBloomEnabled()
                        + " bloomTexture=" + bloomTexture
                        + " bloomSize=" + (softBloom != null ? softBloom.getWidth() + "x" + softBloom.getHeight() : "0x0")
                        + " program=" + material.programId
                        + " surface=" + renderer.surfaceWidth + "x" + renderer.surfaceHeight
                        + " sourceFboStatus=0x" + Integer.toHexString(sourceFramebufferStatus)
                        + " glError=0x" + Integer.toHexString(GLES20.glGetError());
                Log.i("TSOA_FE_DIAG", diagnostic);
                // Export the first real draw without doing file I/O on the GL thread.
                renderer.xServerView.post(() -> TsoaRedirectorV2Log.append(
                        renderer.xServerView.getContext(), diagnostic));
            }
        }
    }

    private void initBuffers() {
        EGLContext current = EGL14.eglGetCurrentContext();
        boolean sameContext = current.equals(bufferContext);
        if (!sameContext || bufferWidth != renderer.surfaceWidth || bufferHeight != renderer.surfaceHeight) {
            if (sameContext) {
                if (readBuffer != null) readBuffer.destroy();
                if (writeBuffer != null) writeBuffer.destroy();
            }
            readBuffer = writeBuffer = null;
            bufferContext = current;
            bufferWidth = renderer.surfaceWidth;
            bufferHeight = renderer.surfaceHeight;
            sourceFramebufferChecked = false;
            sceneTargetsChecked = false;
            // These automatic programs and uniform locations also belong to the old context.
            if (!sameContext) { automaticFxaa = null; cas = rcas = null; copyMaterial = null; }
        }
        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }
        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }
    }

    public synchronized void render() { renderToFramebuffer(0); }

    /** Render the processed scene into a separate real-frame history target. */
    public synchronized void renderToFramebuffer(int outputFramebuffer) {
        boolean useScene = canUseScene();
        boolean useCinematic = !original && cinematic > 0 && cinematicAmount > 0;
        boolean useBeyond = !original && canUseBeyond();
        if ((useCinematic || useBeyond) && readBuffer == null)
            presentationOnlyBuffers = !useScene && !gameFxaa && !gameFsr && gameSharpness == 0 && effects.isEmpty();
        if (useScene && readBuffer == null)
            reconstructionOnlyBuffers = !gameFxaa && !gameFsr && gameSharpness == 0 && effects.isEmpty();
        initBuffers();
        if ((useScene || useCinematic || useBeyond) && !sceneTargetsChecked) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            sceneTargetsComplete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeBuffer.getFramebuffer());
            sceneTargetsComplete &= GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
            sceneTargetsChecked = true;
            if (!sceneTargetsComplete) Log.w("TSOA_FE_DIAG", "Scene reconstruction bypassed: compositor framebuffer unavailable");
        }
        if ((useScene || useCinematic || useBeyond) && !sceneTargetsComplete) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer);
            renderer.viewportNeedsUpdate = true;
            renderer.drawFrame();
            return;
        }
        boolean fsrActive = false;
        int outWidth = renderer.isFullscreen() ? renderer.surfaceWidth : renderer.viewTransformation.viewWidth;
        int outHeight = renderer.isFullscreen() ? renderer.surfaceHeight : renderer.viewTransformation.viewHeight;
        int outX = renderer.isFullscreen() ? 0 : renderer.viewTransformation.viewOffsetX;
        int outY = renderer.isFullscreen() ? 0 : renderer.viewTransformation.viewOffsetY;
        if (gameFsr && SpatialUpscaler.canUpscale(renderer.getGameWidth(), renderer.getGameHeight(), outWidth, outHeight)) {
            if (upscaler == null) upscaler = new SpatialUpscaler();
            fsrActive = upscaler.render(renderer, readBuffer, outX, outY, outWidth, outHeight);
        }
        if (!fsrActive) {
            // Keep a failed eligible capture until resize/toggle/context change;
            // repeatedly reallocating a failed FBO would stall every game frame.
            if ((!gameFsr || !canUseFsr()) && upscaler != null) { upscaler.release(); upscaler = null; }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            renderer.viewportNeedsUpdate = true;
            renderer.drawFrame();
        }
        if (!sourceFramebufferChecked) {
            sourceFramebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            sourceFramebufferChecked = true;
        }
        frameEffects.clear();
        if (gameFxaa && getEffect(FXAAEffect.class) == null) {
            if (automaticFxaa == null) automaticFxaa = new FXAAEffect() {
                @Override public ScreenMaterial createMaterial() {
                    return new ScreenMaterial() {
                        @Override protected String getFragmentShader() { return SpatialShaders.getFxaaFragmentShader(); }
                    };
                }
            };
            frameEffects.add(automaticFxaa);
        }
        if (fsrActive) {
            if (rcas == null) rcas = new SharpeningEffect(true);
            rcas.setStrength(gameSharpness == 0 ? 0.25f : gameSharpness / 100.0f);
            frameEffects.add(rcas);
        } else if (gameSharpness > 0) {
            if (cas == null) cas = new SharpeningEffect(false);
            cas.setStrength(gameSharpness / 100.0f);
            frameEffects.add(cas);
        }
        frameEffects.addAll(effects);
        // The input already has the fitted viewport (and EASU if active). Sharpening,
        // color and Bloom consume the reconstructed real frame before FG records it.
        if (useScene && outWidth > 0 && outHeight > 0) {
            if (reconstruction == null) reconstruction = new SceneReconstruction();
            if (reconstruction.draw(Math.min(genesisLevel, qualityLimit), Math.min(lumenLevel, qualityLimit), renderer,
                    readBuffer.getTextureId(), writeBuffer, outX, outY, outWidth, outHeight)) swapBuffers();
        }
        if ((useCinematic || useBeyond) && (outWidth <= 0 || outHeight <= 0)) useCinematic = useBeyond = false;
        if ((useCinematic || useBeyond) && presentation == null) presentation = new PresentationEffects();
        if (useCinematic && presentation.draw(renderer, readBuffer, writeBuffer.getFramebuffer(),
                outX, outY, outWidth, outHeight, true, Math.min(cinematic, qualityLimit),
                cinematicAmount / 100f, vignette ? 1f : 0f, grain ? 1f : 0f)) swapBuffers();
        for (int i = 0; i < frameEffects.size(); i++) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, useBeyond || i + 1 < frameEffects.size()
                    ? writeBuffer.getFramebuffer() : outputFramebuffer);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderEffect(frameEffects.get(i));
            swapBuffers();
        }
        boolean borderDrawn = useBeyond && presentation.draw(renderer, readBuffer, outputFramebuffer,
                outX, outY, outWidth, outHeight, false, qualityLimit,
                beyond / 100f, blur / 100f, saturation / 100f);
        if (!borderDrawn && (useBeyond || frameEffects.isEmpty())) {
            if (copyMaterial == null) copyMaterial = new ScreenMaterial();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawCopy();
        }
        if (loggedFxaa != gameFxaa || loggedSharpness != gameSharpness || loggedFsr != gameFsr
                || loggedFsrActive != fsrActive || loggedOutputWidth != outWidth || loggedOutputHeight != outHeight
                || loggedInputWidth != renderer.getGameWidth() || loggedInputHeight != renderer.getGameHeight()) {
            final String spatialDiagnostic = "Game spatial pass: fxaa=" + gameFxaa + " sharpness=" + gameSharpness
                    + " fsrRequested=" + gameFsr + " fsrActive=" + fsrActive
                    + " input=" + renderer.getGameWidth() + "x" + renderer.getGameHeight()
                    + " output=" + outWidth + "x" + outHeight;
            loggedFxaa = gameFxaa; loggedSharpness = gameSharpness; loggedFsr = gameFsr;
            loggedFsrActive = fsrActive; loggedOutputWidth = outWidth; loggedOutputHeight = outHeight;
            loggedInputWidth = renderer.getGameWidth(); loggedInputHeight = renderer.getGameHeight();
            Log.i("TSOA_FE_DIAG", spatialDiagnostic);
            renderer.xServerView.post(() -> TsoaRedirectorV2Log.append(renderer.xServerView.getContext(), spatialDiagnostic));
        }
    }

    private ScreenMaterial copyMaterial;
    private void drawCopy() {
        copyMaterial.use();
        renderer.quadVertices.bind(copyMaterial.programId);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        copyMaterial.setUniformInt(copyMaterial.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        renderer.viewportNeedsUpdate = true;
    }
}
