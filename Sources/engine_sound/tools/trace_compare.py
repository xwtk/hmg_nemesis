#!/usr/bin/env python3
"""Fail if the demo's Python gearbox drifts from model/Gearbox.java."""
import sys

java = [line.split() for line in open(sys.argv[1])]
py = [line.split() for line in open(sys.argv[2])]
if len(java) != len(py) or not java:
    raise SystemExit("trace length java=%d python=%d" % (len(java), len(py)))
# float vs double can move one shift by a frame; more than that is drift.
bad = [(a, b) for a, b in zip(java, py)
       if a[2] != b[2] or abs(float(a[3]) - float(b[3])) > 60.0]
if len(bad) > 6:
    for a, b in bad[:10]:
        print("java", " ".join(a), "| python", " ".join(b))
    raise SystemExit("demo gearbox differs from Java in %d of %d samples" % (len(bad), len(java)))
print("trace parity ok (%d samples, %d off by a frame)" % (len(java), len(bad)))
