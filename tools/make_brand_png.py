"""Small PNG writer for the Play listing images. No third-party image library."""

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(path: Path, width: int, height: int, paint) -> None:
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(paint(x, y, width, height))
    payload = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def brand(x, y, width, height):
    ink = (16, 22, 19)
    brass = (224, 181, 106)
    # Shield centered.
    cx, cy = width / 2, height / 2
    nx = (x - cx) / (width * 0.18)
    ny = (y - cy) / (height * 0.28)
    inside = abs(nx) < 1 and -0.9 < ny < 1.05 and abs(nx) < 1.15 - max(0, ny) * 0.35
    if inside and abs(nx) < 0.18 and 0.15 < ny < 0.7:
        return ink
    if inside and nx * nx + (ny + 0.15) ** 2 < 0.08:
        return ink
    return brass if inside else ink


def feature(x, y, width, height):
    base = brand(x, y, width, height)
    # Leave the right side as a quiet panel so the console can crop text later.
    if x > width * 0.62:
        return (24, 33, 28)
    return base


if __name__ == "__main__":
    write_png(ROOT / "playstore" / "icon-512.png", 512, 512, brand)
    write_png(ROOT / "playstore" / "feature-1024x500.png", 1024, 500, feature)
    print("wrote icons")
