"""Shared helpers used by both entrypoint build scripts (troubleShooter and USB package)."""

from __future__ import annotations

import re
from pathlib import Path

CA_RE = re.compile(r"^[0-9a-f]{8}\.\d$")


def cacerts_heredocs(cacerts_dir: Path) -> str:
    """One heredoc per bundled root. PEM text has no 'CA_EOF' line."""
    out = []
    for p in sorted(cacerts_dir.iterdir()) if cacerts_dir.is_dir() else []:
        if not CA_RE.match(p.name):
            continue
        text = p.read_text(encoding="ascii")
        if "CA_EOF" in text:
            raise SystemExit(f"{p} contains the heredoc marker")
        if not text.endswith("\n"):
            text += "\n"
        out.append(f"cat > /data/nemesis/cacerts-new/{p.name} << 'CA_EOF'\n{text}CA_EOF\n")
    if not out:
        raise SystemExit(f"no root certificates in {cacerts_dir}; run tools/make_cacerts.py")
    return "".join(out)
