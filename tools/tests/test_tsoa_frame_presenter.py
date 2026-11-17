#!/usr/bin/env python3
"""Exercise the actual frame presenter and cadence clock with traced GL doubles.

Requires Python 3 and JDK 17+. Source capture, history identity, presentation
fractions, resource lifetime and failures are checked without Android or a GPU.
Optical-flow pixels and GLES program/state validation have a separate GPU test.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "android/opengl/EGLContext.java": """
package android.opengl;
public final class EGLContext {
    public final int id;
    public EGLContext(int id) { this.id = id; }
    @Override public boolean equals(Object other) {
        return other instanceof EGLContext && ((EGLContext)other).id == id;
    }
    @Override public int hashCode() { return id; }
}
""",
    "android/opengl/EGL14.java": """
package android.opengl;
public final class EGL14 {
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext(0);
    public static EGLContext current = new EGLContext(1);
    public static EGLContext eglGetCurrentContext() { return current; }
}
""",
    "android/opengl/GLES20.java": """
package android.opengl;
public final class GLES20 {
    public static final int GL_RGBA=6408, GL_FRAMEBUFFER=36160;
    public static final int GL_FRAMEBUFFER_COMPLETE=36053;
    public static int framebuffer, checks;
    public static boolean framebufferComplete = true;
    public static void glBindFramebuffer(int target, int value) { framebuffer = value; }
    public static int glCheckFramebufferStatus(int target) {
        checks++;
        return framebufferComplete ? GL_FRAMEBUFFER_COMPLETE : 36054;
    }
    public static void glViewport(int x, int y, int width, int height) {}
}
""",
    "com/winlator/renderer/Trace.java": """
package com.winlator.renderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
public final class Trace {
    public static final ArrayList<String> events = new ArrayList<>();
    public static final ArrayList<Draw> draws = new ArrayList<>();
    public static final Map<Integer, RenderTarget> targets = new HashMap<>();
    public static int allocations, destructions, preparations, releases, captures;
    public static long storageBytes;
    public static boolean prepareWorks=true, drawWorks=true;
    public static final class Draw {
        public final long previous, current;
        public final float fraction;
        public final double displayed;
        Draw(long p, long c, float f) {
            previous=p; current=c; fraction=f; displayed=p*(1.0-f)+c*f;
        }
        @Override public String toString() {
            return previous+"->"+current+"@"+fraction+"="+displayed;
        }
    }
    public static void reset() {
        events.clear(); draws.clear(); targets.clear();
        allocations=destructions=preparations=releases=captures=0;
        storageBytes=0; prepareWorks=drawWorks=true;
        EGL14.current=new EGLContext(EGL14.current.id+1);
        GLES20.framebufferComplete=true; GLES20.checks=0;
    }
}
""",
    "com/winlator/renderer/RenderTarget.java": """
package com.winlator.renderer;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
public final class RenderTarget {
    private static int next=100;
    private final int id=next++;
    private final EGLContext context=EGL14.eglGetCurrentContext();
    private int width, height, format;
    public long source;
    public void setFormat(int format) { this.format=format; }
    public void allocateFramebuffer(int width, int height) {
        if (format != GLES20.GL_RGBA) throw new AssertionError("History must use RGBA");
        this.width=width; this.height=height;
        Trace.allocations++; Trace.storageBytes+=4L*width*height;
        Trace.targets.put(id, this);
    }
    public int getFramebuffer() { return id; }
    public int getTextureId() { return id; }
    public void destroy() {
        if (!context.equals(EGL14.eglGetCurrentContext()))
            throw new AssertionError("Deleted history owned by another context");
        Trace.destructions++; Trace.storageBytes-=4L*width*height;
        Trace.targets.remove(id);
    }
}
""",
    "com/winlator/renderer/FrameInterpolation.java": """
package com.winlator.renderer;
import android.opengl.GLES20;
public final class FrameInterpolation {
    public boolean prepare(int previous, int current, int width, int height) {
        Trace.preparations++; Trace.events.add("prepare");
        if (previous == current) throw new AssertionError("Prepared one-frame history");
        return Trace.prepareWorks;
    }
    public boolean draw(int previous, int current, int width, int height, float fraction) {
        if (GLES20.framebuffer != 0) throw new AssertionError("Presentation target is not display");
        Trace.draws.add(new Trace.Draw(Trace.targets.get(previous).source,
                Trace.targets.get(current).source, fraction));
        Trace.events.add("draw");
        return Trace.drawWorks;
    }
    public void release() { Trace.releases++; }
}
""",
    "com/winlator/renderer/GLRenderer.java": """
package com.winlator.renderer;
public final class GLRenderer {
    public int surfaceWidth=1280, surfaceHeight=720;
    public boolean viewportNeedsUpdate;
    public long source;
    public final Composer effectComposer=new Composer();
    public static final class Composer {
        public long revision;
        public long getVisualRevision() { return revision; }
    }
    public void drawProcessedSceneTo(int framebuffer) {
        Trace.targets.get(framebuffer).source=source;
        Trace.captures++; Trace.events.add("capture:"+source);
    }
    public void drawFrameGenerationCursor() {
        if (Trace.events.isEmpty() || !Trace.events.get(Trace.events.size()-1).equals("draw"))
            throw new AssertionError("Cursor must be drawn after the generated/real scene");
        Trace.events.add("cursor");
    }
    public void logFrameGeneration(String text) {}
}
""",
}


HARNESS = """
package com.winlator.renderer;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;

public final class FramePresenterCheck {
    private static final long BASE=1_000_000_000L, DISPLAY=16_666_667L, SOURCE=2*DISPLAY;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static boolean render(FrameGenerationPresenter presenter, GLRenderer renderer,
            long now, long serial, long timestamp, long display) {
        renderer.source=serial;
        return presenter.render(renderer, now, serial, timestamp, display);
    }
    private static void warm(FrameGenerationPresenter presenter, GLRenderer renderer,
            long startSerial, long startTime, long period) {
        for (int i=0; i<4; i++)
            render(presenter, renderer, startTime+i*period, startSerial+i, startTime+i*period, DISPLAY);
    }
    private static void steadyAndDrain() {
        Trace.reset();
        FrameGenerationPresenter p=new FrameGenerationPresenter(); GLRenderer r=new GLRenderer();
        double previous=Double.NEGATIVE_INFINITY;
        int submitted=0;
        for (int i=0; i<32; i++) {
            long serial=1+i/2, timestamp=BASE+(i/2)*SOURCE, now=BASE+i*DISPLAY;
            boolean active=render(p, r, now, serial, timestamp, DISPLAY);
            double value=active ? Trace.draws.get(Trace.draws.size()-1).displayed : serial;
            require(value>=previous, "Content rewound at vsync "+i+": "+previous+" -> "+value+" "+Trace.draws);
            previous=value;
            if (active) submitted++;
        }
        require(Trace.allocations==2 && Trace.storageBytes==1280L*720*8,
                "Stable source did not reuse exactly two RGBA history textures");
        require(Trace.captures==13, "Warmup/source identity captured duplicate display callbacks: "+Trace.captures);
        require(Trace.preparations==12, "Flow was not prepared once per complete eligible pair: "+Trace.preparations);
        require(Trace.events.stream().filter(x -> x.equals("cursor")).count()==submitted,
                "Generated presentations omitted the live cursor");
        boolean previousEndpoint=false, midpoint=false, currentEndpoint=false;
        for (Trace.Draw draw: Trace.draws) {
            previousEndpoint|=draw.fraction==0f;
            midpoint|=draw.fraction==0.5f;
            currentEndpoint|=draw.fraction==1f;
            require(draw.current>=draw.previous, "Captured source identity moved backwards");
        }
        require(previousEndpoint && midpoint && currentEndpoint,
                "Trace missed previous/midpoint/current endpoints: "+Trace.draws);
        int captures=Trace.captures, prepares=Trace.preparations;
        long lastTime=BASE+15*SOURCE;
        render(p,r,lastTime+DISPLAY,15,lastTime-SOURCE,DISPLAY);
        require(Trace.captures==captures, "Older source serial replaced the latest captured content");
        render(p,r,lastTime+SOURCE,16,lastTime,DISPLAY);
        require(!p.needsVsync(lastTime+SOURCE), "Stalled source kept scheduling display work after current drained");
        for (int i=3; i<20; i++) render(p,r,lastTime+i*DISPLAY,16,lastTime,DISPLAY);
        require(Trace.captures==captures && Trace.preparations==prepares,
                "No-source/pointer-style redraws promoted a capture or recomputed flow");
        p.release();
        require(Trace.storageBytes==0 && Trace.destructions==2, "Off/release leaked history textures");
        System.out.println("PASS: actual 30-to-60 scheduling; monotonic identities, endpoints, single flow per pair, live cursor, duplicate redraws, finite drain");
    }
    private static void panelRate() {
        for (long period: new long[] {DISPLAY, DISPLAY/2}) {
            Trace.reset(); FrameGenerationPresenter p=new FrameGenerationPresenter(); GLRenderer r=new GLRenderer();
            for (int i=0; i<100; i++)
                require(!render(p,r,BASE+i*period,i+1,BASE+i*period,DISPLAY),
                        "Panel-rate source unexpectedly entered interpolation");
            require(Trace.allocations==0 && Trace.preparations==0 && Trace.captures==0,
                    "60+ FPS source allocated history or did GPU generation work");
            require(!p.needsVsync(BASE+100*period), "Ineligible source requested synthetic vsync");
            p.release();
        }
        Trace.reset(); FrameGenerationPresenter p=new FrameGenerationPresenter(); GLRenderer r=new GLRenderer();
        warm(p,r,1,BASE,SOURCE);
        require(Trace.allocations==2, "Slow source did not establish history before rate change");
        for (int i=1;i<=10;i++)
            require(!render(p,r,BASE+3*SOURCE+i*DISPLAY,4+i,BASE+3*SOURCE+i*DISPLAY,DISPLAY),
                    "Source that accelerated to panel rate still generated frames");
        require(Trace.allocations==2 && Trace.destructions==2 && Trace.storageBytes==0,
                "Panel-rate transition retained/reallocated old generation history");
        System.out.println("PASS: 60/120 FPS source on 60 Hz stays on normal rendering with zero history allocation/capture/flow");
    }
    private static void resetPaths() {
        Trace.reset(); FrameGenerationPresenter p=new FrameGenerationPresenter(); GLRenderer r=new GLRenderer();
        warm(p,r,1,BASE,SOURCE);
        render(p,r,BASE+4*SOURCE,5,BASE+4*SOURCE,DISPLAY);
        int oldCaptures=Trace.captures;
        r.effectComposer.revision++;
        require(!render(p,r,BASE+4*SOURCE+DISPLAY,5,BASE+4*SOURCE,DISPLAY),
                "Visual change retained eligible stale pair");
        require(Trace.captures==oldCaptures, "Visual revision reset promoted an unchanged source");
        for (int i=5;i<=7;i++) render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY);
        require(Trace.captures==oldCaptures+1, "Visual revision did not rewarm from actual new source cadence");
        r.surfaceWidth=1920; r.surfaceHeight=1080;
        int beforeResize=Trace.captures;
        for (int i=8;i<=12;i++) render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY);
        require(Trace.captures>=beforeResize+2, "Resize reset repeatedly erased warmup and never resumed capture");
        require(Trace.storageBytes==1920L*1080*8, "Resize retained wrong-sized history");
        int deleted=Trace.destructions, captures=Trace.captures;
        EGL14.current=new EGLContext(EGL14.current.id+1);
        for (int i=13;i<=17;i++) render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY);
        require(Trace.destructions==deleted, "Context replacement deleted names owned by the old context");
        require(Trace.captures>=captures+2, "New context failed to rewarm and recreate history");
        require(!p.hasFailed(), "Benign reset caused permanent bypass");
        p.resetHistory();
        require(!p.needsVsync(BASE+18*SOURCE), "Pause/history reset retained pending generation");
        System.out.println("PASS: visual revision, resize, context replacement and pause reset history and rewarm safely");
    }
    private static void memoryAndFailures() {
        Trace.reset(); FrameGenerationPresenter p=new FrameGenerationPresenter(); GLRenderer r=new GLRenderer();
        r.surfaceWidth=3072; r.surfaceHeight=2048; warm(p,r,1,BASE,SOURCE);
        require(!p.hasFailed() && Trace.storageBytes==48L*1024*1024,
                "Exact 48 MiB history limit was rejected or calculated incorrectly");
        p.release(); require(Trace.storageBytes==0, "Limit-size release leaked targets");
        Trace.reset(); p=new FrameGenerationPresenter(); r=new GLRenderer();
        r.surfaceWidth=3073; r.surfaceHeight=2048; warm(p,r,1,BASE,SOURCE);
        require(p.hasFailed() && Trace.allocations==0 && p.getFailureReason()!=null,
                "Oversized history did not fail open before allocation");
        for (int i=4;i<10;i++) render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY);
        require(Trace.allocations==0, "Oversized failure retried allocation every frame");
        Trace.reset(); p=new FrameGenerationPresenter(); r=new GLRenderer();
        GLES20.framebufferComplete=false; warm(p,r,1,BASE,SOURCE);
        require(p.hasFailed() && Trace.allocations==1 && Trace.destructions==1,
                "Incomplete history FBO did not release and fail open");
        int attempts=GLES20.checks;
        for (int i=4;i<10;i++) require(!render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY),
                "Incomplete FBO failure claimed generated presentation");
        require(GLES20.checks==attempts && Trace.captures==0,
                "Incomplete FBO failure kept retrying or captured unsupported history");
        Trace.reset(); p=new FrameGenerationPresenter(); r=new GLRenderer();
        warm(p,r,1,BASE,SOURCE); Trace.drawWorks=false;
        require(!render(p,r,BASE+4*SOURCE,5,BASE+4*SOURCE,DISPLAY) && p.hasFailed(),
                "Interpolation draw failure did not fall back to normal rendering");
        int draws=Trace.draws.size();
        render(p,r,BASE+4*SOURCE+DISPLAY,5,BASE+4*SOURCE,DISPLAY);
        require(Trace.draws.size()==draws, "Failed interpolation draw retried in the same context");
        Trace.reset(); p=new FrameGenerationPresenter(); r=new GLRenderer();
        warm(p,r,1,BASE,SOURCE);
        render(p,r,BASE+4*SOURCE,5,BASE+4*SOURCE,DISPLAY);
        Trace.prepareWorks=false;
        require(!render(p,r,BASE+4*SOURCE+DISPLAY,5,BASE+4*SOURCE,DISPLAY) && p.hasFailed(),
                "Motion preparation failure did not fall back to normal rendering");
        int failedPreparations=Trace.preparations;
        for (int i=5;i<10;i++) render(p,r,BASE+i*SOURCE,i+1,BASE+i*SOURCE,DISPLAY);
        require(Trace.preparations==failedPreparations && !p.needsVsync(BASE+10*SOURCE),
                "Failed motion preparation kept retrying/scheduling in the same context");
        System.out.println("PASS: 48 MiB bound, oversized allocation avoidance, incomplete FBO cleanup, persistent draw/prepare failure bypass");
    }
    public static void main(String[] args) {
        steadyAndDrain(); panelRate(); resetPaths(); memoryAndFailures();
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    production = repo / "app/src/main/java/com/winlator/renderer"
    with tempfile.TemporaryDirectory(prefix="tsoa-frame-presenter-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, source in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source, encoding="utf-8")
        (sources / "FramePresenterCheck.java").write_text(HARNESS, encoding="utf-8")
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options", "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
            str(production / "FrameGenerationPresenter.java"),
            str(production / "FrameGenerationClock.java"),
        ], check=True)
        subprocess.run([
            args.java, "-cp", str(classes), "com.winlator.renderer.FramePresenterCheck",
        ], check=True)


if __name__ == "__main__":
    main()
