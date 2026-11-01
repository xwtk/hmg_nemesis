#!/usr/bin/env python3
"""Compile and run UpdateRule and StoreList without Android. Host only."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "manager" / "src" / "com" / "nemesis" / "manager"
RUN = ROOT / "tests" / "src" / "com" / "nemesis" / "manager" / "StoreLogicRun.java"


def main() -> None:
    srcs = [PKG / "UpdateRule.java", PKG / "StoreList.java", RUN]
    for s in srcs:
        if not s.is_file():
            print(f"FAIL: missing {s}", file=sys.stderr)
            sys.exit(1)
    out = Path(tempfile.mkdtemp(prefix="nemesis-store-logic-"))
    try:
        subprocess.check_call(
            ["javac", "-source", "1.7", "-target", "1.7", "-d", str(out)]
            + [str(s) for s in srcs]
        )
        subprocess.check_call(
            ["java", "-cp", str(out), "com.nemesis.manager.StoreLogicRun"]
        )
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
