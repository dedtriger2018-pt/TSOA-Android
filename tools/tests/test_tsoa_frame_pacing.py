#!/usr/bin/env python3
"""Run the production XServerView pacing against deterministic Android scheduler fakes.

Uses the JDK compiler module, no Android SDK, GL calls, device, or sleeps. These
checks validate request/callback scheduling; they do not measure panel presentation.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    'android/annotation/SuppressLint.java': r'''
package android.annotation;
public @interface SuppressLint { String[] value(); }
''',
    'android/os/Looper.java': r'''
package android.os;
public final class Looper {
    private static final Thread MAIN_THREAD = Thread.currentThread();
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread() == MAIN_THREAD ? MAIN : null; }
    public static void requireMain() {
        if (myLooper() != MAIN) throw new AssertionError("Scheduler touched off the main thread");
    }
}
''',
    'android/os/Handler.java': r'''
package android.os;
import java.util.ArrayDeque;
public final class Handler {
    private static final ArrayDeque<Runnable> PENDING = new ArrayDeque<>();
    public Handler(Looper looper) {
        if (looper != Looper.getMainLooper()) throw new AssertionError("Non-main Handler");
    }
    public boolean post(Runnable task) {
        synchronized (PENDING) { PENDING.add(task); }
        return true;
    }
    public void removeCallbacks(Runnable task) {
        synchronized (PENDING) { PENDING.removeIf(candidate -> candidate == task); }
    }
    public static int pendingCount() { synchronized (PENDING) { return PENDING.size(); } }
    public static void drain() {
        Looper.requireMain();
        for (int i = 0; i < 1000; i++) {
            Runnable task;
            synchronized (PENDING) { task = PENDING.poll(); }
            if (task == null) return;
            task.run();
        }
        throw new AssertionError("Unbounded main wake loop");
    }
}
''',
    'android/view/Choreographer.java': r'''
package android.view;
import android.os.Looper;
import java.util.ArrayList;
public final class Choreographer {
    public interface FrameCallback { void doFrame(long time); }
    private static final Choreographer INSTANCE = new Choreographer();
    private final ArrayList<FrameCallback> pending = new ArrayList<>();
    public static Choreographer getInstance() { Looper.requireMain(); return INSTANCE; }
    public void postFrameCallback(FrameCallback callback) {
        Looper.requireMain();
        pending.add(callback);
        if (pending.size() > 1) throw new AssertionError("More than one future vsync queued");
    }
    public void removeFrameCallback(FrameCallback callback) {
        Looper.requireMain();
        pending.removeIf(candidate -> candidate == callback);
    }
    public static int pendingCount() { return INSTANCE.pending.size(); }
    public static void fire(long now) {
        Looper.requireMain();
        ArrayList<FrameCallback> callbacks = new ArrayList<>(INSTANCE.pending);
        INSTANCE.pending.clear();
        for (FrameCallback callback : callbacks) callback.doFrame(now);
    }
}
''',
    'android/view/Display.java': r'''
package android.view;
public final class Display {
    public float refreshRate = 60;
    public float getRefreshRate() { return refreshRate; }
}
''',
    'android/content/Context.java': r'''
package android.content;
import android.view.Display;
public final class Context { public Display display = new Display(); }
''',
    'android/view/ViewGroup.java': r'''
package android.view;
public class ViewGroup {
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public LayoutParams(int width, int height) {}
    }
}
''',
    'android/widget/FrameLayout.java': r'''
package android.widget;
import android.view.ViewGroup;
public class FrameLayout {
    public static class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
''',
    'android/opengl/GLSurfaceView.java': r'''
package android.opengl;
import android.content.Context;
import android.view.Display;
import android.view.ViewGroup;
public class GLSurfaceView {
    public interface Renderer {}
    public static final int RENDERMODE_WHEN_DIRTY = 0;
    public int directRequests, renderMode = -1, pauseCalls, resumeCalls;
    private boolean rendererReady;
    private final Context context;
    public GLSurfaceView(Context context) {
        this.context = context;
        // Deliberately invoke the subclass override before its fields exist.
        requestRender();
    }
    public void setLayoutParams(ViewGroup.LayoutParams params) {}
    public void setEGLContextClientVersion(int version) {}
    public void setEGLConfigChooser(int r, int g, int b, int a, int d, int s) {}
    public void setPreserveEGLContextOnPause(boolean preserve) {}
    public void setRenderer(Renderer renderer) { rendererReady = true; }
    public void setRenderMode(int mode) { renderMode = mode; }
    public void requestRender() {
        if (!rendererReady) throw new AssertionError("Constructor reached uninitialized GL thread");
        directRequests++;
    }
    public Display getDisplay() { return context.display; }
    public void onPause() { pauseCalls++; }
    public void onResume() { resumeCalls++; }
    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
}
''',
    'com/winlator/xserver/XServer.java': r'''
package com.winlator.xserver;
public final class XServer {}
''',
    'com/winlator/renderer/GLRenderer.java': r'''
package com.winlator.renderer;
import android.opengl.GLSurfaceView;
import android.os.Looper;
import com.winlator.widget.XServerView;
import com.winlator.xserver.XServer;
public final class GLRenderer implements GLSurfaceView.Renderer {
    public volatile boolean enabled, futurePending;
    public long displayPeriod, lastVsync;
    public int vsyncs, resets;
    public GLRenderer(XServerView view, XServer server) {}
    public void setFrameGenerationEnabled(boolean value) { enabled = value; }
    public void setFrameGenerationDisplayPeriod(long period) {
        Looper.requireMain(); displayPeriod = period;
    }
    public void onFrameGenerationVsync(long timestamp) {
        Looper.requireMain(); lastVsync = timestamp; vsyncs++;
    }
    public boolean needsFrameGenerationVsync(long timestamp) { return futurePending; }
    public void resetFrameGenerationHistory() { resets++; }
}
''',
}


HARNESS = r'''
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import com.winlator.renderer.GLRenderer;
import com.winlator.widget.XServerView;
import com.winlator.xserver.XServer;

public final class TsoaFramePacingCheck {
    private static final long PERIOD = 16_666_667L;
    private static long time;
    private static final class View extends XServerView {
        View(Context context) { super(context, new XServer()); }
        void attach() { onAttachedToWindow(); }
        void detach() { onDetachedFromWindow(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void worker(Runnable action) throws Exception {
        final Throwable[] failure = { null };
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        }, "fake-source-or-GL-thread");
        thread.start(); thread.join();
        if (failure[0] != null) throw new AssertionError("Worker failed", failure[0]);
    }
    private static void burst(View view) throws Exception {
        // Source updates and pointer updates both enter the widget through requestRender.
        worker(() -> { for (int i = 0; i < 100; i++) view.requestRender(); });
    }
    private static void frame() { Choreographer.fire(time += PERIOD); }
    private static void idleQueues() {
        require(Handler.pendingCount() == 0 && Choreographer.pendingCount() == 0,
                "Idle widget retained scheduled work");
    }

    private static void directAndCoalescedRequests() throws Exception {
        Context context = new Context();
        View view = new View(context);
        GLRenderer renderer = view.getRenderer();
        require(view.directRequests == 0, "Construction-time override requested a premature draw");
        require(view.renderMode == GLSurfaceView.RENDERMODE_WHEN_DIRTY, "Changed dirty render mode");
        require(renderer.displayPeriod == Math.round(1_000_000_000.0 / 60), "Initial refresh missing");
        burst(view);
        require(view.directRequests == 100, "FG-off requests were delayed or coalesced");
        idleQueues();

        view.setFrameGenerationEnabled(true);
        require(renderer.enabled, "Enable did not reach renderer");
        idleQueues(); // Enabling before attachment must not wake a surface.
        view.attach();
        require(Choreographer.pendingCount() == 1, "Attach failed to arm enabled FG");
        frame();
        require(renderer.lastVsync == time && view.directRequests == 101, "Initial vsync not forwarded");
        idleQueues();

        burst(view);
        require(Handler.pendingCount() == 1, "100 requests did not coalesce to one main wake");
        require(Choreographer.pendingCount() == 0 && view.directRequests == 101,
                "Worker requested an immediate FG draw");
        Handler.drain();
        require(Choreographer.pendingCount() == 1, "Main wake did not arm one vsync");
        burst(view); Handler.drain();
        require(Choreographer.pendingCount() == 1, "Repeated burst duplicated a callback");
        frame();
        require(view.directRequests == 102, "Coalesced updates did not produce exactly one draw");
        idleQueues();

        // Completion on GL can discover pending work after the UI callback returned.
        worker(() -> { renderer.futurePending = true; view.requestRender(); });
        Handler.drain(); frame();
        require(Choreographer.pendingCount() == 1, "Future clock work lost its next vsync");
        for (int i = 0; i < 200; i++) {
            int before = view.directRequests;
            burst(view);
            require(Handler.pendingCount() == 1, "Busy source grew the main wake queue");
            Handler.drain();
            require(Choreographer.pendingCount() == 1, "Busy source grew the vsync queue");
            frame();
            require(view.directRequests == before + 1 && Choreographer.pendingCount() == 1,
                    "Pending clock work did not request one draw per display callback");
        }
        renderer.futurePending = false;
        int before = view.directRequests;
        frame();
        require(view.directRequests == before, "Idle clock caused another draw");
        idleQueues();
        view.setFrameGenerationEnabled(false);
        require(!renderer.enabled && view.directRequests == before + 1, "Disable did not repaint directly");
        burst(view);
        require(view.directRequests == before + 101, "Disabled widget retained FG pacing");
        idleQueues();
    }

    private static void lifecycleAndRefresh() throws Exception {
        Context context = new Context();
        View view = new View(context);
        GLRenderer renderer = view.getRenderer();
        view.attach();
        worker(() -> view.setFrameGenerationEnabled(true));
        require(Handler.pendingCount() == 1 && Choreographer.pendingCount() == 0,
                "Background enable bypassed the main thread");
        Handler.drain();
        renderer.futurePending = true;
        frame();
        burst(view); // Pause must remove both this main wake and the pending vsync.
        view.onPause();
        require(view.pauseCalls == 1 && renderer.resets == 1, "Pause failed to reset history/surface");
        idleQueues();
        int before = view.directRequests;
        burst(view); Handler.drain(); frame();
        require(view.directRequests == before, "Paused FG rendered or woke repeatedly");
        idleQueues();

        context.display.refreshRate = 90;
        view.onResume();
        require(view.resumeCalls == 1 && renderer.displayPeriod == Math.round(1_000_000_000.0 / 90),
                "Resume did not propagate the display refresh");
        require(Choreographer.pendingCount() == 1, "Resume did not rearm FG");
        frame();
        require(view.directRequests == before + 1, "Resumed FG did not draw");
        burst(view);
        view.detach();
        require(renderer.resets == 2, "Detach did not invalidate history");
        idleQueues();
        before = view.directRequests;
        burst(view); Handler.drain(); frame();
        require(view.directRequests == before, "Detached FG rendered or woke repeatedly");

        context.display.refreshRate = 120;
        view.attach();
        require(renderer.displayPeriod == Math.round(1_000_000_000.0 / 120), "Reattach lost refresh change");
        require(Choreographer.pendingCount() == 1, "Reattach did not rearm FG");
        view.onPause();
        view.detach();
        view.attach();
        idleQueues(); // An attached but paused surface must remain asleep.
        context.display.refreshRate = Float.NaN;
        view.onResume();
        require(renderer.displayPeriod == 1_000_000_000L / 60, "Invalid refresh lacked a 60 Hz fallback");
        require(Choreographer.pendingCount() == 1, "Second resume did not rearm");
        view.detach();
        context.display = null;
        view.attach();
        require(Math.abs(renderer.displayPeriod - PERIOD) <= 1, "Absent display lacked a refresh fallback");
        view.setFrameGenerationEnabled(false);
        idleQueues();
        view.onPause(); view.onResume(); view.detach(); view.attach();
        idleQueues(); // Lifecycle must not reactivate disabled FG.
    }

    public static void main(String[] args) throws Exception {
        Looper.getMainLooper();
        directAndCoalescedRequests();
        lifecycleAndRefresh();
        System.out.println("PASS: production XServerView pacing; constructor guard, FG-off direct requests, "
                + "100-request coalescing, one future callback, 200 busy frames without queue growth, "
                + "GL completion wake, idle stop, pause/detach cancellation, resume/attach rearm and refresh fallback");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    production = root / 'app/src/main/java/com/winlator/widget/XServerView.java'
    with tempfile.TemporaryDirectory(prefix='tsoa-frame-pacing-') as directory:
        temporary = Path(directory)
        sources = []
        for name, content in STUBS.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(str(path))
        harness = temporary / 'TsoaFramePacingCheck.java'
        harness.write_text(HARNESS, encoding='utf-8')
        classes = temporary / 'classes'
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                        '-source', '8', '-target', '8', '-Xlint:-options', '-d', str(classes),
                        *sources, str(production), str(harness)], check=True)
        subprocess.run([args.java, '-cp', str(classes), 'TsoaFramePacingCheck'], check=True)


if __name__ == '__main__':
    main()
