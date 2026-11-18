#!/usr/bin/env python3
"""Run the production presentation shaders on surfaceless OpenGL ES."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

from test_tsoa_scene_gpu import draw, texture, make_context, np, GL, shaders, ROOT, VERTEX


def programs():
    source = ROOT / "app/src/main/java/com/winlator/renderer/PresentationShaders.java"
    with TemporaryDirectory() as tmp:
        java = Path(tmp, "Emit.java")
        java.write_text("""package com.winlator.renderer;
import java.nio.file.*;
public class Emit {
    public static void main(String[] a) throws Exception {
        Files.writeString(Path.of(a[0],"hist"),PresentationShaders.histogramShader());
        Files.writeString(Path.of(a[0],"cine"),PresentationShaders.cinematicShader());
        Files.writeString(Path.of(a[0],"edge"),PresentationShaders.borderShader());
    }
}""")
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp, str(source), str(java)], check=True)
        subprocess.run(["java", "-cp", tmp, "com.winlator.renderer.Emit", tmp], check=True)
        return [shaders.compileProgram(shaders.compileShader(VERTEX, GL.GL_VERTEX_SHADER),
                shaders.compileShader("#version 300 es\n" + Path(tmp, k).read_text(), GL.GL_FRAGMENT_SHADER))
                for k in ["hist", "cine", "edge"]]


def main():
    make_context()
    hist, cine, edge = programs()
    w, h, margin = 192, 108, 24
    game = np.zeros((h, w, 4), dtype=np.uint8)
    game[:, margin:-margin, :3] = (95, 63, 35)
    game[:, :, 3] = 255
    game[0:4, margin:-margin, :3] = 0
    game[-4:, margin:-margin, :3] = 255
    coords = {"origin": (margin/w, 0.), "extent": ((w-2*margin)/w, 1.)}
    tex = texture(w, h, game)
    stats_a, stats_b = texture(1, 1), texture(1, 1)
    first = draw(hist, stats_a, (1, 1), {"screenTexture": tex, "statistics": 0},
                 coords | {"smoothing": 0.})
    brighter = np.minimum(game.astype(int) * 2, 255).astype(np.uint8)
    bright = texture(w, h, brighter)
    instant = draw(hist, stats_b, (1, 1), {"screenTexture": bright, "statistics": stats_a},
                   coords | {"smoothing": 0.})
    smooth = draw(hist, stats_b, (1, 1), {"screenTexture": bright, "statistics": stats_a},
                  coords | {"smoothing": 0.8})
    assert first[0, 0, 0] < smooth[0, 0, 0] < instant[0, 0, 0], (first, smooth, instant)
    out = texture(w, h)
    grade = draw(cine, out, (w, h), {"screenTexture": tex, "statistics": stats_a},
                 coords | {"grade": (1., 3.), "style": (0., 0.)})
    assert not np.array_equal(grade, game)
    assert np.array_equal(grade[0:4], game[0:4]) and np.array_equal(grade[-4:], game[-4:])
    assert np.array_equal(grade[:, :margin], game[:, :margin])
    side = draw(edge, out, (w, h), {"screenTexture": tex}, coords | {"grade": (1., 3.), "style": (.6, .7)})
    assert side[:, :margin, :3].max() > 10 and side[:, -margin:, :3].max() > 10
    assert np.array_equal(side[:, margin:-margin], game[:, margin:-margin])
    rotated = np.rot90(game).copy()
    horizontal = draw(edge, texture(h, w), (h, w),
                      {"screenTexture": texture(h, w, rotated)},
                      {"origin": (0., margin/w), "extent": (1., (w-2*margin)/w),
                       "grade": (1., 1.), "style": (.3, .8)})
    assert horizontal[:margin, :, :3].max() > 10
    assert np.array_equal(horizontal[margin:-margin, :], rotated[margin:-margin, :])
    print("PASS: actual GLES histogram convergence, grade, black/white preservation and intact horizontal/vertical game viewport")


if __name__ == "__main__":
    main()
