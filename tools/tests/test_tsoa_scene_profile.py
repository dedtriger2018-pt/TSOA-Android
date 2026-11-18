#!/usr/bin/env python3
"""Exercise the offline map compiler and the production Java reader. Python/Pillow + JDK."""
import hashlib
import importlib.util
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("scene_compiler", ROOT / "tools/prepare_tsoa_scene.py")
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)

HARNESS = """
import com.winlator.renderer.SceneProfile;
import java.io.*;
public class ReaderCheck {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            try (InputStream in = new FileInputStream(args[i])) {
                SceneProfile p = SceneProfile.read(in);
                if (i > 0 || p.width != 12 || p.height != 6 || p.sourceWidth != 24
                        || p.sourceHeight != 12 || p.structure[4*11+3] != (byte)255
                        || p.reference[0] != 29 || p.reference[1] != (byte)210
                        || p.structure[0] != 60 || p.structure[1] != 0
                        || p.structure[2] != 0 || p.structure[3] != 0)
                    throw new AssertionError(args[i]);
            } catch (IOException e) { if (i == 0) throw e; }
        }
    }
}
"""


class ProfileTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name)
        self.ref, self.depth, self.mask, self.out = (self.folder / name for name in
                                                     ("ref.png", "depth.png", "mask.png", "p.tscene"))
        Image.new("RGB", (24, 12), (29, 210, 71)).save(self.ref)
        Image.new("L", (24, 12), 60).save(self.depth)
        mask = Image.new("L", (24, 12), 255)
        mask.putpixel((1, 1), 0)
        mask.save(self.mask)

    def test_roundtrip_and_malformed(self):
        compiler.prepare(self.ref, self.depth, self.mask, self.out, size=12)
        valid = self.out.read_bytes()
        self.assertEqual(len(valid), 56 + 8 * 12 * 6)
        bad_files = []

        def bad(data):
            path = self.folder / f"bad{len(bad_files)}"
            path.write_bytes(data)
            bad_files.append(path)

        def signed(payload):
            return payload + hashlib.sha256(payload).digest()

        bad(valid + bytes(1))
        bad(valid[:-1])
        bad(valid[:120])
        bad(valid[:-32] + bytes(32))
        for offset, replacement in ((0, b"X"), (8, struct.pack(">I", 2)),
                                    (12, bytes(4)), (12, b"\xff" * 4),
                                    (20, struct.pack(">H", 257)), (22, struct.pack(">H", 3)),
                                    (27, b"\x00")):
            payload = bytearray(valid[:-32])
            payload[offset:offset+len(replacement)] = replacement
            bad(signed(payload))

        java = self.folder / "ReaderCheck.java"
        java.write_text(HARNESS)
        javac = (["javac"] if shutil.which("javac") else
                 ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"])
        subprocess.run([*javac, "-d", str(self.folder), str(java),
                        str(ROOT / "app/src/main/java/com/winlator/renderer/SceneProfile.java")],
                       check=True)
        subprocess.run(["java", "-cp", str(self.folder), "ReaderCheck", str(self.out),
                        *map(str, bad_files)], check=True)

    def test_registration_and_inputs(self):
        alpha = Image.new("L", (24, 12), 255)
        alpha.save(self.mask)
        motion = self.folder / "motion.png"
        Image.new("L", (24, 12), 200).save(motion)
        compiler.prepare(self.ref, self.depth, self.mask, self.out, motion, self.depth, size=10)
        data = self.out.read_bytes()
        self.assertEqual(data[24+4*50:28+4*50], bytes((60, 200, 60, 255)))
        with self.assertRaises(ValueError):
            compiler.prepare(self.ref, self.depth, self.mask, self.ref)
        Image.new("L", (3, 7)).save(self.depth)
        with self.assertRaises(ValueError):
            compiler.prepare(self.ref, self.depth, self.mask, self.out)
        self.assertEqual(self.out.read_bytes(), data, "Failed generation replaced good cache")


if __name__ == "__main__":
    unittest.main()
