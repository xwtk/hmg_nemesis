#!/usr/bin/env python3
"""Compile and run SetupPress without Android. Host only."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "manager" / "src" / "com" / "nemesis" / "manager" / "SetupPress.java"
RUN = ROOT / "tests" / "src" / "com" / "nemesis" / "manager" / "SetupPressRun.java"


def main() -> None:
    if not SRC.is_file() or not RUN.is_file():
        print("FAIL: missing SetupPress sources", file=sys.stderr)
        sys.exit(1)
    out = Path(tempfile.mkdtemp(prefix="nemesis-setup-press-"))
    try:
        subprocess.check_call(
            [
                "javac",
                "-source",
                "1.7",
                "-target",
                "1.7",
                "-d",
                str(out),
                str(SRC),
                str(RUN),
            ]
        )
        subprocess.check_call(
            ["java", "-cp", str(out), "com.nemesis.manager.SetupPressRun"]
        )
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
