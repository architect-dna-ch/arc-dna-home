#!/usr/bin/env python3
"""
Small update patches between two APKs (used by CI for in-app updates).

An APK is a zip whose entries are compressed one by one, so everything that didn't change between
two builds (native libraries, models, most resources) sits in the new APK byte for byte as in the
old one. The patch says "copy these bytes from the APK you already have" for those and carries only
the rest (changed code, zip headers, the signature). The app rebuilds the new APK from its installed
one and checks the result against the SHA-256 in the patch before installing.

Format (gzip-compressed): b"ARCD1", new size (8 bytes, big-endian), SHA-256 of the new APK (32),
then ops: 0 = copy (old offset 8, length 4), 1 = data (length 4, bytes), 2 = end.

    apkdelta.py OLD.apk NEW.apk OUT.bin
"""
import gzip
import hashlib
import struct
import sys
import zipfile


def entries(path, blob):
    """name -> (data offset, compressed size, crc) for every zip entry."""
    out = {}
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            off = i.header_offset
            n, m = struct.unpack("<HH", blob[off + 26:off + 30])
            out[i.filename] = (off + 30 + n + m, i.compress_size, i.CRC)
    return out


def make(old_path, new_path, out_path):
    old = open(old_path, "rb").read()
    new = open(new_path, "rb").read()
    old_e, new_e = entries(old_path, old), entries(new_path, new)
    # Regions of the new APK that can be copied from the old one: unchanged entry data.
    copies = []
    for name, (start, size, crc) in new_e.items():
        o = old_e.get(name)
        if o and o[1] == size and o[2] == crc and size > 0 and old[o[0]:o[0] + size] == new[start:start + size]:
            copies.append((start, size, o[0]))
    copies.sort()
    ops, pos = [], 0
    for start, size, old_start in copies:
        if start > pos:
            ops.append(b"\x01" + struct.pack(">I", start - pos) + new[pos:start])
        ops.append(b"\x00" + struct.pack(">QI", old_start, size))
        pos = start + size
    if pos < len(new):
        ops.append(b"\x01" + struct.pack(">I", len(new) - pos) + new[pos:])
    ops.append(b"\x02")
    body = b"ARCD1" + struct.pack(">Q", len(new)) + hashlib.sha256(new).digest() + b"".join(ops)
    with gzip.open(out_path, "wb", compresslevel=9) as f:
        f.write(body)
    copied = sum(c[1] for c in copies)
    print(f"{out_path}: new {len(new)} bytes, {copied} copied from old, patch carries {len(new) - copied}")


def apply(old_path, patch_path, out_path):
    """Same steps as the app (for testing)."""
    old = open(old_path, "rb").read()
    data = gzip.open(patch_path, "rb").read()
    assert data[:5] == b"ARCD1"
    size, digest, i, out = struct.unpack(">Q", data[5:13])[0], data[13:45], 45, bytearray()
    while True:
        op = data[i]
        i += 1
        if op == 0:
            off, n = struct.unpack(">QI", data[i:i + 12])
            i += 12
            out += old[off:off + n]
        elif op == 1:
            n = struct.unpack(">I", data[i:i + 4])[0]
            i += 4
            out += data[i:i + n]
            i += n
        else:
            break
    assert len(out) == size and hashlib.sha256(out).digest() == digest, "patch result does not match"
    open(out_path, "wb").write(out)


if __name__ == "__main__":
    if sys.argv[1] == "--apply":
        apply(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        make(sys.argv[1], sys.argv[2], sys.argv[3])
