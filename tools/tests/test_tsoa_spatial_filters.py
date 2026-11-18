#!/usr/bin/env python3
"""Check persisted optional game-filter settings against actual production Java.

Uses Python 3 and JDK 17+, with Android interfaces doubled by the menu test.
GPU shader compilation/rendering is validated independently on GLES3.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
from test_tsoa_menu_language import STUBS
from test_tsoa_game_color import LIFECYCLE_STUBS

HARNESS = r'''
import android.content.Context;
import com.winlator.tsoa.TsoaOptions;
import java.util.HashMap;
import java.util.Map;

public final class TsoaSpatialOptionsCheck {
    private static void require(boolean state, String message) {
        if (!state) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Context context = new Context();
        TsoaOptions options = new TsoaOptions(context);
        require(!options.isGameFxaaEnabled(), "FXAA must default Off");
        require(!options.isGameFsrEnabled(), "FSR must default Off");
        require(options.getGameSharpeningStrength() == 0, "Sharpening must default Off");
        require(!options.isGameBloomEnabled(), "Bloom must remain optional");
        require(!options.isGameFrameGenerationEnabled(), "Frame generation must default Off");
        context.values.put("game_frame_generation_enabled", true);
        context.values.put("menu_language", "zh-CN");
        context.values.put("wine_locale", "en_US.UTF-8");
        context.values.put("game_hdr_mode", 2);
        context.values.put("game_bloom_enabled", true);
        for (boolean fxaa : new boolean[]{true, false}) {
            for (boolean fsr : new boolean[]{true, false}) {
                context.values.put("game_fxaa_enabled", fxaa);
                context.values.put("game_fsr_enabled", fsr);
                for (int strength : new int[]{Integer.MIN_VALUE, -100, -1, 0, 1, 25, 50, 75, 100, 101, 1000, Integer.MAX_VALUE}) {
                    context.values.put("game_sharpening_strength", strength);
                    Map<String,Object> snapshot = new HashMap<>(context.values);
                    TsoaOptions recreated = new TsoaOptions(context);
                    require(recreated.isGameFxaaEnabled() == fxaa, "Saved FXAA lost");
                    require(recreated.isGameFsrEnabled() == fsr, "Saved FSR lost");
                    require(recreated.getGameSharpeningStrength() == Math.max(0, Math.min(100, strength)), "Sharpness bounds");
                    require(recreated.isGameBloomEnabled() && recreated.getGameHdrMode() == 2, "Filters changed HDR/Bloom");
                    require(recreated.isGameFrameGenerationEnabled(), "Saved frame generation lost or overwritten");
                    require(recreated.getMenuLanguage().equals("zh-CN"), "Filter getters changed menu language");
                    require(context.values.equals(snapshot), "Filter getters mutated preferences");
                }
            }
        }
        System.out.println("PASS: all optional game filters default Off; persisted combinations and 0..100 sharpening bounds preserve HDR, bloom and menu/native preferences");
    }
}
'''


SPATIAL_LIFECYCLE = r"""
package com.winlator.renderer;
import android.opengl.*;
import com.winlator.renderer.effects.*;
import com.winlator.renderer.material.ShaderMaterial;
import com.winlator.tsoa.TsoaGameColor;
import java.util.*;

public final class SpatialLifecycleCheck {
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static String shader(int draw){return ShaderMaterial.shaders.get(GLES20.draws.get(draw)[4]);}
    private static boolean easu(int draw){return shader(draw).contains("FsrEasuCon");}
    private static boolean fxaa(int draw){return shader(draw).contains("lumaWeights");}
    private static boolean cas(int draw){return shader(draw).contains("green-channel");}
    private static boolean rcas(int draw){return shader(draw).contains("hitMin");}
    public static void main(String[] args) {
        require(SpatialUpscaler.canUpscale(960,544,1906,1080),"Expected actual lower-resolution upscale");
        for(int[] dims:new int[][]{{960,544,960,544},{960,544,960,1080},{960,544,1920,544},
                {1920,1080,960,544},{0,544,1920,1080},{960,0,1920,1080},{-1,544,1920,1080}})
            require(!SpatialUpscaler.canUpscale(dims[0],dims[1],dims[2],dims[3]),"Invalid/native/downscale must bypass FSR");
        GLRenderer renderer=new GLRenderer();
        renderer.frame();require(GLES20.calls==0,"All optional effects Off must perform no filter GL work");
        renderer.effectComposer.configureSpatialFilters(true,false,0);
        renderer.frame();
        require(GLES20.draws.size()==1 && fxaa(0),"FXAA alone must use one validated shader pass");
        require(renderer.nativeCaptures==0,"FXAA unexpectedly captured a lower-resolution FSR source");
        GLES20.draws.clear();
        renderer.effectComposer.configureSpatialFilters(false,false,50);
        renderer.frame();
        require(GLES20.draws.size()==1 && cas(0),"CAS alone must use one CAS pass");
        GLES20.draws.clear();
        renderer.effectComposer.configureSpatialFilters(false,true,0);
        renderer.frame();
        require(renderer.nativeCaptures==1,"FSR did not capture native guest pixels");
        require(GLES20.draws.size()==2 && easu(0) && rcas(1),"FSR requires EASU then RCAS, without extra CAS");
        int[] source=GLES20.sizes.get(GLES20.draws.get(0)[3]);
        require(Arrays.equals(source,new int[]{960,544,GLES20.GL_RGBA}),"EASU received bilinear display pixels, rather than native RGBA8 guest texture");
        require(GLES20.draws.get(0)[0]==1906&&GLES20.draws.get(0)[1]==1080
            &&GLES20.draws.get(0)[5]==247&&GLES20.draws.get(0)[6]==0,"EASU ignored fitted content viewport/offset");
        require(GLES20.draws.get(0)[2]!=renderer.nativeFramebuffers.get(0),"EASU source/target framebuffer feedback");
        int next=GLES20.next;
        renderer.frame();require(GLES20.next==next,"Steady FSR frames reallocated GL resources");
        // Changing the guest size reallocates only its native capture target.
        int deletedForResize=GLES20.deletes;
        renderer.gameWidth=800;renderer.gameHeight=450;GLES20.draws.clear();renderer.frame();
        require(GLES20.deletes==deletedForResize+2,"Guest resize leaked its old native target");
        source=GLES20.sizes.get(GLES20.draws.get(0)[3]);
        require(Arrays.equals(source,new int[]{800,450,GLES20.GL_RGBA}),"Guest resize kept stale input dimensions");
        renderer.gameWidth=960;renderer.gameHeight=544;
        GLES20.draws.clear();
        renderer.effectComposer.configureSpatialFilters(true,true,75);
        TsoaGameColor.apply(renderer,2);TsoaGameColor.applyBloom(renderer,true);
        renderer.frame();
        require(GLES20.draws.size()==7,"Combined FSR/FXAA/RCAS/quarterBloom/color pass count");
        require(easu(0)&&fxaa(1)&&rcas(2),"Spatial stage order must be EASU, FXAA, RCAS");
        for(int i=3;i<6;i++)require(GLES20.draws.get(i)[0]==600&&GLES20.draws.get(i)[1]==270,"Bloom prepass became full-size");
        require(shader(6).contains("hdrStrength"),"Color/HDR/Bloom composite missing after spatial passes");
        for(int i=0;i<GLES20.draws.size();i++)require(!cas(i),"FSR stacked CAS and RCAS");
        ColorEffect color=renderer.effectComposer.getEffect(ColorEffect.class);
        require(color!=null&&color.isBloomEnabled()&&color.getHdrStrength()==1.0f,"Spatial effects changed HDR/Bloom preference");

        // Real processed scenes must reach the separate temporal history target.
        GLES20.draws.clear();
        renderer.effectComposer.renderToFramebuffer(9876);
        require(GLES20.draws.get(GLES20.draws.size()-1)[2]==9876,
                "Final HDR/Bloom/spatial composite escaped the requested frame-history target");

        int originalNativeCaptures=renderer.nativeCaptures;
        renderer.effectComposer.configurePresentation(true,3,75,true,true,60,30,40);
        GLES20.draws.clear();
        renderer.frame();
        require(GLES20.draws.isEmpty()&&renderer.nativeCaptures==originalNativeCaptures,
                "Original Mode retained optional passes");
        renderer.effectComposer.configurePresentation(false,0,75,false,false,0,30,40);
        GLES20.draws.clear();
        renderer.frame();
        require(easu(0)&&fxaa(1)&&rcas(2)&&color.isBloomEnabled()&&color.getHdrStrength()==1.0f,
                "Leaving Original Mode failed to recover the configured effects");

        // Native-resolution FSR alone must bypass allocation and capture entirely.
        GLRenderer nativeRenderer=new GLRenderer();
        nativeRenderer.gameWidth=2400;nativeRenderer.gameHeight=1080;nativeRenderer.fullscreen=true;
        nativeRenderer.effectComposer.configureSpatialFilters(false,true,0);
        int calls=GLES20.calls;nativeRenderer.frame();
        require(GLES20.calls==calls&&nativeRenderer.nativeCaptures==0,"Native FSR created a false upscale or full-size copy");

        // Same-context Off while paused defers GL destruction until an actual resumed draw.
        TsoaGameColor.applyBloom(renderer,false);TsoaGameColor.apply(renderer,0);
        renderer.effectComposer.configureSpatialFilters(false,false,0);
        EGLContext kept=EGL14.current;EGL14.current=EGL14.EGL_NO_CONTEXT;
        calls=GLES20.calls;renderer.xServerView.drain();
        require(GLES20.calls==calls,"Paused disabling filters issued GL calls without context");
        EGL14.current=kept;int deleted=GLES20.deletes;
        renderer.frame();require(GLES20.deletes>deleted,"Resuming Off failed to release native/bloom targets");
        calls=GLES20.calls;renderer.frame();require(GLES20.calls==calls,"Steady Off retained GL postprocessing work");

        // Enabling, then losing the EGL context, must abandon all old target IDs.
        renderer.effectComposer.configureSpatialFilters(true,true,50);renderer.frame();
        renderer.effectComposer.configureSpatialFilters(false,false,0);
        EGL14.current=EGL14.EGL_NO_CONTEXT;renderer.xServerView.drain();
        deleted=GLES20.deletes;EGL14.current=new EGLContext(91);renderer.frame();
        require(GLES20.deletes==deleted,"Deferred Off after context loss deleted stale GL names");
        // A failed native capture target falls back and stays failed until resize/re-enable.
        GLRenderer failing=new GLRenderer();
        failing.effectComposer.configureSpatialFilters(false,true,0);
        GLES20.framebufferStatus=0;GLES20.draws.clear();failing.frame();
        require(failing.nativeCaptures==0&&failing.normalFrames==1,"Failed FSR target did not fall back to normal guest draw");
        next=GLES20.next;failing.frame();
        require(GLES20.next==next,"Failed FSR target reallocated on every frame");
        GLES20.framebufferStatus=GLES20.GL_FRAMEBUFFER_COMPLETE;
        failing.gameWidth=800;failing.gameHeight=450;GLES20.draws.clear();failing.frame();
        require(failing.nativeCaptures==1&&easu(0)&&rcas(1),"Guest resize did not recover a previously failed FSR target");
        System.out.println("PASS: actual composer native capture/EASU/FXAA/RCAS/CAS order, zero duplicate sharpen, all-Off/native-FSR zero work, steady resource reuse, pause/resume/context-loss cleanup, HDR/Bloom preservation");
    }
}
"""


def run_lifecycle(java, root):
    production = root / 'app/src/main/java/com/winlator'
    with tempfile.TemporaryDirectory(prefix='tsoa-spatial-lifecycle-') as directory:
        temporary = Path(directory)
        sources, classes = temporary/'src', temporary/'classes'
        for relative, text in LIFECYCLE_STUBS.items():
            target = sources/relative
            target.parent.mkdir(exist_ok=True, parents=True)
            target.write_text(text, encoding='utf-8')
        (sources/'SpatialLifecycleCheck.java').write_text(SPATIAL_LIFECYCLE, encoding='utf-8')
        classes.mkdir()
        names = ['renderer/effects/Effect.java','renderer/effects/ColorEffect.java',
                 'renderer/effects/FXAAEffect.java','renderer/effects/SharpeningEffect.java',
                 'renderer/EffectComposer.java','renderer/SpatialUpscaler.java','renderer/SpatialShaders.java',
                 'renderer/SoftBloom.java','tsoa/TsoaGameColor.java']
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8',
                        '-Xlint:-options','-d',str(classes),*map(str,sorted(sources.rglob('*.java'))),
                        *[str(production/name) for name in names]],check=True)
        subprocess.run([java,'-cp',str(classes),'com.winlator.renderer.SpatialLifecycleCheck'],check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    production = root / 'app/src/main/java/com/winlator/tsoa/TsoaOptions.java'
    with tempfile.TemporaryDirectory(prefix='tsoa-spatial-options-test-') as directory:
        temporary = Path(directory)
        sources, classes = temporary/'src', temporary/'classes'
        for relative, text in STUBS.items():
            target = sources/relative
            target.parent.mkdir(exist_ok=True, parents=True)
            target.write_text(text, encoding='utf-8')
        (sources/'TsoaSpatialOptionsCheck.java').write_text(HARNESS, encoding='utf-8')
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                        '-source', '8', '-target', '8', '-Xlint:-options', '-d', str(classes),
                        *map(str, sorted(sources.rglob('*.java'))), str(production)], check=True)
        subprocess.run([args.java, '-cp', str(classes), 'TsoaSpatialOptionsCheck'], check=True)
    run_lifecycle(args.java, root)


if __name__ == '__main__':
    main()
