#!/usr/bin/env python3
"""Build a NEMESIS install zip from a package directory containing NEMESIS.data."""
from __future__ import annotations

import argparse
import re
import zipfile
from pathlib import Path

SHORT_MAX = 64
DETAIL_MAX = 256
VER_RE = re.compile(r"^\d+(\.\d+)*$")
ID_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")


def parse_ini(text: str) -> dict[tuple[str, str], str]:
    """Same rules as native/ini.c: a value starting with '"' may span lines,
    with \\" and \\\\ escapes; CRLF and lone CR become LF."""
    out: dict[tuple[str, str], str] = {}
    sec = ""
    i, n, line = 0, len(text), 1
    while i < n:
        while i < n and text[i] in " \t":
            i += 1
        if i >= n:
            break
        if text[i] in "\r\n":
            if text[i] == "\r" and i + 1 < n and text[i + 1] == "\n":
                i += 1
            i += 1
            line += 1
            continue
        j = i
        while j < n and text[j] not in "\r\n":
            j += 1
        if text[i] in "#;[":
            if text[i] == "[" and "]" in text[i:j]:
                sec = text[i + 1:text.index("]", i)].strip()
            i = j
            continue
        eq = text.find("=", i, j)
        if eq < 0:
            i = j
            continue
        key = text[i:eq].strip()
        k = eq + 1
        while k < n and text[k] in " \t":
            k += 1
        start = line
        if k < n and text[k] == '"':
            k += 1
            val = []
            while True:
                if k >= n:
                    raise ValueError(f"line {start}: unterminated quoted value")
                c = text[k]
                k += 1
                if c == '"':
                    break
                if c == "\\" and k < n and text[k] in '"\\':
                    c = text[k]
                    k += 1
                elif c == "\r":
                    if k < n and text[k] == "\n":
                        k += 1
                    c = "\n"
                    line += 1
                elif c == "\n":
                    line += 1
                val.append(c)
            value = "".join(val)
            while k < n and text[k] not in "\r\n":
                k += 1
            i = k
        else:
            value = text[k:j].strip()
            i = j
        if len(value.encode()) >= 1024:
            raise ValueError(f"line {start}: value too long")
        if key:
            out.setdefault((sec, key), value)
    return out


def spec_ok(spec: str) -> bool:
    if spec == "any":
        return True
    if spec[:2] in (">=", "<="):
        return bool(VER_RE.match(spec[2:]))
    if "-" in spec:
        lo, _, hi = spec.partition("-")
        return bool(VER_RE.match(lo) and VER_RE.match(hi))
    return bool(VER_RE.match(spec))


def validate(meta_path: Path) -> list[str]:
    """Returns warnings; raises SystemExit on errors the head unit would reject."""
    warnings: list[str] = []
    try:
        kv = parse_ini(meta_path.read_bytes().decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as e:
        raise SystemExit(f"{meta_path}: {e}")

    def get(key: str) -> str | None:
        return kv.get(("nemesis", key))

    errors = []
    ver = get("version") or "1.0"
    if not VER_RE.match(ver):
        errors.append(f"bad version={ver}")
    pid = get("id") or ""
    if not ID_RE.match(pid) or ".." in pid:
        errors.append(f"bad or missing id={pid!r}")
    v11 = VER_RE.match(ver) and tuple(map(int, ver.split("."))) >= (1, 1)
    if v11:
        if get("types") is not None and get("type") is None:
            warnings.append("version=1.1 uses type=, not types=")
        if not get("type") and not get("types"):
            warnings.append("no type= given")
        for key in ("package-version", "short-desc"):
            if not get(key):
                warnings.append(f"{key}= is not set")
    elif any(get(k) is not None for k in ("type", "package-version", "short-desc", "depends", "os", "models")):
        warnings.append("1.1 keys present but version is not 1.1")
    pv = get("package-version")
    if pv and not VER_RE.match(pv):
        errors.append(f"package-version must be dotted numbers: {pv}")
    for key, limit in (("short-desc", SHORT_MAX), ("detailed-desc", DETAIL_MAX)):
        val = get(key)
        if val is not None and len(val) > limit:
            errors.append(f"{key} is {len(val)} characters (max {limit})")
    for item in filter(None, (s.strip() for s in (get("depends") or "").split(","))):
        dep, _, spec = item.partition("@")
        dep, spec = dep.strip(), spec.strip() or "any"
        if not dep or not spec_ok(spec):
            errors.append(f"bad depends entry: {item}")
        elif dep == pid:
            errors.append("package depends on itself")
    if errors:
        raise SystemExit(f"{meta_path}: " + "; ".join(errors))
    return warnings


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("pkg_dir", type=Path)
    ap.add_argument("-o", "--output", type=Path, default=None)
    args = ap.parse_args()
    src = args.pkg_dir.resolve()
    meta = src / "NEMESIS.data"
    if not meta.is_file():
        raise SystemExit(f"missing {meta}")
    for w in validate(meta):
        print(f"warning: {meta}: {w}")
    out = args.output or (src.parent / f"{src.name}.zip")
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(src.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(src).as_posix())
    print(f"Wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
