#!/usr/bin/env python3
"""Build a NEMESIS core update zip (type=update, id=nemesis.core) for `nemesis update`."""
from __future__ import annotations

import argparse
import hashlib
import io
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VER_RE = re.compile(r"^\d+(\.\d+)*$")
CA_RE = re.compile(r"^[0-9a-f]{8}\.\d$")


def native_version() -> str:
    text = (ROOT / "native" / "nemesis.h").read_text(encoding="utf-8")
    m = re.search(r'#define NEMESIS_VERSION "([^"]+)"', text)
    if not m:
        raise SystemExit("NEMESIS_VERSION not found in native/nemesis.h")
    return m.group(1)


def quote(v: str) -> str:
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", type=Path, required=True)
    ap.add_argument("--version", default=None, help="package-version (default: NEMESIS_VERSION)")
    ap.add_argument("--binary", type=Path, default=ROOT / "native" / "nemesis")
    ap.add_argument("--apk", type=Path, default=ROOT / "manager" / "NEMESIS.apk")
    ap.add_argument("--recovery", type=Path, default=ROOT / "boot" / "install-recovery.sh")
    ap.add_argument("--qb-hook", type=Path, default=ROOT / "boot" / "qb_hook.sh")
    ap.add_argument("--cacerts", type=Path, default=ROOT / "boot" / "cacerts")
    ap.add_argument("--core-package", type=Path, action="append", default=[])
    ap.add_argument("--migrate", type=Path, default=None)
    ap.add_argument("--no-apk", action="store_true")
    ap.add_argument("--os", default="any")
    ap.add_argument("--models", default="any")
    ap.add_argument("--short-desc", default=None)
    ap.add_argument("--wrap", default=None, help="put everything under this folder name")
    ap.add_argument("--corrupt", default=None, help="test only: flip a byte in this member after hashing")
    args = ap.parse_args()

    ver = args.version or native_version()
    if not VER_RE.match(ver):
        raise SystemExit(f"bad version {ver}")
    short = args.short_desc or f"NEMESIS {ver} core update"
    if len(short) > 64:
        raise SystemExit("short-desc is over 64 characters")

    files: dict[str, bytes] = {}
    elf = args.binary.read_bytes()
    if elf[:4] != b"\x7fELF":
        raise SystemExit(f"{args.binary} is not an ELF binary")
    files["core/nemesis"] = elf
    if not args.no_apk and args.apk.is_file():
        files["core/NEMESIS.apk"] = args.apk.read_bytes()
    if args.recovery.is_file():
        files["core/install-recovery.sh"] = args.recovery.read_bytes()
    if args.qb_hook.is_file():
        hook = args.qb_hook.read_bytes()
        if b"NEMESIS_QB_HOOK" not in hook:
            raise SystemExit(f"{args.qb_hook} lacks the NEMESIS_QB_HOOK marker")
        files["core/qb_hook.sh"] = hook
    if args.cacerts.is_dir():
        for p in sorted(args.cacerts.iterdir()):
            if CA_RE.match(p.name):
                files[f"core/cacerts/{p.name}"] = p.read_bytes()
    for z in args.core_package:
        files[f"core-packages/{z.name}"] = z.read_bytes()
    if args.migrate:
        files["scripts/migrate.sh"] = args.migrate.read_bytes()

    meta = (
        "[nemesis]\n"
        "version=1.1\n"
        "id=nemesis.core\n"
        "name=NEMESIS\n"
        "type=update\n"
        f"package-version={ver}\n"
        f"short-desc={quote(short)}\n"
        f"os={quote(args.os)}\n"
        f"models={quote(args.models)}\n"
    )
    files["NEMESIS.data"] = meta.encode()
    sums = "".join(f"{hashlib.md5(b).hexdigest()}  {name}\n" for name, b in sorted(files.items()))
    if args.corrupt:
        b = bytearray(files[args.corrupt])
        b[-1] ^= 0xFF
        files[args.corrupt] = bytes(b)
    files["MD5SUMS"] = sums.encode()

    prefix = f"{args.wrap.strip('/')}/" if args.wrap else ""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            z.writestr(prefix + name, files[name])
    args.output.write_bytes(buf.getvalue())
    print(f"Wrote {args.output} ({len(buf.getvalue())} bytes, version {ver}, {len(files)} files)")


if __name__ == "__main__":
    main()
