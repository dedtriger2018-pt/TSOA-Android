#!/usr/bin/env python3
"""Exercise actual Android-facing scene store/session with JVM doubles for notifications and rendering."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2] / "app/src/main/java/com/winlator"
STUBS = {
    "android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {
    interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String k); }
    interface Editor { Editor putLong(String key, long v); void apply(); }
    Editor edit(); long getLong(String k, long v); int getInt(String k, int v);
    float getFloat(String k,float v); boolean getBoolean(String k,boolean v); String getString(String k,String v);
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
}
""",
    "android/content/Context.java": """
package android.content;
import java.io.File;
import java.util.*;
public class Context implements SharedPreferences {
    public static final int MODE_PRIVATE = 0;
    public File dir;
    final Map<String,Long> numbers = new HashMap<>();
    final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    public SharedPreferences getSharedPreferences(String name, int mode) { return this; }
    public Context getApplicationContext() { return this; }
    public File getNoBackupFilesDir() { return dir; }
    public int getInt(String k,int v) { return (int)getLong(k,v); }
    public long getLong(String k,long v) { return numbers.getOrDefault(k,v); }
    public boolean getBoolean(String k,boolean v) { return v; }
    public String getString(String k,String v) { return v; }
    public float getFloat(String k,float v) { return v; }
    public Editor edit() { return new Editor() {
        String key; long value;
        public Editor putLong(String k,long v) { key=k; value=v; return this; }
        public void apply() { numbers.put(key,value); for (OnSharedPreferenceChangeListener l: listeners) l.onSharedPreferenceChanged(Context.this,key); }
    }; }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){ listeners.add(l); }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){ listeners.remove(l); }
}
""",
    "android/util/Log.java": """
package android.util; public class Log {
    public static int w(String t,String s,Throwable e){ return 0; }
    public static int w(String t,String s){ return 0; }
}""",
    "android/os/Looper.java": """
package android.os; public class Looper { public static Looper getMainLooper(){ return null; } }""",
    "android/os/Handler.java": """
package android.os; import java.util.concurrent.*;
public class Handler {
    public static final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
    public Handler(Looper l){}
    public boolean post(Runnable job){ return queue.add(job); }
    public static void deliver() throws Exception {
        Runnable job=queue.poll(5,TimeUnit.SECONDS);
        if (job==null) throw new AssertionError("missing callback"); job.run();
    }
}""",
    "com/winlator/renderer/EffectComposer.java": """
package com.winlator.renderer; public class EffectComposer {
    public SceneProfile active;
    public int g,l;
    public void configureReconstruction(SceneProfile profile,int genesis,int lumen){
        active=profile; g=genesis; l=lumen;
    }
}""",
}

HARNESS = """
package com.winlator.tsoa;
import android.content.Context;
import android.os.Handler;
import com.winlator.renderer.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
public class SceneSessionCheck {
    static void require(boolean value, String failure){ if (!value) throw new AssertionError(failure); }
    static byte[] pack(int color) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeLong(0x54534f5343454e45L); out.writeInt(1); out.writeInt(3); out.writeInt(2);
        out.writeShort(3); out.writeShort(2);
        for(int i=0;i<6;i++) { out.writeByte(color); out.writeByte(23); out.writeByte(45); out.writeByte(255); }
        for(int i=0;i<6;i++) { out.writeInt(0x40ffffff); }
        out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }
    static void check(String dir) throws Exception {
        Context app=new Context(); app.dir=new File(dir);
        Path active=app.dir.toPath().resolve("active.tscene");
        EffectComposer composer=new EffectComposer();
        TsoaSceneSession session=new TsoaSceneSession(app,composer);
        session.start();
        require(new TsoaOptions(app).getLumenLevel()==0 && composer.active==null,"fresh defaults");
        byte[] first=pack(1),second=pack(2);
        TsoaSceneStore.install(app,new ByteArrayInputStream(first));
        app.edit().putLong(TsoaOptions.LUMEN,3).apply();
        Handler.deliver();
        require(composer.active.reference[0]==1 && composer.g==0 && composer.l==3,"Lumen independent");
        SceneProfile before=composer.active;
        app.edit().putLong(TsoaOptions.GENESIS,2).apply();
        require(composer.active==before && composer.g==2,"level changes reuse profile");
        long revision=app.getLong(TsoaSceneStore.REVISION_KEY,-1);
        for(byte[] invalid:new byte[][]{new byte[3], new byte[SceneProfile.MAX_FILE_BYTES+1]}) {
            try {
                TsoaSceneStore.install(app,new ByteArrayInputStream(invalid));
                throw new AssertionError("bad import accepted");
            } catch(IOException expected){}
            require(Arrays.equals(first,Files.readAllBytes(active)),"bad import changed active file");
            require(!Files.exists(app.dir.toPath().resolve("active.tscene.tmp")),"staging leak");
            require(app.getLong(TsoaSceneStore.REVISION_KEY,-1)==revision && composer.active==before,"spurious revision");
        }
        TsoaSceneStore.install(app,new ByteArrayInputStream(second));
        require(composer.active==null,"previous scene still active during swap");
        // Stop before the replacement load returns. Its queued callback must not revive the stopped display.
        session.stop(); Handler.deliver();
        require(composer.active==null,"stale callback after stop");
        session.start(); Handler.deliver();
        require(composer.active.reference[0]==2 && composer.g==2 && composer.l==3,"resume updated scene");
        app.edit().putLong(TsoaOptions.LUMEN,99).apply();
        require(composer.g==2 && composer.l==0,"invalid Lumen level");
        app.edit().putLong(TsoaOptions.GENESIS,0).apply();
        require(composer.active==null, "disabled resource still active");
        app.edit().putLong(TsoaOptions.GENESIS,1).apply();
        TsoaSceneStore.remove(app);
        Handler.deliver(); Handler.deliver();
        require(composer.active==null && !Files.exists(active),"removed scene returned");
        session.stop();
    }
    public static void main(String[] args) {
        try { check(args[0]); System.out.println("PASS: scene replacement, bounded invalid imports, levels, resume, stale callbacks and deletion"); System.exit(0); }
        catch(Throwable e){ e.printStackTrace(); System.exit(1); }
    }
}"""

def main():
    with tempfile.TemporaryDirectory(prefix="tsoa-scene-session-") as tmp:
        root = Path(tmp)
        classes = root / "classes"
        classes.mkdir()
        sources = []
        for name, source in {**STUBS, "com/winlator/tsoa/SceneSessionCheck.java": HARNESS}.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(path)
        sources += [ROOT / path for path in ("renderer/SceneProfile.java", "tsoa/TsoaSceneStore.java",
                                            "tsoa/TsoaSceneSession.java", "tsoa/TsoaOptions.java")]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target",
                        "8", "-Xlint:-options", "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run(["java", "-cp", str(classes), "com.winlator.tsoa.SceneSessionCheck", str(root)],
                       check=True, timeout=18)

if __name__ == "__main__":
    main()
