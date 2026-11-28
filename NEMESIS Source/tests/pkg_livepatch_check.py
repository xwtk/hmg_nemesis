"""Host check of the LivePatch packages: every section points at shipped files, no hook writes /system,
and every head-unit script parses under mksh and calls only commands the unit has (hu-shell rules).
usage: python3 tests/pkg_livepatch_check.py   (exit 1 if any package has a problem)"""
import os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKGS = ["adb-tcp", "wireless-projection", "voicehandler-vr-gates", "enhanced.projection", "carplay.session"]
# Not on the unit (.cursor/rules/hu-shell.mdc). Matched as a command word, not inside strings like "--preopt".
BANNED = "printf awk sed head tail wc od find xargs cut tr sort uniq expr basename dirname busybox toybox md5sum".split()
CMD = re.compile(r"(?:^|[;&|(]|\bthen|\bdo|\belse|\$\()\s*(" + "|".join(BANNED) + r")\b", re.M)
WRITES_SYSTEM = re.compile(r"remount\s+rw|(?:cp|mv|rm|dd)\b[^\n]*\s/system/")


def ini(path):
    """NEMESIS.data: [section] then key=value; a value opened with a quote may span lines."""
    out, sec, lines = {}, None, iter(open(path, encoding="utf-8").read().splitlines())
    for l in lines:
        if l.startswith("[") and l.rstrip().endswith("]"):
            sec = out.setdefault(l.strip()[1:-1], {})
        elif "=" in l and sec is not None and not l.lstrip().startswith(";"):
            k, v = l.split("=", 1)
            while v.startswith('"') and (len(v) == 1 or not v.rstrip().endswith('"')):
                v += "\n" + next(lines)
            sec[k.strip()] = v.strip().strip('"')
    return out


def private(path):
    """Files kept out of the published tree (.gitignore) are absent there; that is not a defect."""
    # an absent path is not known to be a directory, so "dir/" patterns need the trailing slash too
    return any(subprocess.run(["git", "-C", ROOT, "check-ignore", "-q", p], capture_output=True).returncode == 0
               for p in (path, path + "/"))


def check(name):
    d = os.path.join(ROOT, "packages", name)
    meta = ini(os.path.join(d, "NEMESIS.data"))
    bad = []
    hooks = [meta["nemesis"].get(k, "") for k in ("preinstall", "postinstall", "preuninstall", "postuninstall")]
    sections = [s for s in meta if re.match(r"(patch|file|prop)\.\d+$", s)]
    if not sections:
        bad.append("no [patch]/[file]/[prop] sections")
    for s in sections:
        sec = meta[s]
        if not s.startswith("prop"):
            t = sec.get("target", "")
            if not t.startswith("/system/"):
                bad.append(f"[{s}] target {t!r} is not under /system")
        eng = sec.get("engine", "")
        need = {"file": ["source"], "exec": ["tool"], "dex": ["pack"]}.get(
            "file" if s.startswith("file") else eng, [])
        if s.startswith("patch") and eng not in ("exec", "dex"):
            bad.append(f"[{s}] engine {eng!r}")
        if eng == "dex" and not sec.get("app"):
            bad.append(f"[{s}] engine=dex without app=")
        for k in need:
            p = os.path.join(d, sec.get(k, "?"))
            if not os.path.exists(p):
                if private(p):
                    continue
                bad.append(f"[{s}] {k}={sec.get(k)} is not in the package")
            elif k == "tool" and not os.access(p, os.X_OK):
                bad.append(f"[{s}] tool {sec.get(k)} is not executable")
        pack = os.path.join(d, sec.get("pack", "files/porter"))
        app = sec.get("app", "?")
        fp = os.path.isfile(os.path.join(pack, "fp", app + ".zip"))  # fingerprint pack (metadata 1.3)
        for rel in (["fp/.zip", "fp/.ids", "delta/.delta", "ref/.res"] if fp else ["ref/.zip", "ref/.res"]):
            sub, ext = rel.split("/.")
            ref = os.path.join(pack, sub, app + "." + ext)
            if eng == "dex" and not os.path.isfile(ref) and not private(ref):
                bad.append(f"[{s}] pack has no {sub}/{app}.{ext}")
        if "create" in sec or "prebuilt" in sec:
            bad.append(f"[{s}] create=/prebuilt= are gone (new files come from the farm, ports run on the unit)")
    mksh = shutil.which("mksh")
    for dp, _, fs in os.walk(d):
        for f in fs:
            p = os.path.join(dp, f)
            try:
                first = open(p, "rb").readline()
            except OSError:
                continue
            if not first.startswith(b"#!/system/bin/sh"):
                continue
            rel = os.path.relpath(p, d)
            text = open(p, encoding="utf-8", errors="replace").read()
            code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
            for m in CMD.finditer(code):
                bad.append(f"{rel}: calls {m.group(1)} (not on the unit)")
            if rel in hooks and WRITES_SYSTEM.search(code):
                bad.append(f"{rel}: hook writes /system (LivePatch packages must not)")
            if mksh and subprocess.run([mksh, "-n", p], capture_output=True).returncode:
                bad.append(f"{rel}: mksh -n fails")
    return bad


def selftest():
    assert CMD.search("x=$(sed s/a/b/ f)") and CMD.search("a | tail -1") and not CMD.search('dexopt --preopt "$a"')
    assert WRITES_SYSTEM.search("cp -f x /system/lib/y") and not WRITES_SYSTEM.search("cp x /data/y")


if __name__ == "__main__":
    selftest()
    fails = 0
    for n in PKGS:
        if not os.path.isdir(os.path.join(ROOT, "packages", n)):
            print("skip " + n + " (not in this tree)")
            continue
        b = check(n)
        print(("FAIL " if b else "ok   ") + n)
        for x in b:
            print("     " + x)
        fails += bool(b)
    sys.exit(1 if fails else 0)
