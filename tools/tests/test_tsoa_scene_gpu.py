#!/usr/bin/env python3
"""Exercise production scene GLSL on Linux Mesa (EGL), using synthetic real-frame sequences.

Requires JDK 17+, Python with numpy and PyOpenGL, and a GLES3-capable EGL driver.
Run directly: python3 tools/tests/test_tsoa_scene_gpu.py
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

os.environ.setdefault("EGL_PLATFORM", "surfaceless")
os.environ.setdefault("PYOPENGL_PLATFORM", "egl")

import numpy as np
from OpenGL import EGL, GL
from OpenGL.GL import shaders

ROOT = Path(__file__).resolve().parents[2]
VERTEX = """#version 300 es
out vec2 vUV;
void main() {
    vUV = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
}
"""


def make_context():
    display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
    assert EGL.eglInitialize(display, None, None)
    config, count = EGL.EGLConfig(), EGL.EGLint()
    attrs = (EGL.EGLint * 5)(EGL.EGL_RENDERABLE_TYPE, 0x40,
                              EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT, EGL.EGL_NONE)
    assert EGL.eglChooseConfig(display, attrs, ctypes.byref(config), 1, count) and count.value
    dims = (EGL.EGLint * 5)(EGL.EGL_WIDTH, 1, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE)
    surface = EGL.eglCreatePbufferSurface(display, config, dims)
    assert EGL.eglBindAPI(EGL.EGL_OPENGL_ES_API)
    attrs = (EGL.EGLint * 3)(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE)
    context = EGL.eglCreateContext(display, config, EGL.EGL_NO_CONTEXT, attrs)
    assert EGL.eglMakeCurrent(display, surface, surface, context)
    GL.glDisable(GL.GL_DITHER)
    return display


def production_programs():
    emitter = """package com.winlator.renderer;
import java.nio.file.*;
class EmitScene {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0], "capture"), SceneShaders.capture());
        Files.writeString(Path.of(args[0], "recognize"), SceneShaders.recognize());
        Files.writeString(Path.of(args[0], "estimate"), SceneShaders.estimate());
        Files.writeString(Path.of(args[0], "lumenMap"), SceneShaders.lumenMap());
        Files.writeString(Path.of(args[0], "reconstruct"), SceneShaders.reconstruct());
        Files.writeString(Path.of(args[0], "probe"), SceneShaders.probe());
    }
}"""
    scene = ROOT / "app/src/main/java/com/winlator/renderer/SceneShaders.java"
    with TemporaryDirectory() as temp:
        source = Path(temp, "EmitScene.java")
        source.write_text(emitter)
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main",
                        "-d", temp, str(source), str(scene)], check=True)
        subprocess.run(["java", "-cp", temp, "com.winlator.renderer.EmitScene", temp], check=True)
        return {name: shaders.compileProgram(
            shaders.compileShader(VERTEX, GL.GL_VERTEX_SHADER),
            shaders.compileShader("#version 300 es\n" + Path(temp, name).read_text(), GL.GL_FRAGMENT_SHADER))
                for name in ("capture", "recognize", "estimate", "lumenMap", "reconstruct", "probe")}


def texture(width, height, pixels=None):
    tex = GL.glGenTextures(1)
    GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
    for key in (GL.GL_TEXTURE_MIN_FILTER, GL.GL_TEXTURE_MAG_FILTER):
        GL.glTexParameteri(GL.GL_TEXTURE_2D, key, GL.GL_LINEAR)
    for key in (GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T):
        GL.glTexParameteri(GL.GL_TEXTURE_2D, key, GL.GL_CLAMP_TO_EDGE)
    GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, width, height, 0, GL.GL_RGBA,
                    GL.GL_UNSIGNED_BYTE, None if pixels is None else np.ascontiguousarray(pixels))
    return tex


def draw(program, target, size, inputs, values):
    fbo = GL.glGenFramebuffers(1)
    GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
    GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, target, 0)
    assert GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
    GL.glViewport(0, 0, *size)
    GL.glUseProgram(program)
    for unit, (name, tex) in enumerate(inputs.items()):
        location = GL.glGetUniformLocation(program, name)
        assert location >= 0, name
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glUniform1i(location, unit)
    for name, value in values.items():
        location = GL.glGetUniformLocation(program, name)
        assert location >= 0, name
        if isinstance(value, tuple):
            GL.glUniform2f(location, *value)
        else:
            GL.glUniform1f(location, value)
    GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
    data = GL.glReadPixels(0, 0, *size, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE)
    GL.glDeleteFramebuffers(1, [fbo])
    assert GL.glGetError() == GL.GL_NO_ERROR
    return np.frombuffer(data, dtype=np.uint8).reshape(size[1], size[0], 4)


class Scene:
    def __init__(self, programs, size, padding=0):
        self.programs = programs
        w, h = size
        self.size = (w + 2 * padding, h)
        self.padding = padding
        self.map_size = (96, 54)
        self.reference = [texture(*self.map_size), texture(*self.map_size)]
        self.structure = [texture(*self.map_size), texture(*self.map_size)]
        self.smart = [texture(*self.map_size), texture(*self.map_size)]
        self.score, self.output, self.probe = texture(1, 1), texture(*self.size), texture(8, 4)
        self.index = 0
        self.ready = False
        self.coords = {"viewOrigin": (padding / self.size[0], 0.), "viewExtent": (w / self.size[0], 1.)}
        self.step = {"mapStep": tuple(1 / n for n in self.map_size)}

    def draw(self, game, levels=(3, 3), phase=1.4, analyze=False):
        src = texture(*self.size, np.pad(game, ((0, 0), (self.padding, self.padding), (0, 0))))
        def recognize():
            return draw(self.programs["recognize"], self.score, (1, 1),
                        {"screenTexture": src, "referenceTexture": self.reference[self.index]},
                        self.coords | self.step | {"history": float(self.ready)})[0, 0]

        score = recognize()
        if analyze or not self.ready:
            nxt = 1 - self.index
            draw(self.programs["capture"], self.reference[nxt], self.map_size,
                 {"screenTexture": src}, self.coords | self.step)
            draw(self.programs["estimate"], self.structure[nxt], self.map_size,
                 {"screenTexture": self.reference[nxt], "referenceTexture": self.reference[self.index],
                  "structureTexture": self.structure[self.index], "scoreTexture": self.score}, self.step)
            draw(self.programs["lumenMap"], self.smart[nxt], self.map_size,
                 {"screenTexture": self.reference[nxt], "structureTexture": self.structure[nxt],
                  "scoreTexture": self.score, "smartTexture": self.smart[self.index]},
                 self.step | {"history": float(self.ready)})
            self.index = nxt
            if not self.ready:
                self.ready = True
                score = recognize()

        result = draw(self.programs["reconstruct"], self.output, self.size,
                      {"screenTexture": src, "referenceTexture": self.reference[self.index],
                       "structureTexture": self.structure[self.index], "scoreTexture": self.score,
                       "smartTexture": self.smart[self.index]},
                      self.coords | self.step | {"levels": levels, "phase": phase})
        measurement = draw(self.programs["probe"], self.probe, (8, 4),
                           {"screenTexture": src, "referenceTexture": self.output, "scoreTexture": self.score},
                           self.coords)
        GL.glDeleteTextures([src])
        return score, result, measurement


def fixture():
    h, w = 180, 320
    y, x = np.mgrid[:h, :w] / np.array([[[h]], [[w]]])
    image = np.full((h, w, 4), 255, dtype=np.uint8)
    image[..., 0] = 95 + 48 * np.sin(8*x + 3*y) + 18 * np.cos(41*x - 18*y)
    image[..., 1] = 116 + 72 * np.sin(5*y - 6*x)
    image[..., 2] = 124 + 68 * np.cos(13*x + 2*y)
    return image


def delta(a, b):
    return abs(a.astype(int) - b.astype(int))


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--reference", type=Path, help="optional local image (requires Pillow)")
    cli.add_argument("--other-scene", type=Path, help="different local scene")
    args = cli.parse_args()
    if bool(args.reference) != bool(args.other_scene):
        cli.error("supply both --reference and --other-scene")
    display = make_context()
    programs = production_programs()
    source = fixture()
    model = Scene(programs, (320, 180), padding=12)
    expected = np.pad(source, ((0, 0), (12, 12), (0, 0)))
    score, off, probe = model.draw(source, (0, 0))
    assert score[0] >= 230 and np.array_equal(off, expected) and not probe[..., 0].max()
    model.draw(source, analyze=True)
    model.draw(source, analyze=True)
    measured = {}
    for levels in ((1, 0), (2, 0), (3, 0), (0, 1), (0, 2), (0, 3), (3, 3)):
        score, out, probe = model.draw(source, levels)
        difference = delta(out, expected)
        assert score[0] >= 230 and probe[..., 0].max() > 1, (levels, score, probe[..., 0].max())
        assert np.array_equal(out[..., 3], expected[..., 3])
        assert not difference[:43].max(), "dialogue band"
        assert not difference[:, :12].max() and not difference[:, -12:].max(), "outside viewport"
        measured[str(levels)] = int(probe[..., 0].max())
    assert measured["(0, 3)"] > measured["(0, 1)"]
    assert not np.array_equal(model.draw(source, (3, 0), phase=.3)[1], model.draw(source, (3, 0), phase=3.5)[1])

    # Medium cannot cut more than a few 8-bit levels of signed diffuse/AO.
    _, medium, _ = model.draw(source, (0, 2))
    assert (expected[..., :3].astype(int) - medium[..., :3].astype(int)).max() <= 7
    # Alternating paint texture is incoherent, while a broad gradient survives.
    texture_paint = source.copy()
    for x in range(source.shape[1]):
        noise = 21 if x % 6 < 3 else -21
        texture_paint[:, x, :3] = np.clip(texture_paint[:, x, :3].astype(int) + noise, 0, 255)
    painted = Scene(programs, (320, 180))
    for _ in range(3):
        _, painted_result, _ = painted.draw(texture_paint, (0, 2), analyze=True)
    signed = painted_result[..., :3].astype(int) - texture_paint[..., :3].astype(int)
    assert np.abs(np.diff(signed[72:145, 40:280], axis=1)).mean() < 1.6
    assert not np.array_equal(painted_result, texture_paint)

    overlay = source.copy()
    overlay[82:128, 140:186] = (250, 14, 220, 255)
    overlay[60:70, 70:170:2] = (255, 255, 255, 255)
    score, out, probe = model.draw(overlay)
    assert score[0] and probe[..., 0].max(), "local overlay rejected entire scene"
    shifted = out[:, 12:-12]
    assert np.array_equal(shifted[82:128, 140:186], overlay[82:128, 140:186]), "transient foreground"
    assert np.array_equal(shifted[60:70, 70:170:2], overlay[60:70, 70:170:2]), "thin text"

    other = source.copy()
    other[..., :3] = 255 - source[..., :3]
    score, out, probe = model.draw(other, analyze=True)
    assert not score[0] and score[3] > 245 and not probe[..., 0].max()
    assert np.array_equal(out[:, 12:-12], other), "scene cut must bypass old structure"
    score, out, probe = model.draw(other, analyze=True)
    assert score[0] >= 230 and probe[..., 0].max() > 1, "new scene did not bootstrap automatically"
    flat = np.full_like(source, (115, 115, 115, 255))
    _, _, _ = model.draw(flat, analyze=True)
    score, out, probe = model.draw(flat, analyze=True)
    assert not score[0] and not probe[..., 0].max(), "confirmed flat screen should bypass"

    report = {"driver": GL.glGetString(GL.GL_RENDERER).decode(), "max_delta_8bit": measured,
              "scene_cut_bypass": True, "automatic_recovery": True, "protected_regions": True}
    if args.reference:
        from PIL import Image
        def local_capture(path):
            with Image.open(path) as image:
                return np.asarray(image.convert("RGBA").resize((960, 540)))[::-1].copy()
        local = Scene(programs, (960, 540))
        first = local_capture(args.reference)
        for _ in range(3):
            score, out, probe = local.draw(first, analyze=True)
        assert score[0] > 220 and (probe[..., 0] > 1).any()
        report["local_capture"] = {"confidence8": int(score[0]), "changed_pixels": int(np.count_nonzero(
            np.any(delta(out, first)[..., :3] > 1, axis=2))), "max_delta_8bit": int(probe[..., 0].max())}
        score, out, probe = local.draw(local_capture(args.other_scene), analyze=True)
        assert score[0] == 0 and not probe[..., 0].max()
    EGL.eglTerminate(display)
    print(json.dumps(report))


if __name__ == "__main__":
    main()
