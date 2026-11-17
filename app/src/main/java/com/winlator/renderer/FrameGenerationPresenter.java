package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

/** GL-thread history of processed real scenes; synthetic presents never notify Wine. */
public final class FrameGenerationPresenter {
    private static final long MAX_HISTORY_BYTES = 48L * 1024 * 1024;
    private static final long STALE_NANOS = 250_000_000L;
    private final FrameGenerationClock clock = new FrameGenerationClock();
    private final RenderTarget[] frames = new RenderTarget[2];
    private EGLContext context;
    private FrameInterpolation interpolation;
    private int width, height, current, count;
    private long sourceSerial = Long.MIN_VALUE, sourceTime, visualRevision = Long.MIN_VALUE;
    private boolean prepared, failed;
    private float heldFraction = 1.0f;
    private volatile boolean historyAvailable;
    private String failureReason;
    private long realCaptures, generatedPresents, skippedGenerations, lastDiagnostic;
    private int slowSubmissions;

    private void ensureContext() {
        EGLContext now = EGL14.eglGetCurrentContext();
        if (now.equals(context)) return;
        // Old context objects have already been reclaimed by EGL, not this context.
        context = now;
        frames[0] = frames[1] = null;
        interpolation = new FrameInterpolation();
        width = height = count = 0;
        sourceSerial = Long.MIN_VALUE;
        sourceTime = 0;
        prepared = failed = historyAvailable = false;
        failureReason = null;
        slowSubmissions = 0;
        clock.reset();
    }

    private boolean allocate(int w, int h) {
        if (frames[0] != null && width == w && height == h) return true;
        if (w <= 0 || h <= 0 || (long)w * h * 8 > MAX_HISTORY_BYTES) {
            fail("history exceeds conservative memory limit");
            return false;
        }
        releaseTargets();
        width = w;
        height = h;
        for (int i = 0; i < 2; i++) {
            frames[i] = new RenderTarget();
            frames[i].setFormat(GLES20.GL_RGBA);
            frames[i].allocateFramebuffer(w, h);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frames[i].getFramebuffer());
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                releaseTargets();
                fail("real-frame history framebuffer unavailable");
                return false;
            }
        }
        return true;
    }

    private void fail(String reason) {
        failed = true;
        failureReason = reason;
        historyAvailable = false;
        clock.reset();
    }

    /** No GL calls; called on GL thread when pause/geometry/options invalidate history. */
    public void resetHistory() {
        count = 0;
        sourceSerial = Long.MIN_VALUE;
        sourceTime = 0;
        prepared = historyAvailable = false;
        heldFraction = 1.0f;
        slowSubmissions = 0;
        clock.reset();
    }

    /** Returns false for normal-render fallback, including warmup and refresh saturation. */
    public boolean render(GLRenderer renderer, long nowNanos, long serial, long timestampNanos,
                          long displayPeriodNanos) {
        long workStarted = System.nanoTime();
        ensureContext();
        if (failed) return false;
        int w = renderer.surfaceWidth, h = renderer.surfaceHeight;
        long revision = renderer.effectComposer.getVisualRevision();
        boolean resized = width != 0 && (width != w || height != h);
        if (resized || revision != visualRevision) {
            resetHistory();
            if (resized) {
                releaseTargets();
                interpolation.release();
                width = height = 0;
            }
            visualRevision = revision;
        }
        clock.setDisplayPeriodNanos(displayPeriodNanos);
        boolean fresh = serial > sourceSerial;
        if (fresh) {
            if (sourceTime != 0 && (timestampNanos <= sourceTime || timestampNanos - sourceTime > STALE_NANOS)) {
                resetHistory();
            }
            clock.onSourceFrame(timestampNanos, serial);
            sourceSerial = serial;
            sourceTime = timestampNanos;
        }
        // Learn the source cadence without allocating extra textures while at panel rate.
        if (!clock.isInterpolationEligible()) {
            long period = clock.getSourcePeriodNanos();
            // A single late/coalesced pair must not force shader recompilation and
            // allocation churn. Sustained panel-rate sources/stale resets free storage.
            if (frames[0] != null && (period == 0 || period <= displayPeriodNanos * 1.06)) {
                releaseTargets();
                interpolation.release();
            }
            count = 0;
            prepared = historyAvailable = false;
            clock.markCurrentPresented(nowNanos);
            return false;
        }
        if (fresh || count == 0) {
            if (!allocate(w, h)) return false;
            current = count == 0 ? 0 : 1 - current;
            renderer.drawProcessedSceneTo(frames[current].getFramebuffer());
            count = Math.min(2, count + 1);
            realCaptures++;
            historyAvailable = true;
            prepared = false;
            // A pair shift retains the last real frame as previous. Never show
            // the new current early and then rewind to its midpoint.
            heldFraction = count < 2 ? 1.0f : 0.0f;
        }
        FrameGenerationClock.Presentation presentation = clock.getPresentation(nowNanos);
        float fraction = presentation.draw ? presentation.fraction : heldFraction;
        if (count < 2) {
            fraction = 1.0f;
            clock.markCurrentPresented(nowNanos);
        }
        boolean generate = fraction > 0.0f && fraction < 1.0f;
        int previousTexture = frames[count > 1 ? 1 - current : current].getTextureId();
        int currentTexture = frames[current].getTextureId();
        // Flow is computed only if a midpoint can actually be presented on this display.
        if (generate && !prepared) {
            prepared = interpolation.prepare(previousTexture, currentTexture, w, h);
            if (!prepared) {
                skippedGenerations++;
                fail("motion estimation shader or framebuffer unavailable");
                return false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, w, h);
        if (!interpolation.draw(previousTexture, currentTexture, w, h, fraction)) {
            fail("interpolation shader or framebuffer unavailable");
            return false;
        }
        // This measures CPU/driver submission stalls, not GPU duration or physical FPS.
        if (System.nanoTime() - workStarted > displayPeriodNanos * 2L) slowSubmissions++;
        else slowSubmissions = 0;
        if (slowSubmissions >= 8) {
            fail("sustained slow GL submission; preserving normal rendering");
            return false;
        }
        heldFraction = fraction;
        if (presentation.draw && generate) generatedPresents++;
        renderer.drawFrameGenerationCursor();
        renderer.viewportNeedsUpdate = true;
        if (nowNanos - lastDiagnostic >= 5_000_000_000L) {
            lastDiagnostic = nowNanos;
            renderer.logFrameGeneration("active sourcePeriodMs=" + clock.getSourcePeriodNanos() / 1_000_000.0
                    + " realCaptures=" + realCaptures + " generatedSubmissions=" + generatedPresents
                    + " skipped=" + skippedGenerations + " history=" + w + "x" + h);
        }
        return true;
    }

    public boolean needsVsync(long nowNanos) { return historyAvailable && clock.wantsVsync(nowNanos); }
    public String getFailureReason() { return failureReason; }
    public boolean hasFailed() { return failed; }

    private void releaseTargets() {
        for (int i = 0; i < 2; i++) {
            if (frames[i] != null) frames[i].destroy();
            frames[i] = null;
        }
    }

    /** Valid EGL context only. Context loss is handled by ensureContext before deletion. */
    public void release() {
        ensureContext();
        releaseTargets();
        if (interpolation != null) interpolation.release();
        resetHistory();
        context = null;
    }
}
