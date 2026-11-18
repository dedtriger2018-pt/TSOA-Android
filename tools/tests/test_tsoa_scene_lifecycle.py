#!/usr/bin/env python3
"""Trace the production scene compositor's cache, ownership and failure paths on the JVM."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

from test_tsoa_game_color import LIFECYCLE_STUBS

HARNESS = """
package com.winlator.renderer;
import android.opengl.*;
public class SceneLifecycle {
    static void check(boolean b,String label) { if(!b) throw new AssertionError(label); }
    static int draws() { return GLES20.draws.size(); }
    public static void main(String[] args) {
        SceneProfile p=new SceneProfile();
        GLRenderer r=new GLRenderer();
        EffectComposer c=r.effectComposer;
        check(!c.hasEffects(),"default");
        c.configureReconstruction(p,2,3);
        check(c.hasEffects(),"enabled");
        c.renderToFramebuffer(718);
        check(draws()==2 && GLES20.draws.get(0)[0]==1 && GLES20.draws.get(1)[2]==718,"recognition and output");
        int allocated=GLES20.next;
        r.frame();
        check(draws()==3 && GLES20.next==allocated,"static signature reused");
        r.timestamp++;
        r.frame();
        check(draws()==5 && GLES20.next==allocated,"new guest signature");
        long before=c.getVisualRevision();
        c.configureReconstruction(p,0,1);
        check(c.getVisualRevision()>before,"FG invalidation");
        r.frame();
        check(draws()==6 && GLES20.next==allocated,"independent Lumen");
        r.gameWidth=700;
        check(!c.hasEffects(),"wrong guest aspect");
        r.gameWidth=960;
        int deletes=GLES20.deletes;
        EGL14.current=new EGLContext(2);
        r.frame();
        check(draws()==8 && GLES20.deletes==deletes,"context replacement");
        c.configureReconstruction(null,0,0);
        r.xServerView.drain();
        check(!c.hasEffects() && GLES20.sizes.isEmpty()==false,"off with simulated old context objects");
        check(GLES20.deletes==deletes+8,"release three profile textures, score target and compositor targets");
        GLRenderer bad=new GLRenderer();
        bad.effectComposer.configureReconstruction(p,1,0);
        GLES20.linkOK=false;
        bad.frame();
        int after=draws(), names=GLES20.next;
        bad.frame();
        check(draws()==after && GLES20.next==names && !bad.effectComposer.hasEffects(),"sticky shader failure");
        GLES20.linkOK=true;
        bad.surfaceWidth--;
        bad.frame();
        check(draws()==after+2,"resize recovery");
        System.out.println("PASS scene compositor cache, FG revision, independent Lumen, aspect, EGL ownership, disable, shader fallback and retry");
    }
}
"""


def main():
    stubs = LIFECYCLE_STUBS.copy()
    base = 'com/winlator/renderer/'
    del stubs[base + 'SceneReconstruction.java']
    stubs[base + 'SceneProfile.java'] = """package com.winlator.renderer;
public class SceneProfile {
    public int sourceWidth=960, sourceHeight=544, width=30, height=17;
    public byte[] reference=new byte[width*height*4], structure=reference.clone();
}"""
    renderer = base + 'GLRenderer.java'
    stubs[renderer] = stubs[renderer].replace('public int getGameWidth()', '''
    public long timestamp=100000000L;
    public long getSceneContentTime(){return timestamp;}
    public int getGameWidth()''')
    gl = 'android/opengl/GLES20.java'
    stubs[gl] = stubs[gl].replace('public static int next=', '''
    public static final int GL_NO_ERROR=0,GL_TRUE=1,GL_LINK_STATUS=701,GL_NEAREST=702,GL_TRIANGLES=703;
    public static boolean linkOK=true;
    public static void glGetProgramiv(int p,int prop,int[] data,int at){data[at]=linkOK?1:0;}
    public static String glGetProgramInfoLog(int p){return "test";}
    public static int next=''')
    shader = base + 'material/ShaderMaterial.java'
    stubs[shader] = stubs[shader].replace('    protected String getFragmentShader()', '''
    protected String getVertexShader(){return "";}
    protected String getFragmentShader()''')
    production = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/winlator/renderer'
    modules = ['EffectComposer','SoftBloom','SpatialUpscaler','SpatialShaders','SceneReconstruction','SceneShaders',
               'effects/Effect','effects/ColorEffect','effects/FXAAEffect','effects/SharpeningEffect']
    with TemporaryDirectory(prefix='tsoa-scene-life-') as directory:
        work = Path(directory)
        files = []
        for relative, source in {**stubs, 'SceneLifecycle.java': HARNESS}.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
            files.append(str(path))
        files.extend(str(production / (name + '.java')) for name in modules)
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '8', '-target', '8',
                        '-Xlint:-options', '-d', str(work), *files], check=True)
        subprocess.run(['java', '-cp', str(work), 'com.winlator.renderer.SceneLifecycle'], check=True)


if __name__ == '__main__':
    main()
