#!/usr/bin/env python3
"""Run the actual lens shaders on a software GLES driver."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

from test_tsoa_scene_gpu import draw, texture, make_context, np, GL, shaders, ROOT, VERTEX


def programs():
    source = ROOT / "app/src/main/java/com/winlator/renderer/OpticalShaders.java"
    with TemporaryDirectory() as temp:
        java = Path(temp, "Emit.java")
        java.write_text("""package com.winlator.renderer;
import java.nio.file.*;
public class Emit {
    public static void main(String[] a) throws Exception {
        Files.writeString(Path.of(a[0],"capture"),OpticalShaders.capture());
        Files.writeString(Path.of(a[0],"horizontal"),OpticalShaders.spread(true));
        Files.writeString(Path.of(a[0],"vertical"),OpticalShaders.spread(false));
        Files.writeString(Path.of(a[0],"composite"),OpticalShaders.composite());
        Files.writeString(Path.of(a[0],"probe"),OpticalShaders.probe());
    }
}""")
        subprocess.run(["java","-m","jdk.compiler/com.sun.tools.javac.Main","-d",temp,str(source),str(java)],check=True)
        subprocess.run(["java","-cp",temp,"com.winlator.renderer.Emit",temp],check=True)
        return {name: shaders.compileProgram(shaders.compileShader(VERTEX,GL.GL_VERTEX_SHADER),
                    shaders.compileShader("#version 300 es\n"+Path(temp,name).read_text(),GL.GL_FRAGMENT_SHADER))
                 for name in ("capture","horizontal","vertical","composite","probe")}


def main():
    make_context()
    program = programs()
    w,h,bar = 344,180,12
    frame = np.full((h,w,4),255,dtype=np.uint8)
    frame[...,:3] = (82,72,61)
    frame[...,3] = 220  # Prior cinematic shader bypassed alpha below 0.99.
    frame[:,:bar] = (46,64,85,255)
    frame[:,-bar:] = (46,64,85,255)
    frame[105:119,152:172,:3] = (251,251,251)
    frame[29:34,75:252:3,:3] = 255
    frame[128:163,208:288,:3] = np.repeat(
        (73+16*np.cos(np.arange(80)*1.4)).astype(np.uint8)[None,:,None],3,axis=2)
    coords = {"origin":(bar/w,0.), "extent":((w-2*bar)/w,1.)}
    source = texture(w,h,frame)
    out = texture(w,h)
    aw,ah = 160,90
    atlas,scratch = texture(aw,ah),texture(aw,ah)
    atlas_size = (aw,ah)
    draw(program["capture"],atlas,atlas_size,{"screenTexture":source,"auxiliary":0},
         coords | {"stepUV":(1/aw,1/ah),"hasBloom":0.})
    draw(program["horizontal"],scratch,atlas_size,{"screenTexture":atlas},{"grade":(1.,3.),"stepUV":(1.8/aw,0.),"lightMix":(1.,1.)})
    draw(program["vertical"],atlas,atlas_size,{"screenTexture":scratch},{"stepUV":(0.,1.8/ah),"lightMix":(1.,1.)})
    depth = np.full((90,160,4),255,dtype=np.uint8)
    depth[...,0] = 170
    depth[59:88,:,0] = 85
    depth[...,2] = 0
    depth_texture = texture(160,90,depth)
    trusted = texture(1,1,np.array([[[255,90,190,0]]],dtype=np.uint8))
    cut = texture(1,1,np.array([[[0,90,190,255]]],dtype=np.uint8))

    def compose(lens=0.,focus=0.,halation=0.,grain=0.,vignette=0.,quality=3.,size=1.5,response=.3,
                bloom=0.,aperture=(1.,1.),score=trusted,amount=1.):
        result = draw(program["composite"],out,(w,h),
                      {"screenTexture":source,"auxiliary":atlas,"sceneMap":depth_texture,"sceneScore":score},
                      coords | {"grade":(amount,quality),"lightMix":(lens,halation),
                                "focusVignette":(focus,vignette),"grainTuning":(size,response),
                                "grainAmount":grain,"hasBloom":bloom,"aperture":aperture})
        assert np.array_equal(result[...,3],frame[...,3]), "alpha"
        assert np.array_equal(result[:,:bar],frame[:,:bar]), "ambient left"
        assert np.array_equal(result[:,-bar:],frame[:,-bar:]), "ambient right"
        assert np.array_equal(result[29:34,75:252:3,:3],frame[29:34,75:252:3,:3]), "dialogue letters"
        return result

    assert np.array_equal(compose(),frame)
    assert np.array_equal(compose(lens=1.,focus=1.,halation=1.,grain=1.,vignette=1.,amount=0.),frame)
    flare = compose(lens=1.)
    delta = flare[...,:3].astype(int)-frame[...,:3].astype(int)
    assert delta[107:118,178:208,2].max()>3
    assert delta[135:151,143:180,2].max()<delta[107:118,178:208,2].max()
    halo = compose(halation=1.)
    diff = halo[...,:3].astype(int)-frame[...,:3].astype(int)
    assert diff[98:105,152:172,0].max()>4 and diff[...,0].max()>diff[...,2].max()
    assert compose(halation=1.,bloom=1.)[98:105,152:172,0].max()<halo[98:105,152:172,0].max()
    softened = compose(focus=1.)
    crop = (slice(137,156),slice(220,277),0)
    assert np.std(softened[crop])<np.std(frame[crop])*0.87
    assert np.array_equal(compose(focus=1.,score=cut),frame)
    assert np.array_equal(softened[58:83],frame[58:83])
    grain = compose(grain=1.)
    assert np.std(grain[74:94,57:119,0])>2.2
    assert not np.array_equal(grain,compose(grain=1.,size=3.))
    assert not np.array_equal(grain,compose(grain=1.,response=1.))
    vignette = compose(vignette=1.)
    assert vignette[155,bar+5,0]<=frame[155,bar+5,0]-14
    assert np.array_equal(vignette[90,172],frame[90,172])
    inset = compose(vignette=1.,aperture=(.72,1.))
    assert np.array_equal(inset[:,bar:bar+20],frame[:,bar:bar+20])
    stages = [int(frame[155,bar+5,0]-compose(vignette=1.,quality=q)[155,bar+5,0]) for q in [1.,2.,3.]]
    assert stages==sorted(set(stages)), stages
    proof = draw(program["probe"],texture(10,6),(10,6),{"screenTexture":source,"auxiliary":out},coords)
    assert proof[...,0].max()>10
    print(f"PASS: optics independent, low/medium/ultra vignette {stages}, bright halos, safe focus, adaptive grain and exact bars")


if __name__ == "__main__":
    main()
