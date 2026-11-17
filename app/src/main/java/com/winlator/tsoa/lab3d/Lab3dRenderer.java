package com.winlator.tsoa.lab3d;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import java.util.HashMap;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Disposable GLES 3 courtyard experiment. Call lifecycle/resource methods on the GL thread. */
public final class Lab3dRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "TsoaLab3D";
    public static final float NEAR_PLANE = .18f, FAR_PLANE = 130f;
    private volatile boolean pbr = true, lights = true, dof = true, bloom = true, rain = true;
    private volatile boolean paused;
    private volatile float rainIntensity = .8f, lightIntensity = 1f, renderScale = 1f;
    private volatile float focusDistance = 18, exposure = 1, backgroundBrightness = 1;
    private volatile int quality = 1;
    private volatile float touchX = .5f, touchY = .5f;
    private volatile boolean touchDown;
    private volatile boolean pendingTap;
    private volatile float tapX = .5f, tapY = .5f;
    private volatile int touchSequence;
    private volatile String lastError = "", diagnostics = "Waiting for GLES 3 context";
    private volatile boolean ready;
    private volatile long frameCount;
    private volatile int renderWidth, renderHeight;
    private int surfaceWidth = 1, surfaceHeight = 1, handledTouch;
    private boolean previousDown;
    private float previousX, previousY, yaw, pitch, time;
    private long lastNanos;
    private final float[] projection = new float[16], view = new float[16], viewProjection = new float[16];
    private final float[] inverseViewProjection = new float[16], camera = new float[3];
    private final float[] nearPoint = new float[4], farPoint = new float[4], clip = new float[4];
    private final float[] ripple = {0, -8, -100, 0};
    private Program sceneProgram, skyProgram, rainProgram, blurProgram, compositeProgram;
    private Lab3dMesh architecture, floor;
    private final Target scene = new Target(), blurA = new Target(), blurB = new Target();
    private boolean hdr, offscreen;
    private int failedWidth = -1, failedHeight = -1;

    public Lab3dRenderer(Context context) { }
    public Lab3dRenderer() { }
    public void setPbrEnabled(boolean enabled) { pbr = enabled; }
    public void setDynamicLightsEnabled(boolean enabled) { lights = enabled; }
    public void setDofEnabled(boolean enabled) { dof = enabled; }
    public void setBloomEnabled(boolean enabled) { bloom = enabled; }
    public void setRainEnabled(boolean enabled) { rain = enabled; }
    public void setRainIntensity(float value) { rainIntensity = clamp(value, 0, 1.5f); }
    public void setLightIntensity(float value) { lightIntensity = clamp(value, 0, 2); }
    public void setFocusDistance(float metres) { focusDistance = clamp(metres, 3, 30); }
    public void setExposure(float value) { exposure = clamp(value, .5f, 1.6f); }
    public void setBackgroundBrightness(float value) { backgroundBrightness = clamp(value, .35f, 1); }
    public void setRenderScale(float value) { renderScale = clamp(value, .4f, 1.5f); }
    /** 0 = 640px, 1 = 960px default, 2 = 1280px maximum internal longest edge. */
    public void setQuality(int value) { quality = Math.max(0, Math.min(2, value)); }
    public void setPaused(boolean value) { paused = value; }
    /** Coordinates use the Android convention: left/top=0, right/bottom=1. */
    public synchronized void setTouch(float normalizedX, float normalizedY, boolean down) {
        float x=clamp(normalizedX, 0, 1), y=clamp(normalizedY, 0, 1);
        if(down&&!touchDown){tapX=x;tapY=y;pendingTap=true;}
        touchX=x;touchY=y;
        touchDown = down; touchSequence++;
    }
    public void onTouch(float normalizedX, float normalizedY, boolean down) { setTouch(normalizedX, normalizedY, down); }
    public String getLastError() { return lastError; }
    public String getDiagnostics() { return diagnostics; }
    public boolean isReady() { return ready; }
    public long getFrameCount() { return frameCount; }
    public int getRenderWidth() { return renderWidth; }
    public int getRenderHeight() { return renderHeight; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A recreated EGL context already owns fresh names; never delete stale names here.
        ready = false; lastError = ""; lastNanos = 0; frameCount = 0;
        scene.forget(); blurA.forget(); blurB.forget();
        sceneProgram = skyProgram = rainProgram = blurProgram = compositeProgram = null;
        architecture = floor = null; offscreen = false; failedWidth = failedHeight = -1;
        try {
            sceneProgram = new Program(Lab3dShaders.sceneVertex(), Lab3dShaders.sceneFragment(),
                "uViewProjection", "uCamera", "uTime", "uFlash", "uPbr", "uLights", "uRipple",
                "uLightPos[0]", "uLightPos[1]", "uLightPos[2]", "uLightColor[0]", "uLightColor[1]", "uLightColor[2]");
            skyProgram = new Program(Lab3dShaders.fullscreenVertex(), Lab3dShaders.skyFragment(),
                "uInverseViewProjection", "uCamera", "uTime", "uFlash");
            rainProgram = new Program(Lab3dShaders.rainVertex(), Lab3dShaders.rainFragment(),
                "uViewProjection", "uCamera", "uTime", "uRainIntensity", "uFlash");
            blurProgram = new Program(Lab3dShaders.fullscreenVertex(), Lab3dShaders.blurFragment(), "uColor", "uDirection");
            compositeProgram = new Program(Lab3dShaders.fullscreenVertex(), Lab3dShaders.compositeFragment(),
                "uColor", "uBlur", "uDepth", "uDof", "uBloom", "uFocus", "uTime", "uExposure", "uBrightness");
            architecture = new Lab3dMesh(Lab3dMesh.cube(), Lab3dMesh.architecture());
            floor = new Lab3dMesh(Lab3dMesh.floor(), Lab3dMesh.floorInstance());
            GLES30.glDisable(GLES30.GL_DITHER);
            GLES30.glClearColor(.035f, .065f, .085f, 1);
            ready = true;
            diagnostics = "GLES 3 ready · procedural courtyard";
        } catch (RuntimeException error) {
            release();
            lastError = error.getMessage() == null ? error.toString() : error.getMessage();
            diagnostics = "GLES initialization failed";
            Log.e(TAG, "GLES courtyard initialization failed", error);
        }
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfaceWidth = Math.max(1, width); surfaceHeight = Math.max(1, height);
        failedWidth = failedHeight = -1;
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
    }

    @Override public void onDrawFrame(GL10 unused) {
        if (!ready) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            return;
        }
        long now = System.nanoTime();
        if (lastNanos != 0 && !paused) time += Math.min(.1f, (now - lastNanos) * 1e-9f);
        lastNanos = now;
        try {
            updateCamera();
            processTouch();
            ensureTargets();
            float flash = lights ? lightning(time) : 0;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, offscreen ? scene.fbo : 0);
            GLES30.glViewport(0, 0, offscreen ? renderWidth : surfaceWidth, offscreen ? renderHeight : surfaceHeight);
            GLES30.glDepthMask(true);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            drawSky(flash);
            drawArchitecture(flash);
            if (rain && rainIntensity > .001f) drawRain(flash);
            if (offscreen) postProcess();
            frameCount++;
        } catch (RuntimeException error) {
            ready = false;
            lastError = error.getMessage() == null ? error.toString() : error.getMessage();
            Log.e(TAG, "Courtyard frame failed", error);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        }
    }

    private void updateCamera() {
        camera[0] = (float)Math.sin(yaw) * 6.5f + (float)Math.sin(time * .105f) * .13f;
        camera[1] = 3.25f + pitch * 2.3f + (float)Math.sin(time * .23f) * .035f;
        camera[2] = 12.8f + (float)Math.cos(time * .12f) * .08f;
        Matrix.perspectiveM(projection, 0, 52, (float)surfaceWidth / surfaceHeight, NEAR_PLANE, FAR_PLANE);
        Matrix.setLookAtM(view, 0, camera[0], camera[1], camera[2], yaw * 3, 2.65f + pitch * 2, -10, 0, 1, 0);
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0);
        Matrix.invertM(inverseViewProjection, 0, viewProjection, 0);
    }

    private synchronized void processTouch() {
        int seq = touchSequence;
        if (seq == handledTouch) return;
        float x = touchX, y = touchY;
        boolean down = touchDown;
        boolean tap=pendingTap;
        if (down || tap) {
            if (!down && tap) { x=tapX; y=tapY; }
            if (previousDown && down) {
                yaw = clamp(yaw + (x - previousX) * .8f, -.48f, .48f);
                pitch = clamp(pitch + (y - previousY) * .35f, -.2f, .2f);
            }
            // Unproject the actual camera ray and intersect the wet roof plane.
            clip[0] = x * 2 - 1; clip[1] = 1 - y * 2; clip[2] = -1; clip[3] = 1;
            Matrix.multiplyMV(nearPoint, 0, inverseViewProjection, 0, clip, 0);
            clip[2] = 1;
            Matrix.multiplyMV(farPoint, 0, inverseViewProjection, 0, clip, 0);
            float nw = nearPoint[3], fw = farPoint[3];
            float nx = nearPoint[0]/nw, ny = nearPoint[1]/nw, nz = nearPoint[2]/nw;
            float dx = farPoint[0]/fw-nx, dy = farPoint[1]/fw-ny, dz = farPoint[2]/fw-nz;
            if (dy < -.001f) {
                float t = (.012f-ny)/dy, wx=nx+dx*t, wz=nz+dz*t;
                if (t > 0 && Math.abs(wx)<22 && wz>-42 && wz<22 &&
                    (tap || !previousDown || time-ripple[2]>.25f)) {
                    ripple[0]=wx; ripple[1]=wz; ripple[2]=time; ripple[3]=1;
                }
            }
        }
        pendingTap=false;
        previousDown = down; previousX = x; previousY = y; handledTouch = seq;
    }

    private void drawSky(float flash) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glDisable(GLES30.GL_BLEND);
        skyProgram.use(); skyProgram.matrix("uInverseViewProjection", inverseViewProjection);
        skyProgram.vector3("uCamera", camera[0], camera[1], camera[2]);
        skyProgram.scalar("uTime", time); skyProgram.scalar("uFlash", flash);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private void drawArchitecture(float flash) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthFunc(GLES30.GL_LEQUAL); GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK);
        sceneProgram.use(); sceneProgram.matrix("uViewProjection", viewProjection);
        sceneProgram.vector3("uCamera", camera[0], camera[1], camera[2]);
        sceneProgram.scalar("uTime", time); sceneProgram.scalar("uFlash", flash);
        sceneProgram.integer("uPbr", pbr ? 1 : 0); sceneProgram.integer("uLights", lights ? 1 : 0);
        sceneProgram.vector4("uRipple", ripple);
        float drift = (float)Math.sin(time * .38f);
        sceneProgram.vector3("uLightPos[0]", -3.9f + drift*.30f, 1.85f, -3.2f);
        sceneProgram.vector3("uLightPos[1]", 5.2f, 2.2f+(float)Math.sin(time*.48f)*.2f, -7.4f);
        sceneProgram.vector3("uLightPos[2]", -.9f+(float)Math.sin(time*.21f)*1.8f, 5.8f, -24.5f);
        float k = lightIntensity;
        sceneProgram.vector3("uLightColor[0]", 1.8f*k, 15.5f*k, 24f*k);
        sceneProgram.vector3("uLightColor[1]", 30f*k, 10.5f*k, 2.1f*k);
        sceneProgram.vector3("uLightColor[2]", (1.8f+flash*44)*k, (14+flash*48)*k, (22+flash*60)*k);
        floor.draw(); architecture.draw();
    }

    private void drawRain(float flash) {
        GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA); GLES30.glDepthMask(false);
        rainProgram.use(); rainProgram.matrix("uViewProjection", viewProjection);
        rainProgram.vector3("uCamera", camera[0], camera[1], camera[2]);
        rainProgram.scalar("uTime", time); rainProgram.scalar("uRainIntensity", rainIntensity);
        rainProgram.scalar("uFlash", flash);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, quality == 0 ? 700 : quality == 1 ? 1100 : 1600);
        GLES30.glDepthMask(true); GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void ensureTargets() {
        int edge = quality == 0 ? 640 : quality == 1 ? 960 : 1280;
        float scale = Math.min(renderScale, (float)edge / Math.max(surfaceWidth, surfaceHeight));
        int w = Math.max(2, ((int)(surfaceWidth * scale)) & ~1);
        int h = Math.max(2, ((int)(surfaceHeight * scale)) & ~1);
        if (scene.width == w && scene.height == h && scene.fbo != 0) return;
        if (failedWidth == w && failedHeight == h) return;
        scene.release(); blurA.release(); blurB.release();
        offscreen = false;
        int bw = Math.max(2, w/4), bh = Math.max(2, h/4);
        hdr = scene.create(w, h, true, true);
        if (!hdr) { scene.release(); if (!scene.create(w, h, false, true)) { allocationFallback(w,h); return; } }
        if (!blurA.create(bw,bh,hdr,false) || !blurB.create(bw,bh,hdr,false)) {
            allocationFallback(w,h); return;
        }
        renderWidth = w; renderHeight = h; offscreen = true;
        diagnostics = "GLES 3 · " + w + "×" + h + (hdr ? " HDR16F" : " RGBA8") + " · 3 point lights · depth DOF";
        Log.i(TAG, diagnostics);
    }

    private void allocationFallback(int width, int height) {
        scene.release(); blurA.release(); blurB.release(); offscreen = false;
        failedWidth = width; failedHeight = height;
        renderWidth = surfaceWidth; renderHeight = surfaceHeight;
        diagnostics = "GLES 3 direct scene fallback; framebuffer allocation unavailable";
        Log.w(TAG, diagnostics);
    }

    private void postProcess() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glDisable(GLES30.GL_BLEND);
        // Quarter-resolution separable Gaussian is shared by DOF and bloom.
        if (dof || bloom) {
            blurProgram.use(); blurProgram.integer("uColor", 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurA.fbo);
            GLES30.glViewport(0,0,blurA.width,blurA.height);
            bindTexture(0, scene.color); blurProgram.vector2("uDirection", 1.4f/blurA.width, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurB.fbo);
            bindTexture(0,blurA.color); blurProgram.vector2("uDirection",0,1.4f/blurA.height);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,surfaceWidth,surfaceHeight);
        compositeProgram.use(); compositeProgram.integer("uColor",0); compositeProgram.integer("uBlur",1); compositeProgram.integer("uDepth",2);
        compositeProgram.integer("uDof",dof?1:0); compositeProgram.integer("uBloom",bloom?1:0);
        compositeProgram.scalar("uFocus",focusDistance); compositeProgram.scalar("uTime",time);
        compositeProgram.scalar("uExposure",exposure); compositeProgram.scalar("uBrightness",backgroundBrightness);
        bindTexture(0,scene.color); bindTexture(1,(dof||bloom)?blurB.color:scene.color); bindTexture(2,scene.depth);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    private static void bindTexture(int unit, int id) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id);
    }
    private static float lightning(float t) {
        float p=t%19.0f;
        if(p<12.2f || p>12.95f) return 0;
        return (float)(Math.exp(-Math.pow((p-12.36f)*30,2))*.85+Math.exp(-Math.pow((p-12.64f)*42,2))*.5);
    }
    private static float clamp(float value,float low,float high) { return Math.max(low,Math.min(high,value)); }

    /** Must run through GLSurfaceView.queueEvent while its EGL context is current. */
    public void release() {
        ready=false;
        if(architecture!=null) { architecture.release(); architecture=null; }
        if(floor!=null) { floor.release(); floor=null; }
        scene.release(); blurA.release(); blurB.release();
        if(sceneProgram!=null)sceneProgram.release(); if(skyProgram!=null)skyProgram.release();
        if(rainProgram!=null)rainProgram.release(); if(blurProgram!=null)blurProgram.release();
        if(compositeProgram!=null)compositeProgram.release();
    }

    private static final class Program {
        int id;
        final HashMap<String,Integer> uniforms = new HashMap<>();
        Program(String vertex,String fragment,String... names) {
            int vs=compile(GLES30.GL_VERTEX_SHADER,vertex), fs=0;
            try {
                fs=compile(GLES30.GL_FRAGMENT_SHADER,fragment); id=GLES30.glCreateProgram();
                GLES30.glAttachShader(id,vs); GLES30.glAttachShader(id,fs); GLES30.glLinkProgram(id);
                int[] status=new int[1]; GLES30.glGetProgramiv(id,GLES30.GL_LINK_STATUS,status,0);
                if(status[0]==0) { String log=GLES30.glGetProgramInfoLog(id); GLES30.glDeleteProgram(id);id=0;throw new IllegalStateException("GLES program: "+log); }
                for(String name:names)uniforms.put(name,GLES30.glGetUniformLocation(id,name));
            } finally { GLES30.glDeleteShader(vs);if(fs!=0)GLES30.glDeleteShader(fs); }
        }
        static int compile(int type,String source) {
            int shader=GLES30.glCreateShader(type); GLES30.glShaderSource(shader,source); GLES30.glCompileShader(shader);
            int[] status=new int[1]; GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,status,0);
            if(status[0]==0){String log=GLES30.glGetShaderInfoLog(shader);GLES30.glDeleteShader(shader);throw new IllegalStateException("GLES shader: "+log);}
            return shader;
        }
        int loc(String name){Integer value=uniforms.get(name);return value==null?-1:value;}
        void use(){GLES30.glUseProgram(id);}
        void scalar(String name,float x){GLES30.glUniform1f(loc(name),x);}
        void integer(String name,int x){GLES30.glUniform1i(loc(name),x);}
        void vector2(String name,float x,float y){GLES30.glUniform2f(loc(name),x,y);}
        void vector3(String name,float x,float y,float z){GLES30.glUniform3f(loc(name),x,y,z);}
        void vector4(String name,float[] v){GLES30.glUniform4fv(loc(name),1,v,0);}
        void matrix(String name,float[] m){GLES30.glUniformMatrix4fv(loc(name),1,false,m,0);}
        void release(){if(id!=0)GLES30.glDeleteProgram(id);id=0;}
    }

    private static final class Target {
        int fbo,color,depth,width,height;
        final int[] one=new int[1];
        boolean create(int w,int h,boolean floating,boolean withDepth) {
            width=w;height=h;
            for(int i=0;i<8&&GLES30.glGetError()!=GLES30.GL_NO_ERROR;i++) { }
            GLES30.glGenTextures(1,one,0);color=one[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,color); textureParameters(GLES30.GL_LINEAR);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,floating?GLES30.GL_RGBA16F:GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,floating?GLES30.GL_HALF_FLOAT:GLES30.GL_UNSIGNED_BYTE,null);
            GLES30.glGenFramebuffers(1,one,0);fbo=one[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,color,0);
            if(withDepth){
                GLES30.glGenTextures(1,one,0);depth=one[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,depth);textureParameters(GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_COMPARE_MODE,GLES30.GL_NONE);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_DEPTH_COMPONENT24,w,h,0,GLES30.GL_DEPTH_COMPONENT,GLES30.GL_UNSIGNED_INT,null);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_TEXTURE_2D,depth,0);
            }
            return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE && GLES30.glGetError()==GLES30.GL_NO_ERROR;
        }
        static void textureParameters(int filter){
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,filter);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        }
        void release(){
            if(fbo!=0){one[0]=fbo;GLES30.glDeleteFramebuffers(1,one,0);}if(color!=0){one[0]=color;GLES30.glDeleteTextures(1,one,0);}if(depth!=0){one[0]=depth;GLES30.glDeleteTextures(1,one,0);}forget();
        }
        void forget(){fbo=color=depth=width=height=0;}
    }
}
