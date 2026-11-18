#!/usr/bin/env python3
"""Optional offline browser integration test. Requires Pillow, Playwright, Chromium, and a JDK."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]


class SceneEditorTest(unittest.TestCase):
    def test_export_local_image(self):
        with tempfile.TemporaryDirectory() as scratch, sync_playwright() as playwright:
            out = Path(scratch)
            image = out / 'sample.png'
            Image.new('RGB', (600, 400), (81, 92, 103)).save(image)
            browser = playwright.chromium.launch(executable_path=shutil.which('chromium'), args=['--no-sandbox'])
            page = browser.new_page(viewport={'width': 400, 'height': 800}, accept_downloads=True)
            page_errors = []
            page.on('pageerror', lambda err: page_errors.append(str(err)))
            external = []
            page.on('request', lambda request: external.append(request.url)
                    if request.url.startswith(('https:', 'http:')) else None)
            page.set_content((ROOT / 'app/src/main/assets/tsoa_scene_editor/create_tsoa_scene.html').read_text())
            page.locator('#file').set_input_files(image)
            canvas = page.locator('#preview')
            canvas.wait_for(state='visible')
            self.assertTrue(page.locator('#prepare').is_disabled())
            canvas.scroll_into_view_if_needed()
            rect = canvas.bounding_box()
            for fraction, action in ((.2, 'down'), (.8, 'up')):
                page.mouse.move(rect['x'] + fraction * rect['width'], rect['y'] + fraction * rect['height'])
                getattr(page.mouse, action)()

            # Exercise the pure JS digest used by nonsecure Android content URI contexts.
            page.evaluate("Object.defineProperty(globalThis, 'crypto', {value: {}})")
            page.locator('#prepare').click()
            with page.expect_download() as capture:
                page.locator('#download').click()
            self.assertEqual(capture.value.suggested_filename, 'sample.tscene')
            path = out / 'sample.tscene'
            capture.value.save_as(path)
            data = path.read_bytes()
            self.assertEqual(struct.unpack_from('>8sIIIHH', data), (b'TSOSCENE', 1, 600, 400, 256, 171))
            self.assertEqual(data[-32:], hashlib.sha256(data[:-32]).digest())
            n = 256 * 171 * 4
            self.assertEqual(len(data), 56 + 2 * n)
            self.assertEqual(data[24:24 + n], bytes((81, 92, 103, 255)) * (n // 4))
            structure = data[24 + n:-32]

            def pixel(x, y):
                start = 4 * (256 * y + x)
                return tuple(structure[start:start + 4])
            self.assertEqual(pixel(0, 0)[3], 0)
            self.assertEqual(pixel(210, 137)[3], 0)
            self.assertGreater(pixel(130, 120)[0], pixel(130, 51)[0])
            self.assertEqual(pixel(130, 85)[1:], (102, 38, 255))
            self.assertTrue(any(0 < a < 255 for a in structure[3::4]))
            runner = out / 'Read.java'
            runner.write_text('import com.winlator.renderer.SceneProfile; import java.nio.file.*; '
                              'class Read { public static void main(String[] args) throws Exception {'
                              'try (java.io.InputStream in = Files.newInputStream(Paths.get(args[0]))) {'
                              'SceneProfile p = SceneProfile.read(in); '
                              'if (p.width != 256 || p.height != 171) throw new AssertionError(); } } }')
            compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
            source = ROOT / 'app/src/main/java/com/winlator/renderer/SceneProfile.java'
            subprocess.run([*compiler, '-d', str(out), str(source), str(runner)], check=True)
            subprocess.run(['java', '-cp', str(out), 'Read', str(path)], check=True)

            page.evaluate("window.TsoaSceneEditor = {install: encoded => {window.installed = encoded}}")
            page.locator('#prepare').click()
            page.wait_for_function('typeof window.installed === "string"')
            self.assertEqual(base64.b64decode(page.evaluate('window.installed'), validate=True), data)

            page.locator('#undo').click()
            self.assertTrue(page.locator('#prepare').is_disabled())
            self.assertFalse(page.locator('#download').is_visible())

            # Optional test against a separately downloaded exact public reference.
            if reference := os.getenv('TSOA_STEAM_REFERENCE'):
                page.locator('#file').set_input_files(Path(reference))
                page.get_by_text('Referência reconhecida.', exact=False).wait_for()
                self.assertFalse(page.locator('#prepare').is_disabled())
                # Change one trailing byte while retaining valid JPEG, size and dimensions.
                changed = bytearray(Path(reference).read_bytes())
                at = changed.index(b'JFIF') if b'JFIF' in changed else 0
                if at:
                    changed[at] ^= 1
                    other = out / 'different.jpg'
                    other.write_bytes(changed)
                    page.locator('#file').set_input_files(other)
                    canvas.wait_for(state='visible')
                    self.assertTrue(page.locator('#prepare').is_disabled())
            for language, title in [('en', 'Scene profile'), ('zh-CN', '场景配置')]:
                page.goto('about:blank?lang=' + language)
                page.set_content((ROOT / 'app/src/main/assets/tsoa_scene_editor/create_tsoa_scene.html').read_text())
                self.assertEqual(page.locator('h1').inner_text(), title)
            self.assertEqual(page_errors, [])
            self.assertEqual(external, [])
            browser.close()


if __name__ == '__main__':
    unittest.main()
