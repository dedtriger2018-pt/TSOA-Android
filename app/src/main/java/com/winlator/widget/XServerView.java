package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private static final long DEFAULT_DISPLAY_PERIOD_NANOS = 1_000_000_000L / 60;
    private final GLRenderer renderer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean renderDirty = new AtomicBoolean();
    private final AtomicBoolean wakePosted = new AtomicBoolean();
    private volatile boolean frameGenerationEnabled;
    private volatile boolean paused;
    private volatile boolean attached;

    // Choreographer and its callback state belong exclusively to the main thread.
    private Choreographer choreographer;
    private boolean frameCallbackPosted;
    private final Choreographer.FrameCallback frameCallback = this::onFrameGenerationFrame;
    private final Runnable renderWake = () -> {
        wakePosted.set(false);
        if (canScheduleFrameGeneration()) postFrameCallback();
    };

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        updateFrameGenerationDisplayPeriod();
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setFrameGenerationEnabled(boolean enabled) {
        frameGenerationEnabled = enabled;
        if (renderer == null) return;
        renderer.setFrameGenerationEnabled(enabled);
        if (enabled) {
            renderDirty.set(true);
            runOnMain(() -> {
                if (frameGenerationEnabled) {
                    updateFrameGenerationDisplayPeriod();
                    if (canScheduleFrameGeneration()) postFrameCallback();
                }
            });
        } else {
            renderDirty.set(false);
            runOnMain(this::stopFrameGenerationCallbacks);
            super.requestRender();
        }
    }

    @Override
    public void requestRender() {
        // View construction can invoke overrides before our fields or GL thread exist.
        if (renderer == null || renderDirty == null || mainHandler == null) return;
        if (!frameGenerationEnabled) {
            super.requestRender();
            return;
        }
        renderDirty.set(true);
        if (canScheduleFrameGeneration() && wakePosted.compareAndSet(false, true)) {
            mainHandler.post(renderWake);
        }
    }

    private boolean canScheduleFrameGeneration() {
        return frameGenerationEnabled && !paused && attached && renderer != null;
    }

    private void postFrameCallback() {
        if (frameCallbackPosted || !canScheduleFrameGeneration()) return;
        if (choreographer == null) choreographer = Choreographer.getInstance();
        frameCallbackPosted = true;
        choreographer.postFrameCallback(frameCallback);
    }

    private void onFrameGenerationFrame(long frameTimeNanos) {
        frameCallbackPosted = false;
        if (!canScheduleFrameGeneration()) return;
        boolean dirty = renderDirty.getAndSet(false);
        if (!dirty && !renderer.needsFrameGenerationVsync(frameTimeNanos)) return;
        renderer.onFrameGenerationVsync(frameTimeNanos);
        super.requestRender();
        // Keep at most one future vsync queued. The GL completion also requests a
        // wake if a newly captured frame creates work after this callback finishes.
        if (renderDirty.get() || renderer.needsFrameGenerationVsync(frameTimeNanos)) {
            postFrameCallback();
        }
    }

    private void stopFrameGenerationCallbacks() {
        if (frameCallbackPosted && choreographer != null) {
            choreographer.removeFrameCallback(frameCallback);
        }
        frameCallbackPosted = false;
        mainHandler.removeCallbacks(renderWake);
        wakePosted.set(false);
    }

    private void updateFrameGenerationDisplayPeriod() {
        if (renderer == null) return;
        Display display = getDisplay();
        float refreshRate = display != null ? display.getRefreshRate() : 60.0f;
        long displayPeriod = DEFAULT_DISPLAY_PERIOD_NANOS;
        if (!Float.isNaN(refreshRate) && !Float.isInfinite(refreshRate) && refreshRate >= 10.0f && refreshRate <= 1000.0f) {
            displayPeriod = Math.round(1_000_000_000.0 / refreshRate);
        }
        renderer.setFrameGenerationDisplayPeriod(displayPeriod);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override
    public void onPause() {
        paused = true;
        runOnMain(this::stopFrameGenerationCallbacks);
        renderer.resetFrameGenerationHistory();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        paused = false;
        runOnMain(() -> {
            updateFrameGenerationDisplayPeriod();
            if (canScheduleFrameGeneration()) {
                renderDirty.set(true);
                postFrameCallback();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        runOnMain(() -> {
            updateFrameGenerationDisplayPeriod();
            if (canScheduleFrameGeneration()) {
                renderDirty.set(true);
                postFrameCallback();
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        runOnMain(this::stopFrameGenerationCallbacks);
        renderer.resetFrameGenerationHistory();
        super.onDetachedFromWindow();
    }
}
