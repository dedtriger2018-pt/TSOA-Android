#!/usr/bin/env python3
"""Relocate the bundled ARM64 Box64 ELF before assembling the dedicated APK.

Requires host-side patchelf, zstd and readelf (binutils). Does not execute Box64.
The tracked .tzst is replaced only in the build working tree; no Android-side
patching or committed replacement binary is needed. Run --check after preparing.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile


PROJECT = Path(__file__).resolve().parents[1]
ASSET = PROJECT / "app/src/main/assets/box64/box64-0.4.4.tzst"
REPORT = PROJECT / "build/tsoa-box64-relocation.json"
ROOTFS = "/data/user/0/com.naninovel.mobile/files/rootfs"
OLD_ROOTFS = "/data/data/com.winlator/files/rootfs"
ARCHIVE_SHA256 = "e1a34fed50bb38ef7fbf51ad5e5b9a76ec4c8178f9cfb4b9f6def0497f2f446c"
BINARY_SHA256 = "9c1517392d5d1824cd64e8d26f7aed9fb28402b4fa23cbb24f564cf6e2608ca9"
MEMBER = "usr/local/bin/box64"
PACKAGED_ASSET_PATH = "box64/box64-0.4.4.tzst"
PAGE_SIZE = 65536
ENTRY_POINT = 0x34B24440
NEEDED = ["libc.so.6", "libm.so.6", "libresolv.so.2", "ld-linux-aarch64.so.1"]


class PreparationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PreparationError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(command: list[str]) -> str:
    environment = dict(os.environ, LC_ALL="C")
    result = subprocess.run(command, capture_output=True, text=True, env=environment)
    if result.returncode:
        raise PreparationError(
            f"Command failed ({result.returncode}): {' '.join(command)}\n{result.stderr.strip()}"
        )
    return result.stdout


def elf_details(data: bytes) -> dict:
    """Check the native ELF layout and fingerprint executable code and TLS."""
    require(len(data) >= 64, "Box64 is shorter than an ELF64 header")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    ident, kind, machine, _, entry, phoff, shoff = header[:7]
    require(ident[:7] == b"\x7fELF\x02\x01\x01", "Expected little-endian ELF64")
    require(kind == 2 and machine == 183, "Expected an AArch64 ET_EXEC Box64")
    require(entry == ENTRY_POINT, "Unexpected Box64 entry point")
    phsize, phnum, shsize, shnum, shstrings = header[9:14]
    require(phsize == 56 and shsize == 64, "Unexpected ELF table entry size")
    require(phoff + phsize * phnum <= len(data), "Program headers exceed ELF size")
    require(shoff + shsize * shnum <= len(data), "Section headers exceed ELF size")
    require(shnum > 0 and shstrings < shnum, "Missing ELF section-name table")
    programs = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(phnum)]
    loads = [p for p in programs if p[0] == 1]
    require(bool(loads), "ELF has no PT_LOAD segments")
    for p in loads:
        _, _, offset, address, _, filesz, memsz, alignment = p
        require(filesz <= memsz and offset + filesz <= len(data), "Invalid PT_LOAD bounds")
        require(alignment == PAGE_SIZE, "PT_LOAD lost its 64 KiB alignment")
        require(offset % alignment == address % alignment, "Invalid PT_LOAD offset/address alignment")
    require(any(p[1] & 1 and p[3] <= entry < p[3] + p[6] for p in loads),
            "Entry point is outside executable PT_LOAD segments")
    interpreters = [p for p in programs if p[0] == 3]
    require(len(interpreters) == 1, "Expected exactly one PT_INTERP")
    interp = interpreters[0]
    require(interp[2] + interp[5] <= len(data), "PT_INTERP exceeds ELF size")
    interpreter = data[interp[2]:interp[2] + interp[5]].rstrip(b"\0").decode("utf-8")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shsize) for i in range(shnum)]
    strings = sections[shstrings]
    require(strings[4] + strings[5] <= len(data), "Invalid section-name table")
    names = data[strings[4]:strings[4] + strings[5]]
    executable = []
    for section in sections:
        if section[2] & 4:
            name_offset, section_kind, flags, address, offset, size = section[:6]
            require(offset + size <= len(data), "Executable section exceeds ELF size")
            name = names[name_offset:].split(b"\0", 1)[0].decode("utf-8")
            executable.append([name, section_kind, flags, address, size, sha256(data[offset:offset + size])])
    require(bool(executable), "No executable ELF sections found")
    tls = []
    for p in programs:
        if p[0] == 7:
            require(p[2] + p[5] <= len(data), "PT_TLS exceeds ELF size")
            tls.append([p[1], p[3], p[5], p[6], p[7], sha256(data[p[2]:p[2] + p[5]])])
    return {"type": kind, "machine": machine, "entry": entry, "flags": header[7],
            "interpreter": interpreter, "executable_sections": sorted(executable), "tls": tls}


def dynamic_details(binary: Path, readelf: str) -> tuple[dict, str]:
    dump = run([readelf, "--wide", "--file-header", "--program-headers", "--dynamic", "--section-headers", str(binary)])
    needed = re.findall(r"\(NEEDED\).*?Shared library: \[([^\]]+)\]", dump)
    runpaths = re.findall(r"\(RUNPATH\).*?Library runpath: \[([^\]]*)\]", dump)
    rpaths = re.findall(r"\(RPATH\).*?Library rpath: \[([^\]]*)\]", dump)
    require(needed == NEEDED, "Box64 DT_NEEDED entries changed")
    require(len(runpaths) == 1 and not rpaths, "Expected one DT_RUNPATH and no DT_RPATH")
    return {"needed": needed, "runpath": runpaths[0]}, dump


def unpack_archive(asset: Path, directory: Path, zstd: str) -> tuple[Path, bytes]:
    archive = directory / "source.tar"
    run([zstd, "--quiet", "--decompress", "--force", str(asset), "-o", str(archive)])
    with tarfile.open(archive, "r:") as source:
        matches = [m for m in source.getmembers() if m.name.removeprefix("./") == MEMBER]
        require(len(matches) == 1 and matches[0].isfile(), "Expected one regular usr/local/bin/box64 archive member")
        stream = source.extractfile(matches[0])
        require(stream is not None, "Unable to read archived Box64")
        data = stream.read()
    return archive, data


def validate_target(data: bytes, binary: Path, readelf: str, original: dict) -> tuple[dict, str]:
    details = elf_details(data)
    require(details["interpreter"] == ROOTFS + "/lib/ld-linux-aarch64.so.1", "Incorrect relocated PT_INTERP")
    for key in ("type", "machine", "entry", "flags", "executable_sections", "tls"):
        require(details[key] == original[key], f"Relocation changed ELF {key}")
    dynamic, dump = dynamic_details(binary, readelf)
    require(dynamic["runpath"] == ROOTFS + "/lib", "Incorrect relocated DT_RUNPATH")
    details.update(dynamic)
    return details, dump


def validate_existing(asset: Path, report_path: Path, zstd: str, readelf: str) -> dict:
    try:
        report = json.loads(report_path.read_text())
    except (OSError, ValueError) as error:
        raise PreparationError(f"Prepared-asset report unavailable: {report_path}") from error
    require(report.get("schema") == 1 and report.get("rootfs") == ROOTFS, "Unexpected relocation report")
    require(report["source"]["archive_sha256"] == ARCHIVE_SHA256 and report["source"]["binary_sha256"] == BINARY_SHA256,
            "Report does not refer to the pinned original Box64")
    require(sha256(asset.read_bytes()) == report["target"]["archive_sha256"], "Prepared archive SHA-256 differs from its report")
    with tempfile.TemporaryDirectory(prefix="tsoa-box64-check-") as temporary:
        directory = Path(temporary)
        _, data = unpack_archive(asset, directory, zstd)
        require(sha256(data) == report["target"]["binary_sha256"], "Prepared Box64 SHA-256 differs from its report")
        require(len(data) == report["target"]["binary_size"], "Prepared Box64 size differs from its report")
        binary = directory / "box64"
        binary.write_bytes(data)
        details, _ = validate_target(data, binary, readelf, report["source"]["elf"])
        require(details == report["target"]["elf"], "Prepared ELF metadata differs from its report")
    return report


def prepare(asset: Path, output: Path, report_path: Path, patchelf: str, zstd: str, readelf: str, check: bool) -> dict:
    require(asset.is_file(), f"Missing Box64 asset: {asset}")
    if check:
        return validate_existing(output, report_path, zstd, readelf)
    input_sha = sha256(asset.read_bytes())
    if input_sha != ARCHIVE_SHA256:
        require(asset.resolve() == output.resolve(), "Unexpected source archive SHA-256; use the tracked original asset")
        return validate_existing(asset, report_path, zstd, readelf)
    if output.is_file() and report_path.is_file():
        try:
            return validate_existing(output, report_path, zstd, readelf)
        except (PreparationError, KeyError, TypeError):
            pass
    output.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".tsoa-box64-", dir=output.parent) as temporary:
        directory = Path(temporary)
        source_tar, original_data = unpack_archive(asset, directory, zstd)
        require(sha256(original_data) == BINARY_SHA256, "Original Box64 member SHA-256 differs from the pinned asset")
        original = elf_details(original_data)
        require(original["interpreter"] == OLD_ROOTFS + "/lib/ld-linux-aarch64.so.1", "Original Box64 interpreter changed")
        binary = directory / "box64"
        binary.write_bytes(original_data)
        original_dynamic, _ = dynamic_details(binary, readelf)
        require(original_dynamic["runpath"] == OLD_ROOTFS + "/lib", "Original Box64 RUNPATH changed")
        original.update(original_dynamic)
        run([patchelf, "--page-size", str(PAGE_SIZE), "--set-interpreter", ROOTFS + "/lib/ld-linux-aarch64.so.1",
             "--set-rpath", ROOTFS + "/lib", str(binary)])
        patched = binary.read_bytes()
        target, dump = validate_target(patched, binary, readelf, original)
        target_tar = directory / "prepared.tar"
        with tarfile.open(source_tar, "r:") as source, tarfile.open(target_tar, "w:", format=tarfile.PAX_FORMAT) as destination:
            for member in source:
                info = copy.copy(member)
                if member.name.removeprefix("./") == MEMBER:
                    info.size = len(patched)
                    destination.addfile(info, io.BytesIO(patched))
                else:
                    destination.addfile(info, source.extractfile(member) if member.isfile() else None)
        target_asset = directory / "prepared.tzst"
        run([zstd, "--quiet", "-19", "--threads=1", "--force", str(target_tar), "-o", str(target_asset)])
        report = {"schema": 1, "rootfs": ROOTFS, "member": MEMBER,
                  "patchelf": run([patchelf, "--version"]).strip(),
                  "source": {"archive_sha256": input_sha, "binary_sha256": BINARY_SHA256, "elf": original},
                  "target": {"archive_sha256": sha256(target_asset.read_bytes()), "binary_sha256": sha256(patched),
                             "binary_size": len(patched), "elf": target}}
        report_temporary = report_path.with_name(report_path.name + ".tmp")
        dump_path = report_path.with_suffix(".readelf.txt")
        report_temporary.write_text(json.dumps(report, indent=2) + "\n")
        dump_path.write_text(dump)
        target_asset.chmod(asset.stat().st_mode & 0o777)
        os.replace(target_asset, output)
        os.replace(report_temporary, report_path)
    return report


def write_metadata(report: dict, path: Path, check: bool) -> None:
    """Expose only the fields needed for verified atomic installation on Android."""
    target = report["target"]
    metadata = {"asset_path": PACKAGED_ASSET_PATH,
                "binary_sha256": target["binary_sha256"],
                "binary_size": target["binary_size"],
                "interpreter": target["elf"]["interpreter"],
                "runpath": target["elf"]["runpath"]}
    existing = None
    try:
        existing = json.loads(path.read_text())
    except (OSError, ValueError):
        pass
    if check:
        require(existing == metadata, f"Missing or mismatched generated asset metadata: {path}")
    elif existing != metadata:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + ".tmp")
        temporary.write_text(json.dumps(metadata, indent=2) + "\n")
        os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--asset", type=Path, default=ASSET, help="Pinned source .tzst (default: packaged Box64 0.4.4)")
    parser.add_argument("--output", type=Path, help="Prepared .tzst destination (default: replace --asset in build tree)")
    parser.add_argument("--report", type=Path, default=REPORT, help="SHA-256/ELF provenance report")
    parser.add_argument("--metadata", type=Path, help="Generated APK installation metadata (default: beside output .tzst)")
    parser.add_argument("--patchelf", default="patchelf")
    parser.add_argument("--zstd", default="zstd")
    parser.add_argument("--readelf", default="readelf")
    parser.add_argument("--check", action="store_true", help="Validate the prepared asset and report without modifying files")
    args = parser.parse_args()
    try:
        output = args.output or args.asset
        metadata = args.metadata or output.parent / "tsoa-relocation.json"
        generated_paths = [args.report.resolve(), args.report.with_suffix(".readelf.txt").resolve(), metadata.resolve()]
        require(len(set(generated_paths)) == len(generated_paths), "Report, readelf dump and metadata paths must differ")
        require(all(p not in (args.asset.resolve(), output.resolve()) for p in generated_paths),
                "Metadata/report paths must not overwrite a Box64 archive")
        for name in (args.zstd, args.readelf) + (() if args.check else (args.patchelf,)):
            require(shutil.which(name) is not None, f"Required build tool not found: {name}")
        report = prepare(args.asset, output, args.report, args.patchelf, args.zstd, args.readelf, args.check)
        write_metadata(report, metadata, args.check)
    except (PreparationError, OSError, ValueError, KeyError, TypeError, tarfile.TarError, struct.error) as error:
        print(f"Box64 preparation failed: {error}", file=sys.stderr)
        return 1
    print("Box64 ELF relocation verified")
    print("PT_INTERP:", report["target"]["elf"]["interpreter"])
    print("RUNPATH:", report["target"]["elf"]["runpath"])
    print("Archive SHA-256:", report["target"]["archive_sha256"])
    print("Binary SHA-256:", report["target"]["binary_sha256"])
    print("Report:", args.report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
