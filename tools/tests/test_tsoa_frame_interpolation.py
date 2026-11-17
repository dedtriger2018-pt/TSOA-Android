#!/usr/bin/env python3
"""Check the production frame interpolator's GL resource lifetime and state safety.

Uses Python 3 and JDK 17+. Android and GLES calls are traced by small Java doubles;
production FrameInterpolation, ScreenMaterial and ShaderMaterial are compiled.
GPU motion pixels are validated separately by the EGL/GLES3 shader harness.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    'android/opengl/EGL14.java': r'''
package android.opengl;
public final class EGL14 {
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext();
    public static EGLContext current = new EGLContext();
    public static EGLContext eglGetCurrentContext() { return current; }
}
''',
    'android/opengl/EGLContext.java': r'''
package android.opengl;
public final class EGLContext {}
''',
    'android/opengl/GLES20.java': r'''
package android.opengl;
import java.nio.Buffer;
import java.util.*;
public class GLES20 {
    public static final int GL_FRAMEBUFFER=36160, GL_FRAMEBUFFER_COMPLETE=36053, GL_COLOR_ATTACHMENT0=36064;
    public static final int GL_TEXTURE_2D=3553, GL_RGBA=6408, GL_UNSIGNED_BYTE=5121;
    public static final int GL_TEXTURE_MIN_FILTER=10241, GL_TEXTURE_MAG_FILTER=10240, GL_LINEAR=9729;
    public static final int GL_TEXTURE_WRAP_S=10242, GL_TEXTURE_WRAP_T=10243, GL_CLAMP_TO_EDGE=33071;
    public static final int GL_TEXTURE0=33984, GL_TRIANGLES=4, GL_TEXTURE_BINDING_2D=32873;
    public static final int GL_VIEWPORT=2978, GL_ACTIVE_TEXTURE=34016, GL_CURRENT_PROGRAM=35725;
    public static final int GL_COLOR_WRITEMASK=3107, GL_BLEND=3042, GL_DEPTH_TEST=2929;
    public static final int GL_STENCIL_TEST=2960, GL_SCISSOR_TEST=3089, GL_CULL_FACE=2884;
    public static final int GL_DITHER=3024, GL_SAMPLE_ALPHA_TO_COVERAGE=32926, GL_SAMPLE_COVERAGE=32928;
    public static final int GL_VERTEX_SHADER=35633, GL_FRAGMENT_SHADER=35632, GL_COMPILE_STATUS=35713, GL_LINK_STATUS=35714;
    public static int next=1, drawFbo=51, readFbo=52, active=GL_TEXTURE0+7, program=11;
    public static int draws=0, allocations=0, deletedTextures=0, deletedFbos=0, deletedPrograms=0;
    public static int[] viewport={3,4,513,257}, bound={101,102,103,104,105,106,107,108};
    public static boolean[] color={false,true,false,true};
    public static boolean fail=false;
    public static Set<Integer> enabled=new HashSet<>(Arrays.asList(GL_BLEND,GL_DEPTH_TEST,GL_DITHER,GL_SCISSOR_TEST));
    public static void glGetIntegerv(int pname,int[] out,int offset) {
        if(pname==GLES30.GL_DRAW_FRAMEBUFFER_BINDING)out[offset]=drawFbo;
        else if(pname==GLES30.GL_READ_FRAMEBUFFER_BINDING)out[offset]=readFbo;
        else if(pname==GL_ACTIVE_TEXTURE)out[offset]=active;
        else if(pname==GL_CURRENT_PROGRAM)out[offset]=program;
        else if(pname==GL_TEXTURE_BINDING_2D)out[offset]=bound[active-GL_TEXTURE0];
        else if(pname==GL_VIEWPORT)System.arraycopy(viewport,0,out,offset,4);
        else throw new AssertionError("unknown state "+pname);
    }
    public static void glGetBooleanv(int pname,boolean[] out,int offset){System.arraycopy(color,0,out,offset,4);}
    public static void glActiveTexture(int x){active=x;}
    public static void glBindTexture(int target,int x){bound[active-GL_TEXTURE0]=x;}
    public static void glBindFramebuffer(int target,int x){
        if(target==GL_FRAMEBUFFER){drawFbo=x;readFbo=x;}
        else if(target==GLES30.GL_DRAW_FRAMEBUFFER)drawFbo=x;
        else if(target==GLES30.GL_READ_FRAMEBUFFER)readFbo=x;
        else throw new AssertionError("target");
    }
    public static void glViewport(int x,int y,int w,int h){viewport=new int[]{x,y,w,h};}
    public static void glUseProgram(int x){program=x;}
    public static void glColorMask(boolean a,boolean b,boolean c,boolean d){color=new boolean[]{a,b,c,d};}
    public static boolean glIsEnabled(int x){return enabled.contains(x);}
    public static void glEnable(int x){enabled.add(x);}
    public static void glDisable(int x){enabled.remove(x);}
    public static void glGenTextures(int n,int[] out,int offset){for(int i=0;i<n;i++)out[offset+i]=next++;}
    public static void glGenFramebuffers(int n,int[] out,int offset){for(int i=0;i<n;i++)out[offset+i]=next++;}
    public static void glDeleteTextures(int n,int[] x,int o){for(int i=0;i<n;i++)if(x[o+i]!=0)deletedTextures++;}
    public static void glDeleteFramebuffers(int n,int[] x,int o){for(int i=0;i<n;i++)if(x[o+i]!=0)deletedFbos++;}
    public static void glTexImage2D(int target,int level,int internal,int w,int h,int border,int format,int type,Buffer data){allocations++;}
    public static void glTexParameteri(int a,int b,int c){}
    public static void glFramebufferTexture2D(int a,int b,int c,int d,int e){}
    public static int glCheckFramebufferStatus(int x){return fail?0:GL_FRAMEBUFFER_COMPLETE;}
    public static void glDrawArrays(int mode,int start,int count){
        if(mode!=GL_TRIANGLES||count!=3||enabled.contains(GLES30.GL_RASTERIZER_DISCARD))throw new AssertionError("bad draw");
        draws++;
    }
    public static int glCreateProgram(){return next++;}
    public static int glCreateShader(int type){return next++;}
    public static void glShaderSource(int id,String s){}
    public static void glCompileShader(int id){}
    public static void glGetShaderiv(int id,int name,int[] out,int offset){out[offset]=1;}
    public static String glGetShaderInfoLog(int id){return "";}
    public static void glAttachShader(int a,int b){}
    public static void glLinkProgram(int a){}
    public static void glDeleteShader(int a){}
    public static void glGetProgramiv(int id,int name,int[] out,int offset){out[offset]=1;}
    public static String glGetProgramInfoLog(int id){return "";}
    public static int glGetUniformLocation(int id,String name){return 1;}
    public static void glDeleteProgram(int id){if(id!=0)deletedPrograms++;}
    public static void glUniform1f(int a,float b){}
    public static void glUniform1i(int a,int b){}
    public static void glUniform2f(int a,float b,float c){}
    public static void glUniform3f(int a,float b,float c,float d){}
    public static void glUniform1fv(int a,int b,float[] c,int d){}
}
''',
    'android/opengl/GLES30.java': r'''
package android.opengl;
public final class GLES30 extends GLES20 {
    public static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006, GL_READ_FRAMEBUFFER_BINDING = 36010;
    public static final int GL_DRAW_FRAMEBUFFER = 36009, GL_READ_FRAMEBUFFER = 36008;
    public static final int GL_RGBA8 = 32856, GL_RASTERIZER_DISCARD = 35977;
}
''',
    'android/graphics/Color.java': r'''
package android.graphics;
public final class Color {
    public static int red(int color) { return (color >>> 16) & 255; }
    public static int green(int color) { return (color >>> 8) & 255; }
    public static int blue(int color) { return color & 255; }
}
''',
}


HARNESS = r'''
import android.opengl.*;
import com.winlator.renderer.FrameInterpolation;
import java.util.*;
public final class LifecycleTest {
    static int assertions;
    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    static String state(){return GLES20.drawFbo+":"+GLES20.readFbo+":"+GLES20.active+":"+GLES20.program+":"+Arrays.toString(GLES20.viewport)+":"+Arrays.toString(GLES20.bound)+":"+Arrays.toString(GLES20.color)+":"+new TreeSet<>(GLES20.enabled);}
    public static void main(String[] args){
        FrameInterpolation helper=new FrameInterpolation();
        check(helper.getStorageBytes()==0 && GLES20.allocations==0 && GLES20.draws==0,"construction must allocate no GPU resources");
        String original=state();
        check(!helper.draw(801,802,1280,720,.5f),"unprepared midpoint rejected");
        check(helper.draw(801,802,1280,720,0f) && helper.draw(801,802,1280,720,1f),"unprepared endpoints copy");
        check(GLES20.allocations==0,"endpoint copies must allocate no targets");
        check(original.equals(state()),"endpoint state restored");
        int passes=GLES20.draws;
        check(helper.prepare(801,802,1280,720),"prepare succeeds");
        check(GLES20.draws-passes==9,"prepare draws exactly nine small passes");
        check(helper.getStorageBytes()==1152004L,"flow memory bound");
        check(helper.getWidth()==320 && helper.getHeight()==180,"flow dimensions");
        check(original.equals(state()),"prepare restores framebuffer/readbuffer/viewport/program/textures/enables/mask");
        int allocations=GLES20.allocations;
        passes=GLES20.draws;
        check(helper.draw(801,802,1280,720,.5f),"cached midpoint draws");
        check(GLES20.draws-passes==1 && allocations==GLES20.allocations,"cached flow costs one draw and no allocation");
        check(original.equals(state()),"midpoint state restored");
        passes=GLES20.draws;
        check(helper.prepare(801,802,1280,720),"same IDs may hold new contents");
        check(GLES20.draws-passes==9 && allocations==GLES20.allocations,"same-ID prepare recomputes without allocation");
        helper.invalidate();
        check(!helper.draw(801,802,1280,720,.5f),"invalid history rejected");
        check(!helper.draw(801,802,1280,720,Float.NaN),"NaN fraction rejected");
        check(helper.prepare(801,802,721,1281),"portrait resize");
        check(helper.getWidth()<=320&&helper.getHeight()<=180,"portrait stays bounded");
        check(GLES20.deletedTextures==9 && GLES20.deletedFbos==9,"resize releases all targets");
        EGL14.current=new EGLContext();
        int deletes=GLES20.deletedTextures;
        check(helper.prepare(801,802,1280,720),"context replacement recreates resources");
        check(deletes==GLES20.deletedTextures,"context replacement never deletes names from old context");
        check(helper.draw(801,802,1280,720,.5f),"context replacement also recreates presentation material");
        helper.release();
        check(GLES20.deletedTextures==deletes+9 && helper.getStorageBytes()==0,"current-context release frees targets");
        check(GLES20.deletedPrograms==4,"release frees all material programs");
        FrameInterpolation broken=new FrameInterpolation();
        GLES20.fail=true;
        check(!broken.prepare(901,902,640,360),"allocation failure fails closed");
        allocations=GLES20.allocations;
        check(!broken.prepare(901,902,640,360)&&allocations==GLES20.allocations,"failed allocation is not retried every frame");
        check(broken.getStorageBytes()==0,"failed targets freed");
        check(broken.draw(901,902,640,360,1f),"allocation failure still permits real-frame endpoint");
        GLES20.fail=false;
        check(broken.prepare(901,902,641,360),"resize permits allocation recovery");
        broken.release();
        check(FrameInterpolation.flowWidth(0,0)==1 && FrameInterpolation.flowHeight(0,0)==1,"minimum dimension helpers");
        System.out.println("PASS "+assertions+" helper lifecycle assertions; 9 preparation passes, 1 cached presentation pass, <=1152004 flow bytes");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    renderer = root / 'app/src/main/java/com/winlator/renderer'
    production = [renderer / 'FrameInterpolation.java',
                  renderer / 'material/ScreenMaterial.java',
                  renderer / 'material/ShaderMaterial.java']
    with tempfile.TemporaryDirectory(prefix='tsoa-frame-interpolation-') as directory:
        temporary = Path(directory)
        sources = []
        for relative, text in STUBS.items():
            source = temporary / relative
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(text, encoding='utf-8')
            sources.append(source)
        harness = temporary / 'LifecycleTest.java'
        harness.write_text(HARNESS, encoding='utf-8')
        classes = temporary / 'classes'
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                        '-source', '8', '-target', '8', '-Xlint:-options',
                        '-d', str(classes), *map(str, production + sources),
                        str(harness)], check=True)
        subprocess.run([args.java, '-cp', str(classes), 'LifecycleTest'], check=True)


if __name__ == '__main__':
    main()
