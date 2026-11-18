#!/usr/bin/env python3
"""Exercise production scene GLSL on Linux Mesa (EGL), using authored synthetic fixtures.

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
        Files.writeString(Path.of(args[0], "score"), SceneShaders.getRecognitionFragmentShader());
        Files.writeString(Path.of(args[0], "render"), SceneShaders.getReconstructionFragmentShader());
    }
}"""
    scene = ROOT / "app/src/main/java/com/winlator/renderer/SceneShaders.java"
    with TemporaryDirectory() as temp:
        source = Path(temp, "EmitScene.java")
        source.write_text(emitter)
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main",
                        "-d", temp, str(source), str(scene)], check=True)
        subprocess.run(["java", "-cp", temp, "com.winlator.renderer.EmitScene", temp], check=True)
        return [shaders.compileProgram(
            shaders.compileShader(VERTEX, GL.GL_VERTEX_SHADER),
            shaders.compileShader("#version 300 es\n" + Path(temp, name).read_text(), GL.GL_FRAGMENT_SHADER))
                for name in ("score", "render")]


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


def frame(programs, reference, structure, game, levels=(3, 3), phase=1.4, padding=0):
    # Test data is top-down. The game texture lives in a bottom-up compositor FBO.
    h, w = game.shape[:2]
    ph, pw = reference.shape[:2]
    screen = np.pad(game, ((0, 0), (padding, padding), (0, 0)))
    sw = screen.shape[1]
    src = texture(sw, h, screen[::-1])
    ref = texture(pw, ph, reference)
    struct = texture(pw, ph, structure)
    score, output = texture(1, 1), texture(sw, h)
    viewport = {"viewOrigin": (padding / sw, 0.), "viewExtent": (w / sw, 1.)}
    inputs = {"screenTexture": src, "referenceTexture": ref}
    recognition = draw(programs[0], score, (1, 1), inputs, viewport)
    inputs.update(structTexture=struct, scoreTexture=score)
    result = draw(programs[1], output, (sw, h), inputs,
                  dict(viewport, levels=levels, phase=phase, profileStep=(1 / pw, 1 / ph)))
    GL.glDeleteTextures([src, ref, struct, score, output])
    return int(recognition[0, 0, 0]), result[::-1]


def fixture():
    h, w = 120, 192
    y, x = np.mgrid[:h, :w] / np.array([[[h]], [[w]]])
    ref = np.full((h, w, 4), 255, dtype=np.uint8)
    ref[..., 0] = 95 + 48 * np.sin(8*x + 3*y) + 18 * np.cos(41*x - 18*y)
    ref[..., 1] = 116 + 72 * np.sin(5*y - 6*x)
    ref[..., 2] = 124 + 68 * np.cos(13*x + 2*y)
    struct = np.full_like(ref, 255)
    struct[..., 0] = 140 + 72 * np.sin(8*x + 2*y) + 28 * np.cos(5*y)
    struct[..., 3] = np.where(y < .15, 0, 255)
    return ref, struct


def delta(a, b):
    return abs(a.astype(int) - b.astype(int))


def local_screens(programs, first, other):
    """Compare two local captures at 960-wide guest vs 256-wide authored maps."""
    from PIL import Image

    def load(path):
        with Image.open(path) as source:
            image = source.convert("RGBA")
        image.thumbnail((960, 960), Image.Resampling.LANCZOS)
        return image

    scene, cut = load(first), load(other)
    if scene.size != cut.size:
        raise ValueError("Choose two local captures with matching dimensions")
    profile = scene.copy()
    profile.thumbnail((256, 256), Image.Resampling.LANCZOS)
    ref = np.array(profile)
    h, w = ref.shape[:2]
    y, x = np.mgrid[:h, :w] / np.array([[[h]], [[w]]])
    struct = np.zeros_like(ref)
    struct[..., 0] = 70 + 140*y + 12*np.sin(6*x)
    struct[..., 1:3] = 192
    struct[..., 3] = np.where((y > .2) & (y < .6) & (x > .2) & (x < .8), 255, 0)
    score, current = frame(programs, ref, struct, np.array(scene))
    other_score, unknown = frame(programs, ref, struct, np.array(cut))
    changed = int(np.count_nonzero(np.any(current != np.array(scene), axis=2)))
    assert score > 0 and changed > 0, "local capture never matched"
    assert not other_score and np.array_equal(unknown, np.array(cut)), "local alternate capture affected"
    return {"profile_size": profile.size, "frame_size": scene.size, "score": score,
            "changed_pixels": changed, "other_scene_score": other_score}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--reference", type=Path, help="optional local image (requires Pillow)")
    cli.add_argument("--other-scene", type=Path, help="different scene to test rejection")
    args = cli.parse_args()
    if bool(args.reference) != bool(args.other_scene):
        cli.error("supply both --reference and --other-scene")
    display = make_context()
    programs = production_programs()
    ref, struct = fixture()
    score, off = frame(programs, ref, struct, ref, (0, 0))
    assert score >= 240 and np.array_equal(off, ref), ("off", score, delta(off, ref).max())
    measured = {}
    for levels in ((1, 0), (2, 0), (3, 0), (0, 1), (0, 2), (0, 3), (3, 3)):
        score, output = frame(programs, ref, struct, ref, levels)
        d = delta(output, ref)
        assert score >= 240 and d.max() > 0, (levels, score)
        assert np.array_equal(output[..., 3], ref[..., 3]), "alpha"
        assert d[:15].max() == 0, "authored protection mask"
        measured[str(levels)] = round(float(d[..., :3].mean()), 3)

    _, animated = frame(programs, ref, struct, ref, (3, 0), phase=3.5)
    _, previous = frame(programs, ref, struct, ref, (3, 0), phase=.3)
    assert delta(animated, previous).max() > 0, "Genesis did not move eligible pixels"
    no_motion = struct.copy()
    no_motion[..., 1] = 0
    _, still = frame(programs, ref, no_motion, ref, (3, 0))
    assert np.array_equal(still, ref), "Genesis moved a region with no authored motion"

    matte = struct.copy()
    matte[..., 2] = 0
    _, matte_light = frame(programs, ref, matte, ref, (0, 2))
    _, reflective_light = frame(programs, ref, struct, ref, (0, 2))
    assert delta(matte_light, reflective_light).max() > 1, "reflectivity had no effect"

    flat = np.full_like(ref, 115)
    flat[..., 3] = 255
    flat_score, untouched = frame(programs, flat, struct, flat)
    assert not flat_score and np.array_equal(untouched, flat), "recognized ambiguous flat scene"

    overlay = ref.copy()
    overlay[40:70, 64:86, :] = (240, 10, 215, 197)
    overlay[86:95, 42:145:2, :] = (255, 255, 255, 219)
    score, result = frame(programs, ref, struct, overlay)
    assert score > 0 and delta(result, overlay).max() > 0, "partial overlay discarded whole scene"
    assert np.array_equal(result[40:70, 64:86], overlay[40:70, 64:86]), "foreground"
    assert np.array_equal(result[86:95, 42:145:2], overlay[86:95, 42:145:2]), "thin text"
    assert np.array_equal(result[..., 3], overlay[..., 3]), "source alpha"

    unrelated = ref.copy()
    unrelated[..., :3] = 255 - ref[..., :3]
    cut_score, cut = frame(programs, ref, struct, unrelated)
    assert cut_score == 0 and np.array_equal(cut, unrelated), "scene cut retained effects"

    score, bars = frame(programs, ref, struct, ref, padding=40)
    assert score >= 240 and bars[:, :40].max() == 0 and bars[:, -40:].max() == 0, "fitted viewport"
    _, full = frame(programs, ref, struct, ref)
    assert delta(bars[:, 40:-40], full).max() <= 1, "viewport misaligned profile"

    report = {"driver": GL.glGetString(GL.GL_RENDERER).decode(), "unmatched_score": cut_score,
              "matched_score": score, "mean_rgb_change_8bit": measured}
    if args.reference:
        report["local_captures"] = local_screens(programs, args.reference, args.other_scene)
    print(json.dumps(report))
    for program in programs:
        GL.glDeleteProgram(program)
    EGL.eglTerminate(display)


if __name__ == "__main__":
    main()
