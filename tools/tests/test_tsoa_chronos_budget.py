#!/usr/bin/env python3
"""Exercise branch generations, restores, exclusive game access and adaptive quality."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

ROOT = Path(__file__).resolve().parents[2] / "app/src/main/java/com/winlator"
CHRONOS = """package com.winlator.tsoa;
import java.io.*;
import java.nio.file.*;
public final class ChronosCheck {
    static void ok(boolean pass) { if (!pass) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]), saves=Files.createDirectory(dir.resolve("saves"));
        ChronosStore store=new ChronosStore(dir.resolve("chronos").toFile());
        ok(store.list().isEmpty());
        ok(TsoaSaveAccess.startGame());
        try { TsoaSaveAccess.beginMaintenance(); throw new AssertionError(); }
        catch (IOException expected) {}
        TsoaSaveAccess.endGame();
        TsoaSaveAccess.beginMaintenance();
        ok(!TsoaSaveAccess.startGame());
        TsoaSaveAccess.endMaintenance();
        Files.writeString(saves.resolve("global"), "shared");
        Files.writeString(saves.resolve("slot"), "start");
        ChronosStore.Point root=store.create(saves.toFile(),"root");
        Files.writeString(saves.resolve("slot"),"branch-A");
        ChronosStore.Point a=store.create(saves.toFile(),root.id);
        File rollback=store.restore(saves.toFile(),root);
        ok(Files.readString(rollback.toPath().resolve("slot")).equals("branch-A"));
        ok(Files.readString(saves.resolve("global")).equals("shared"));
        ok(Files.readString(saves.resolve("slot")).equals("start"));
        Files.writeString(saves.resolve("slot"),"branch-B");
        ChronosStore.Point b=store.create(saves.toFile(),root.id);
        ok(store.list().size()==3 && !a.id.equals(b.id) && a.parent.equals(root.id) && b.parent.equals(root.id));
        try { store.delete(root); throw new AssertionError(); }
        catch (IOException expected) {}
        Files.write(a.file.toPath(),new byte[]{1,2,3});
        try { store.restore(saves.toFile(),a); throw new AssertionError(); }
        catch (IOException expected) {}
        ok(Files.readString(saves.resolve("slot")).equals("branch-B"));
        store.delete(a);
        ok(store.list().size()==2);
        System.out.println("PASS: Chronos branching, safe rollback, invalid archive, leaf removal and live-session lock");
    }
}
"""
BUDGET = """package com.winlator.renderer;
public final class BudgetCheck {
    static void ok(boolean pass) { if (!pass) throw new AssertionError(); }
    static int simulate(GraphicsBudget b, long from, int count, long work, int thermal) {
        b.setThermal(thermal);
        int result=3;
        for (int i=0;i<count;i++) {
            long now=1+from+i*20_000_000L;
            if ((i % 2)==0) b.noteGenerated();
            result=b.frame(now,now,work,16_666_667L);
        }
        return result;
    }
    public static void main(String[] args) {
        GraphicsBudget adaptive=new GraphicsBudget(true);
        ok(simulate(adaptive,0,205,15_000_000,0)==2);
        ok(simulate(adaptive,4_100_000_000L,110,2_000_000,4)==1);
        ok(simulate(adaptive,6_300_000_000L,410,2_000_000,0)==2);
        ok(adaptive.sample().sourceRate>=49 && adaptive.sample().generatedRate>=24);
        GraphicsBudget manual=new GraphicsBudget(false);
        ok(simulate(manual,0,205,15_000_000,4)==3);
        System.out.println("PASS: source and synthetic counters, sustained load, thermal cap, recovery hysteresis, manual quality");
    }
}
"""


def main():
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        (folder / "ChronosCheck.java").write_text(CHRONOS)
        (folder / "BudgetCheck.java").write_text(BUDGET)
        names = ["tsoa/ChronosStore.java", "tsoa/TsoaSaveArchive.java",
                 "tsoa/TsoaSaveAccess.java", "renderer/GraphicsBudget.java"]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp,
                        *[str(ROOT / name) for name in names],
                        str(folder / "ChronosCheck.java"), str(folder / "BudgetCheck.java")], check=True)
        subprocess.run(["java", "-cp", tmp, "com.winlator.tsoa.ChronosCheck", tmp], check=True)
        subprocess.run(["java", "-cp", tmp, "com.winlator.renderer.BudgetCheck"], check=True)


if __name__ == "__main__":
    main()
