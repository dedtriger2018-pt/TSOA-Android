"""Integration checks against the actual packaged ARM64 Box64; never execute it."""

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
SCRIPT = PROJECT / "tools/prepare_tsoa_box64.py"
SOURCE = PROJECT / "app/src/main/assets/box64/box64-0.4.4.tzst"
PATCHELF = os.environ.get("TSOA_TEST_PATCHELF", "patchelf")
ROOTFS = "/data/user/0/com.naninovel.mobile/files/rootfs"


def invoke(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], capture_output=True, text=True)


def archive_contents(path):
    data = subprocess.run(["zstd", "-qdc", str(path)], check=True, capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
        metadata = {m.name: (m.mode, m.uid, m.gid, m.uname, m.gname, m.mtime,
                             m.type, m.linkname, m.devmajor, m.devminor, m.pax_headers)
                    for m in archive.getmembers()}
        binary = next(m for m in archive.getmembers() if m.name.removeprefix("./") == "usr/local/bin/box64")
        return metadata, archive.extractfile(binary).read()


@unittest.skipUnless(all(shutil.which(x) for x in (PATCHELF, "zstd", "readelf")),
                     "Install patchelf, zstd and binutils for native packaging checks")
class Box64PreparationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="tsoa-box64-tests-")
        cls.directory = Path(cls.temporary.name)
        cls.prepared = cls.directory / "prepared.tzst"
        cls.report = cls.directory / "report.json"
        result = invoke("--asset", SOURCE, "--output", cls.prepared, "--report", cls.report,
                        "--patchelf", PATCHELF)
        if result.returncode:
            cls.temporary.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_relocated_elf_and_tar_metadata(self):
        source_metadata, _ = archive_contents(SOURCE)
        target_metadata, binary = archive_contents(self.prepared)
        self.assertEqual(source_metadata, target_metadata)
        binary_path = self.directory / "box64-for-readelf"
        binary_path.write_bytes(binary)
        output = subprocess.run(["readelf", "-hldW", str(binary_path)], check=True,
                                capture_output=True, text=True, env=dict(os.environ, LC_ALL="C")).stdout
        self.assertIn(f"Requesting program interpreter: {ROOTFS}/lib/ld-linux-aarch64.so.1", output)
        self.assertIn(f"Library runpath: [{ROOTFS}/lib]", output)
        self.assertIn("AArch64", output)
        self.assertIn("0x34b24440", output)
        report = json.loads(self.report.read_text())
        self.assertEqual(2, report["source"]["elf"]["type"])
        self.assertEqual(2, report["target"]["elf"]["type"])
        self.assertEqual(183, report["target"]["elf"]["machine"])
        self.assertEqual(hashlib.sha256(binary).hexdigest(), report["target"]["binary_sha256"])
        self.assertEqual(report["source"]["elf"]["executable_sections"], report["target"]["elf"]["executable_sections"])
        self.assertEqual(report["source"]["elf"]["tls"], report["target"]["elf"]["tls"])
        metadata = json.loads((self.directory / "tsoa-relocation.json").read_text())
        self.assertEqual(metadata, {"asset_path": "box64/box64-0.4.4.tzst",
                                   "binary_sha256": report["target"]["binary_sha256"],
                                   "binary_size": len(binary),
                                   "interpreter": ROOTFS + "/lib/ld-linux-aarch64.so.1",
                                   "runpath": ROOTFS + "/lib"})

    def test_in_place_idempotence_and_read_only_check(self):
        before = self.prepared.read_bytes()
        mtime = self.prepared.stat().st_mtime_ns
        for extra in (("--patchelf", PATCHELF), ("--check",)):
            result = invoke("--asset", self.prepared, "--report", self.report, *extra)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, self.prepared.read_bytes())
        self.assertEqual(mtime, self.prepared.stat().st_mtime_ns)

    def test_unexpected_source_hash_is_rejected_without_overwriting(self):
        bad_asset = self.directory / "unknown.tzst"
        bad_asset.write_bytes(b"not the pinned Box64 asset")
        result = invoke("--asset", bad_asset, "--report", self.directory / "unknown-report.json",
                        "--patchelf", PATCHELF)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"not the pinned Box64 asset", bad_asset.read_bytes())

    def test_failed_patchelf_does_not_replace_source(self):
        source_copy = self.directory / "failure.tzst"
        shutil.copyfile(SOURCE, source_copy)
        original = source_copy.read_bytes()
        result = invoke("--asset", source_copy, "--report", self.directory / "failure-report.json",
                        "--patchelf", shutil.which("false"))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, source_copy.read_bytes())

    def test_metadata_path_cannot_overwrite_archive(self):
        before = self.prepared.read_bytes()
        result = invoke("--asset", self.prepared, "--report", self.prepared,
                        "--patchelf", PATCHELF)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not overwrite", result.stderr)
        self.assertEqual(before, self.prepared.read_bytes())

    def test_check_rejects_tampered_metadata_and_archive(self):
        with tempfile.TemporaryDirectory(dir=self.directory) as temporary:
            directory = Path(temporary)
            target = directory / "prepared.tzst"
            report = directory / "report.json"
            metadata = directory / "tsoa-relocation.json"
            shutil.copyfile(self.prepared, target)
            shutil.copyfile(self.report, report)
            shutil.copyfile(self.directory / "tsoa-relocation.json", metadata)
            metadata.write_text("{}")
            result = invoke("--asset", target, "--report", report, "--check")
            self.assertNotEqual(0, result.returncode)
            shutil.copyfile(self.directory / "tsoa-relocation.json", metadata)
            with target.open("ab") as stream:
                stream.write(b"changed")
            result = invoke("--asset", target, "--report", report, "--check")
            self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
