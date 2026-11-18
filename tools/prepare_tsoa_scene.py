#!/usr/bin/env python3
"""Prepare one private .tscene profile offline. Requires Pillow >=9.1.

Use an opaque sRGB background in the game's framing, and aligned grayscale
PNG maps of exactly the same pixel dimensions. White mask authorizes processing,
black protects (e.g. the dialogue area). White depth means near. White motion
and reflectivity mark areas suitable for those effects. The output includes a
small copy of the reference image and should be kept with the owner's game.
"""
import argparse
import hashlib
from pathlib import Path
import struct
import tempfile

from PIL import Image, ImageChops, ImageFilter


def load_image(path, *, grayscale=False, expected=None):
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError(f"{path.name}: image exceeds 64 MiB")
    with Image.open(path, formats=("PNG", "JPEG", "BMP", "WEBP")) as image:
        w, h = image.size
        if max(w, h) > 8192 or w * h > 32_000_000:
            raise ValueError(f"{path.name}: source exceeds size limit")
        if expected and image.size != expected:
            raise ValueError(f"{path.name}: expected {expected}, got {image.size}")
        if getattr(image, "n_frames", 1) != 1:
            raise ValueError(f"{path.name}: supply a single frame")
        if "A" in image.getbands() or "transparency" in image.info:
            if image.convert("RGBA").getchannel("A").getextrema()[0] != 255:
                raise ValueError(f"{path.name}: supply an opaque image")
        if grayscale:
            if image.mode not in ("1", "L"):
                raise ValueError(f"{path.name}: use an 8-bit grayscale map")
            return image.convert("L")
        return image.convert("RGB")


def prepare(reference, depth, mask, output, motion=None, reflectivity=None, size=256):
    if not 1 <= size <= 256:
        raise ValueError("--size must be between 1 and 256")
    inputs = [p for p in (reference, depth, mask, motion, reflectivity) if p is not None]
    if any(output.resolve() == p.resolve() for p in inputs):
        raise ValueError("Choose an output separate from the source images")
    image = load_image(reference)
    w, h = image.size
    largest = max(w, h)
    edge = min(size, largest)
    dims = tuple(max(1, (n * edge + largest // 2) // largest) for n in (w, h))
    ref_bytes = image.resize(dims, Image.Resampling.BOX).convert("RGBA").tobytes()
    image.close()

    def channel(path):
        return load_image(path, grayscale=True, expected=(w, h)).resize(dims, Image.Resampling.BOX)

    allowed = load_image(mask, grayscale=True, expected=(w, h))
    alpha = allowed.resize(dims, Image.Resampling.BOX)
    # A single protected source pixel must veto the entire reduced texel, even
    # at 8K -> 256. A float reduction avoids rounding tiny coverage down to zero.
    protected = allowed.point([255] + [0] * 255).convert("F").resize(dims, Image.Resampling.BOX)
    guard = Image.frombytes("L", dims, bytes(0 if amount > 0 else 255 for amount in memoryview(protected.tobytes()).cast("f")))
    alpha = ImageChops.darker(alpha, guard).filter(ImageFilter.MinFilter(3))
    zero = Image.new("L", dims)
    structure = Image.merge("RGBA", (channel(depth), channel(motion) if motion else zero,
                                     channel(reflectivity) if reflectivity else zero, alpha))
    header = struct.pack(">8sIIIHH", b"TSOSCENE", 1, w, h, *dims)
    payload = header + ref_bytes + structure.tobytes()
    target = tempfile.NamedTemporaryFile(dir=output.parent, delete=False)
    tmp = Path(target.name)
    try:
        with target:
            target.write(payload)
            target.write(hashlib.sha256(payload).digest())
        tmp.replace(output)
    finally:
        tmp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    for name, help_text in (("reference", "background or clean game capture"),
                            ("depth", "relative depth, near = white"),
                            ("mask", "area permitted to change, protected = black"),
                            ("output", "destination .tscene file")):
        parser.add_argument("--" + name, type=Path, required=True, help=help_text)
    parser.add_argument("--motion", type=Path, help="allowed motion, default zero")
    parser.add_argument("--reflectivity", type=Path, help="material response, default zero")
    parser.add_argument("--size", type=int, default=256, help="maximum map dimension, 1..256")
    try:
        prepare(**vars(parser.parse_args()))
    except (OSError, ValueError, Image.DecompressionBombError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
