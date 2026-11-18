#!/usr/bin/env python3
"""Exercise the production game-color lifecycle without Android or GPU dependencies.

Requires Python 3 and JDK 17 or newer. The renderer and material interfaces are
stubbed; ColorEffect, Effect and TsoaGameColor use their actual Java sources.
GLSL compilation and rendering are covered by the separate GLES validation.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "com/winlator/renderer/material/ShaderMaterial.java": """
package com.winlator.renderer.material;
import java.util.HashMap;
import java.util.Map;
public class ShaderMaterial {
    public static class Uniform {
        public final String name;
        public Uniform(String name) { this.name = name; }
    }
    public final Map<String, Float> values = new HashMap<>();
    protected String getFragmentShader() { return ""; }
    public void use() {}
    public void setUniformInt(Uniform uniform, int value) {
        values.put(uniform.name, (float)value);
    }
    public void setUniformFloat(Uniform uniform, float value) {
        values.put(uniform.name, value);
    }
}
""",
    "com/winlator/renderer/material/ScreenMaterial.java": """
package com.winlator.renderer.material;
public class ScreenMaterial extends ShaderMaterial {}
""",
    "com/winlator/renderer/GLRenderer.java": """
package com.winlator.renderer;
import com.winlator.renderer.effects.Effect;
import java.util.ArrayList;
public class GLRenderer {
    public final Composer effectComposer = new Composer();
    public static class Composer {
        public final ArrayList<Effect> effects = new ArrayList<>();
        public int renderRequests;
        public int bloomReleaseRequests;
        public void releaseBloomResources() { bloomReleaseRequests++; }
        public <T extends Effect> T getEffect(Class<T> type) {
            for (Effect effect : effects) {
                if (effect.getClass() == type) return type.cast(effect);
            }
            return null;
        }
        public void addEffect(Effect effect) {
            if (!effects.contains(effect)) effects.add(effect);
            renderRequests++;
        }
        public void removeEffect(Effect effect) {
            effects.remove(effect);
            renderRequests++;
        }
    }
}
""",
}


HARNESS = """
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.tsoa.TsoaGameColor;

public class TsoaGameColorCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void neutralControls(ColorEffect effect) {
        require(effect.getBrightness() == 0.0f && effect.getContrast() == 0.0f
                && effect.getGamma() == 1.0f, "Preset changed legacy controls");
    }

    private static void uniform(ScreenMaterial material, String name, float value) {
        require(material.values.containsKey(name), "Missing uniform: " + name);
        require(material.values.get(name) == value, "Wrong uniform: " + name);
    }

    public static void main(String[] args) {
        for (int mode : new int[] {0, 1, 2, -1, 3}) TsoaGameColor.apply(null, mode);
        TsoaGameColor.applyBloom(null, false);
        TsoaGameColor.applyBloom(null, true);
        ColorEffect bounds = new ColorEffect();
        require(!bounds.isBloomEnabled(), "Bloom must default Off");
        require(bounds.getHdrStrength() == 0.0f, "Manual effects must default to HDR Off");
        bounds.setHdrStrength(-3.0f);
        require(bounds.getHdrStrength() == 0.0f, "Negative strength escaped bounds");
        bounds.setHdrStrength(4.0f);
        require(bounds.getHdrStrength() == 1.0f, "Large strength escaped bounds");
        bounds.setHdrStrength(Float.NaN);
        require(bounds.getHdrStrength() == 0.0f, "NaN reached shader strength");
        bounds.setHdrStrength(Float.POSITIVE_INFINITY);
        require(bounds.getHdrStrength() == 1.0f, "Positive infinity reached shader strength");
        bounds.setHdrStrength(Float.NEGATIVE_INFINITY);
        require(bounds.getHdrStrength() == 0.0f, "Negative infinity reached shader strength");
        ScreenMaterial manualMaterial = bounds.getMaterial();
        manualMaterial.use();
        uniform(manualMaterial, "hdrStrength", 0.0f);
        System.out.println("PASS: manual default Off, bounded/nonfinite strength inputs, null renderer");

        GLRenderer renderer = new GLRenderer();
        Effect otherEffect = new Effect() {};
        renderer.effectComposer.addEffect(otherEffect);
        int beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.effects.size() == 1,
                "Off added a color pass");
        require(renderer.effectComposer.renderRequests == beforeOff,
                "Fresh Off requested an unnecessary render");

        TsoaGameColor.apply(renderer, 1);
        ColorEffect effect = renderer.effectComposer.getEffect(ColorEffect.class);
        require(effect != null && effect.getHdrStrength() == 0.45f,
                "Suave preset strength changed");
        neutralControls(effect);
        ScreenMaterial material = effect.getMaterial();
        material.use();
        uniform(material, "hdrStrength", 0.45f);
        uniform(material, "brightness", 0.0f);
        uniform(material, "contrast", 0.0f);
        uniform(material, "gamma", 1.0f);

        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == effect,
                "Mode switch replaced the existing color effect");
        require(effect.getMaterial() == material, "Mode switch replaced the color material");
        require(effect.getHdrStrength() == 1.0f && renderer.effectComposer.effects.size() == 2,
                "Marcante added another color pass or has wrong strength");
        neutralControls(effect);
        material.use();
        uniform(material, "hdrStrength", 1.0f);
        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.effects.size() == 2,
                "Repeated preset application duplicated the color pass");
        System.out.println("PASS: Suave/Marcante reuse one effect and material; live uniform upload");

        TsoaGameColor.apply(renderer, 0);
        require(effect.getHdrStrength() == 0.0f,
                "Off left automatic HDR strength active");
        require(renderer.effectComposer.effects.size() == 1
                && renderer.effectComposer.effects.contains(otherEffect),
                "Off removed another effect or retained the neutral automatic color pass");

        ColorEffect manual = new ColorEffect();
        manual.setBrightness(0.1f);
        manual.setContrast(-0.2f);
        manual.setGamma(1.2f);
        renderer.effectComposer.addEffect(manual);
        beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual
                && manual.getBrightness() == 0.1f && manual.getContrast() == -0.2f
                && manual.getGamma() == 1.2f,
                "Off changed a manual-only color effect");
        require(renderer.effectComposer.renderRequests == beforeOff,
                "Off touched a manual-only effect");

        TsoaGameColor.apply(renderer, 2);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual,
                "Preset failed to reuse the manual color pass");
        neutralControls(manual);
        manual.setBrightness(0.07f);
        manual.setContrast(0.13f);
        manual.setGamma(1.1f);
        beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == manual
                && manual.getHdrStrength() == 0.0f && manual.getBrightness() == 0.07f
                && manual.getContrast() == 0.13f && manual.getGamma() == 1.1f,
                "Off discarded manual edits while clearing automatic HDR");
        require(renderer.effectComposer.renderRequests > beforeOff,
                "Clearing automatic HDR did not request a redraw");
        require(renderer.effectComposer.effects.contains(otherEffect),
                "Clearing automatic HDR changed an unrelated effect");
        manual.getMaterial().use();
        uniform(manual.getMaterial(), "hdrStrength", 0.0f);
        uniform(manual.getMaterial(), "brightness", 0.07f);
        uniform(manual.getMaterial(), "contrast", 0.13f);
        uniform(manual.getMaterial(), "gamma", 1.1f);

        for (int invalidMode : new int[] {-1, 3, 999}) {
            TsoaGameColor.apply(renderer, 1);
            TsoaGameColor.apply(renderer, invalidMode);
            require(renderer.effectComposer.getEffect(ColorEffect.class) == null,
                    "Unknown mode did not default to Off: " + invalidMode);
            require(renderer.effectComposer.effects.size() == 1
                    && renderer.effectComposer.effects.contains(otherEffect),
                    "Unknown mode changed an unrelated effect");
        }
        System.out.println("PASS: Off clears automatic HDR, preserves manual edits and other effects; unknown modes Off");

        beforeOff = renderer.effectComposer.renderRequests;
        TsoaGameColor.applyBloom(renderer, false);
        require(renderer.effectComposer.renderRequests == beforeOff,
                "Fresh bloom Off requested an unnecessary render");
        TsoaGameColor.applyBloom(renderer, true);
        ColorEffect bloom = renderer.effectComposer.getEffect(ColorEffect.class);
        require(bloom != null && bloom.isBloomEnabled() && bloom.getHdrStrength() == 0.0f,
                "Bloom alone must work with HDR Off");
        neutralControls(bloom);
        ScreenMaterial bloomMaterial = bloom.getMaterial();
        bloom.setBloomTextureAvailable(true);
        bloomMaterial.use();
        uniform(bloomMaterial, "bloomStrength", 0.48f);
        uniform(bloomMaterial, "bloomTexture", 1.0f);
        bloom.setBloomTextureAvailable(false);
        bloomMaterial.use();
        uniform(bloomMaterial, "bloomStrength", 0.0f);
        TsoaGameColor.apply(renderer, 2);
        TsoaGameColor.apply(renderer, 0);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == bloom
                && bloom.isBloomEnabled() && bloom.getHdrStrength() == 0.0f,
                "Turning HDR Off removed bloom");
        TsoaGameColor.applyBloom(renderer, false);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == null,
                "Both Off retained a neutral color pass");
        require(renderer.effectComposer.bloomReleaseRequests == 1,
                "Bloom Off did not request GL-thread cleanup");

        TsoaGameColor.apply(renderer, 1);
        ColorEffect edited = renderer.effectComposer.getEffect(ColorEffect.class);
        edited.setHdrStrength(0.67f);
        edited.setBrightness(0.04f);
        edited.setContrast(-0.11f);
        edited.setGamma(1.21f);
        ScreenMaterial editedMaterial = edited.getMaterial();
        TsoaGameColor.applyBloom(renderer, true);
        TsoaGameColor.applyBloom(renderer, true);
        TsoaGameColor.applyBloom(renderer, false);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == edited
                && edited.getMaterial() == editedMaterial
                && edited.getHdrStrength() == 0.67f && edited.getBrightness() == 0.04f
                && edited.getContrast() == -0.11f && edited.getGamma() == 1.21f,
                "Bloom toggle reset HDR/manual edits or replaced its shared pass");
        require(renderer.effectComposer.effects.size() == 2
                && renderer.effectComposer.effects.contains(otherEffect),
                "Bloom toggle duplicated its pass or changed another effect");
        edited.setHdrStrength(0.0f);
        TsoaGameColor.applyBloom(renderer, true);
        TsoaGameColor.applyBloom(renderer, false);
        require(renderer.effectComposer.getEffect(ColorEffect.class) == edited,
                "Bloom Off discarded manual-only adjustments");
        editedMaterial.use();
        uniform(editedMaterial, "bloomStrength", 0.0f);
        uniform(editedMaterial, "brightness", 0.04f);
        uniform(editedMaterial, "hdrStrength", 0.0f);
        System.out.println("PASS: Bloom alone, HDR-Off preservation, 0/0 pass removal, shared material, manual/HDR preservation, cleanup request");
    }
}
"""


# Trace the actual Java helper/composer without an Android runtime. The separate
# GLES runner checks the exact production shaders on a real surfaceless context.
LIFECYCLE_STUBS = {
    "com/winlator/renderer/PresentationEffects.java": """
package com.winlator.renderer;
class PresentationEffects {
    public void release() {}
    public boolean draw(GLRenderer r,RenderTarget input,int output,int x,int y,int w,int h,
                        int quality,float amount,float first,float second) {return false;}
    public boolean drawBorders(GLRenderer r,RenderTarget input,int output,int x,int y,int w,int h,
               int quality,long revision,float amount,float blur,float brightness,float saturation) {return false;}
}
""",
    "com/winlator/renderer/SceneReconstruction.java": """
package com.winlator.renderer;
class SceneReconstruction {
    boolean available(int w,int h){return true;}
    boolean draw(int g,int l,GLRenderer r,int in,RenderTarget out,int x,int y,int w,int h){
        throw new AssertionError("Reconstruction disabled in legacy filters test");
    }
    void release(){}
}
""",
    "android/opengl/EGLContext.java": """
package android.opengl;
public final class EGLContext {
    public final int id;
    public EGLContext(int id) { this.id = id; }
    public boolean equals(Object other) { return other instanceof EGLContext && ((EGLContext)other).id == id; }
    public int hashCode() { return id; }
}
""",
    "android/opengl/EGL14.java": """
package android.opengl;
public class EGL14 {
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext(0);
    public static EGLContext current = new EGLContext(1);
    public static EGLContext eglGetCurrentContext() { return current; }
}
""",
    "android/opengl/GLES20.java": """
package android.opengl;
import java.util.*;
public class GLES20 {
    public static final int GL_FRAMEBUFFER=1, GL_FRAMEBUFFER_BINDING=2, GL_VIEWPORT=3,
        GL_ACTIVE_TEXTURE=4, GL_TEXTURE_BINDING_2D=5, GL_CURRENT_PROGRAM=6,
        GL_TEXTURE0=10, GL_TEXTURE1=11, GL_TEXTURE_2D=12, GL_RGBA=13,
        GL_UNSIGNED_BYTE=14, GL_TEXTURE_MIN_FILTER=15, GL_TEXTURE_MAG_FILTER=16,
        GL_LINEAR=17, GL_TEXTURE_WRAP_S=18, GL_TEXTURE_WRAP_T=19, GL_CLAMP_TO_EDGE=20,
        GL_COLOR_ATTACHMENT0=21, GL_FRAMEBUFFER_COMPLETE=22, GL_TRIANGLE_STRIP=23,
        GL_BLEND=24, GL_COLOR_BUFFER_BIT=25;
    public static int next=100, fbo=0, active=GL_TEXTURE0, program=0, calls, deletes;
    public static int framebufferStatus=GL_FRAMEBUFFER_COMPLETE;
    public static boolean blend=true;
    public static int[] viewport={7,9,2400,1080};
    public static final Map<Integer,Integer> bound = new HashMap<>();
    public static final Map<Integer,int[]> sizes = new HashMap<>();
    public static final List<int[]> draws = new ArrayList<>();
    public static final List<Integer> deleted = new ArrayList<>();
    private static void call() {
        if (EGL14.current.equals(EGL14.EGL_NO_CONTEXT)) throw new AssertionError("GL call without context");
        calls++;
    }
    public static void glGenTextures(int n,int[] out,int offset) {
        call(); for(int i=0;i<n;i++)out[offset+i]=next++;
    }
    public static void glGenFramebuffers(int n,int[] out,int offset) {
        call(); for(int i=0;i<n;i++)out[offset+i]=next++;
    }
    public static void glDeleteTextures(int n,int[] ids,int offset) {
        call(); for(int i=0;i<n;i++)if(ids[offset+i]!=0){deletes++;deleted.add(ids[offset+i]);sizes.remove(ids[offset+i]);}
    }
    public static void glDeleteFramebuffers(int n,int[] ids,int offset) {
        call(); for(int i=0;i<n;i++)if(ids[offset+i]!=0){deletes++;deleted.add(ids[offset+i]);}
    }
    public static void glBindTexture(int target,int texture) { call();bound.put(active,texture); }
    public static void glBindFramebuffer(int target,int value) { call();fbo=value; }
    public static void glTexImage2D(int target,int level,int internal,int width,int height,int border,int format,int type,Object data) {
        call();sizes.put(bound.get(active),new int[]{width,height,internal});
    }
    public static void glTexParameteri(int target,int name,int value) { call(); }
    public static void glFramebufferTexture2D(int target,int attachment,int textarget,int texture,int level) { call(); }
    public static int glCheckFramebufferStatus(int target) {call();return framebufferStatus;}
    public static void glActiveTexture(int texture) {call();active=texture;}
    public static void glViewport(int x,int y,int w,int h) {call();viewport=new int[]{x,y,w,h};}
    public static void glGetIntegerv(int name,int[] out,int offset) {
        call();
        if(name==GL_VIEWPORT)System.arraycopy(viewport,0,out,offset,4);
        else out[offset]=name==GL_FRAMEBUFFER_BINDING?fbo:name==GL_ACTIVE_TEXTURE?active:
            name==GL_CURRENT_PROGRAM?program:bound.getOrDefault(active,0);
    }
    public static boolean glIsEnabled(int name) {call();return blend;}
    public static void glDisable(int name) {call();blend=false;}
    public static void glEnable(int name) {call();blend=true;}
    public static void glUseProgram(int value) {call();program=value;}
    public static void glDrawArrays(int mode,int start,int count) {
        call();draws.add(new int[]{viewport[2],viewport[3],fbo,bound.getOrDefault(active,0),program,viewport[0],viewport[1]});
    }
    public static void glClear(int mask) {call();}
    public static int glGetError() {call();return 0;}
}
""",
    "android/util/Log.java": """
package android.util;
public class Log { public static int i(String tag,String text) { return 0; } public static int w(String tag,String text){return 0;} }
""",
    "com/winlator/core/TsoaRedirectorV2Log.java": """
package com.winlator.core;
public class TsoaRedirectorV2Log { public static void append(Object context,String text) {} }
""",
    "com/winlator/renderer/material/ShaderMaterial.java": """
package com.winlator.renderer.material;
import android.opengl.GLES20;
public class ShaderMaterial {
    public int programId;
    public static class Uniform { public final String name; public Uniform(String name){this.name=name;} }
    protected String getFragmentShader(){return "";}
    public static final java.util.Map<Integer,String> shaders=new java.util.HashMap<>();
    public void use(){if(programId==0){programId=GLES20.next++;shaders.put(programId,getFragmentShader());}GLES20.glUseProgram(programId);}
    public void destroy(){programId=0;}
    public void setUniformFloat(Uniform uniform,float value){}
    public void setUniformInt(Uniform uniform,int value){}
    public void setUniformVec2(Uniform uniform,float x,float y){}
}
""",
    "com/winlator/renderer/material/ScreenMaterial.java": """
package com.winlator.renderer.material;
public class ScreenMaterial extends ShaderMaterial {
    public final Uniforms uniforms=new Uniforms();
    public static class Uniforms {
        public final Uniform resolution=new Uniform("resolution"), screenTexture=new Uniform("screenTexture");
    }
}
""",
    "com/winlator/renderer/RenderTarget.java": """
package com.winlator.renderer;
import android.opengl.GLES20;
public class RenderTarget {
    private int texture, fbo, format;
    public void setFormat(int value){format=value;}
    public void allocateFramebuffer(int w,int h){
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];
        GLES20.glGenFramebuffers(1,ids,0);fbo=ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,format,w,h,0,format,GLES20.GL_UNSIGNED_BYTE,null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);
    }
    public void destroy(){
        GLES20.glDeleteTextures(1,new int[]{texture},0);
        GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);
        texture=fbo=0;
    }
    public int getTextureId(){return texture;}
    public int getFramebuffer(){return fbo;}
}
""",
    "com/winlator/renderer/GLRenderer.java": """
package com.winlator.renderer;
import java.util.*;
public class GLRenderer {
    public final EffectComposer effectComposer=new EffectComposer(this);
    public int surfaceWidth=2400,surfaceHeight=1080;
    public int gameWidth=960,gameHeight=544,nativeCaptures,normalFrames;
    public boolean fullscreen;
    public final ViewTransformation viewTransformation=new ViewTransformation();
    public boolean viewportNeedsUpdate;
    public int getGameWidth(){return gameWidth;}
    public int getGameHeight(){return gameHeight;}
    public boolean isFullscreen(){return fullscreen;}
    public float getMagnifierZoom(){return 1;}
    public boolean isScreenOffsetYRelativeToCursor(){return false;}
    public void drawNativeFrame(){
        nativeCaptures++;
        android.opengl.GLES20.glViewport(0,0,gameWidth,gameHeight);
        nativeFramebuffers.add(android.opengl.GLES20.fbo);
        viewportNeedsUpdate=true;
    }
    public final List<Integer> nativeFramebuffers=new ArrayList<>();
    public static class ViewTransformation {
        public int viewWidth=1906,viewHeight=1080,viewOffsetX=247,viewOffsetY=0;
    }
    public final Quad quadVertices=new Quad();
    public final View xServerView=new View();
    public void drawFrame(){normalFrames++;}
    public void frame(){if(effectComposer.hasEffects())effectComposer.render();else drawFrame();}
    public static class Quad { public void bind(int program){} public int count(){return 4;} }
    public static class View {
        public final List<Runnable> events=new ArrayList<>();
        public void requestRender(){}
        public void queueEvent(Runnable runnable){events.add(runnable);}
        public void post(Runnable runnable){runnable.run();}
        public Object getContext(){return this;}
        public void drain(){for(Runnable r:new ArrayList<>(events))r.run();events.clear();}
    }
}
""",
}

LIFECYCLE_HARNESS = """
import android.opengl.*;
import com.winlator.renderer.*;
import com.winlator.renderer.effects.*;
import com.winlator.tsoa.TsoaGameColor;
import java.util.*;
public class BloomLifecycleCheck {
    private static void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    public static void main(String[] args) {
        GLRenderer renderer=new GLRenderer();
        renderer.frame();
        require(GLES20.calls==0,"Bloom/HDR Off performed GL work");
        require(SoftBloom.quarterDimension(2400)==600 && SoftBloom.quarterDimension(1080)==270
            && SoftBloom.quarterDimension(129)==33 && SoftBloom.quarterDimension(1)==1,"Quarter dimensions");
        SoftBloom helper=new SoftBloom();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,91);
        GLES20.glViewport(7,9,2400,1080);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,72);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,73);
        GLES20.glUseProgram(92);
        int bloom=helper.render(renderer,90,2400,1080);
        require(bloom!=0 && helper.getWidth()==600 && helper.getHeight()==270,"Private allocation dimensions");
        require(helper.getStorageBytes()==1296000L,"Two RGBA8 targets must use 1,296,000 bytes");
        require(GLES20.draws.size()==3,"Helper must draw exactly three prepasses");
        for(int[] draw:GLES20.draws)require(draw[0]==600&&draw[1]==270,"Full-size bloom prepass");
        require(GLES20.fbo==91 && Arrays.equals(GLES20.viewport,new int[]{7,9,2400,1080})
            && GLES20.active==GLES20.GL_TEXTURE1 && GLES20.bound.get(GLES20.GL_TEXTURE0)==72
            && GLES20.bound.get(GLES20.GL_TEXTURE1)==73 && GLES20.program==92 && GLES20.blend,
            "Bloom did not restore framebuffer/viewport/textures/program/blending");
        require(GLES20.sizes.get(bloom)[2]==GLES20.GL_RGBA,"Private target must use standard RGBA");
        int next=GLES20.next;
        helper.render(renderer,90,2400,1080);
        require(GLES20.next==next,"Steady-state bloom allocated resources again");
        int deleted=GLES20.deletes;
        helper.render(renderer,90,129,129);
        require(GLES20.deletes==deleted+4 && helper.getWidth()==33 && helper.getHeight()==33,
            "Resize must delete two old textures/FBOs and use actual surface dimensions");
        deleted=GLES20.deletes;
        EGL14.current=new EGLContext(2);
        int newBloom=helper.render(renderer,90,129,129);
        require(GLES20.deletes==deleted && newBloom!=bloom,"New context deleted stale names or reused old IDs");
        helper.release();
        require(GLES20.deletes==deleted+4 && helper.getStorageBytes()==0,"Release did not free private targets");
        System.out.println("PASS: actual SoftBloom quarter RGBA8 allocation, 3 passes, memory, resize, context names and GL state restoration");

        GLES20.draws.clear();
        TsoaGameColor.applyBloom(renderer,true);
        renderer.frame();
        require(GLES20.draws.size()==4,"Bloom pipeline must use 3 low-size + 1 existing full-size pass");
        for(int i=0;i<3;i++)require(GLES20.draws.get(i)[0]==600&&GLES20.draws.get(i)[1]==270,"Pipeline prepass dimensions");
        require(GLES20.draws.get(3)[0]==2400&&GLES20.draws.get(3)[1]==1080,"Composite must reuse existing pass");
        require(GLES20.active==GLES20.GL_TEXTURE0 && GLES20.bound.get(GLES20.GL_TEXTURE1)==0,"Composite leaked bloom texture binding");
        TsoaGameColor.applyBloom(renderer,false);
        deleted=GLES20.deletes;
        int calls=GLES20.calls;
        EGL14.current=EGL14.EGL_NO_CONTEXT;
        renderer.xServerView.drain();
        require(GLES20.calls==calls && GLES20.deletes==deleted,"Paused Off event performed GL calls without EGL");
        EGL14.current=new EGLContext(2);
        renderer.frame();
        require(GLES20.deletes==deleted+4,"Resume without effects failed to release preserved-context bloom targets");
        calls=GLES20.calls;renderer.frame();
        require(GLES20.calls==calls,"Steady Off kept an active postfilter or release polling GL calls");

        TsoaGameColor.applyBloom(renderer,true);renderer.frame();
        TsoaGameColor.applyBloom(renderer,false);
        EGL14.current=EGL14.EGL_NO_CONTEXT;renderer.xServerView.drain();
        deleted=GLES20.deletes;
        EGL14.current=new EGLContext(3);renderer.frame();
        require(GLES20.deletes==deleted,"New-context deferred cleanup deleted stale GL object names");
        require(renderer.effectComposer.getEffect(ColorEffect.class)==null,"Both Off retained color effect");
        System.out.println("PASS: actual composer 3 low-size/1 existing composite, Off no work, paused cleanup defers until EGL resume, new-context stale IDs abandoned");
    }
}
"""


def run_lifecycle(java, production):
    with tempfile.TemporaryDirectory(prefix="tsoa-bloom-lifecycle-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in LIFECYCLE_STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        (sources / "BloomLifecycleCheck.java").write_text(LIFECYCLE_HARNESS, encoding="utf-8")
        classes.mkdir()
        subprocess.run([
            java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-Xlint:-options",
            "-d", str(classes), *map(str, sorted(sources.rglob("*.java"))),
            *[str(production / relative) for relative in [
                "renderer/effects/Effect.java", "renderer/effects/ColorEffect.java",
                "renderer/EffectComposer.java", "renderer/SoftBloom.java", "tsoa/TsoaGameColor.java",
                "renderer/SpatialUpscaler.java", "renderer/SpatialShaders.java",
                "renderer/effects/FXAAEffect.java", "renderer/effects/SharpeningEffect.java",
            ]],
        ], check=True)
        subprocess.run([java, "-cp", str(classes), "BloomLifecycleCheck"], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    production = repo / "app/src/main/java/com/winlator"
    with tempfile.TemporaryDirectory(prefix="tsoa-game-color-test-") as name:
        temporary = Path(name)
        sources = temporary / "src"
        classes = temporary / "classes"
        for relative, text in STUBS.items():
            target = sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        harness = sources / "TsoaGameColorCheck.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes.mkdir()
        subprocess.run([
            args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main",
            "-source", "8", "-target", "8", "-Xlint:-options",
            "-d", str(classes),
            *map(str, sorted(sources.rglob("*.java"))),
            str(production / "renderer/effects/Effect.java"),
            str(production / "renderer/effects/ColorEffect.java"),
            str(production / "tsoa/TsoaGameColor.java"),
        ], check=True)
        subprocess.run([
            args.java, "-cp", str(classes), "TsoaGameColorCheck",
        ], check=True)

    run_lifecycle(args.java, production)


if __name__ == "__main__":
    main()
