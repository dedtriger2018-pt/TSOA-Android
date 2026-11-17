package com.winlator.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.core.TsoaRedirectorV2Log;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.Effect;
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

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        effects.remove(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized  <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect) {
        ScreenMaterial material = effect.getMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        if (effect instanceof ColorEffect) {
            ColorEffect color = (ColorEffect)effect;
            float strength = color.getHdrStrength();
            if (color != lastLoggedColorEffect || strength != lastLoggedHdrStrength ||
                    material.programId != lastLoggedColorProgram) {
                lastLoggedColorEffect = color;
                lastLoggedHdrStrength = strength;
                lastLoggedColorProgram = material.programId;
                final String diagnostic = "Game color pass drawn: hdrStrength=" + strength
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
        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }

        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }
    }

    public synchronized void render() {
        initBuffers();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hasEffects() ? readBuffer.getFramebuffer() : 0);
        if (!sourceFramebufferChecked) {
            sourceFramebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            sourceFramebufferChecked = true;
        }
        renderer.drawFrame();

        for (Effect effect : effects) {
            boolean renderToScreen = effect == effects.get(effects.size()-1);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, !renderToScreen ? writeBuffer.getFramebuffer() : 0);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderEffect(effect);
            swapBuffers();
        }
    }
}
