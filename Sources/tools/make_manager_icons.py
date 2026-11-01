#!/usr/bin/env python3
"""Draw the Manager's home-screen icons into manager/res/drawable. Plain PNGs:
this firmware's inflater fails on 9-patches. Output is committed; PIL is only
needed to regenerate it."""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "manager" / "res" / "drawable"
SIZE = 128
S = 4
FG = (182, 193, 200, 255)
TILE = (42, 50, 54, 255)


def box(x0, y0, x1, y1):
    return [x0 * S, y0 * S, x1 * S, y1 * S]


def line(d, pts, width):
    d.line([(x * S, y * S) for x, y in pts], fill=FG, width=width * S, joint="curve")


def store(d):
    d.rectangle(box(36, 54, 92, 94), outline=FG, width=5 * S)
    line(d, [(30, 54), (40, 34), (88, 34), (98, 54), (30, 54)], 5)
    line(d, [(64, 62), (64, 84)], 5)
    line(d, [(54, 74), (64, 84), (74, 74)], 5)


def apps(d):
    for x in (36, 58, 80):
        for y in (36, 58, 80):
            d.rounded_rectangle(box(x - 7, y - 7, x + 7, y + 7), radius=3 * S, fill=FG)


def settings(d):
    import math

    for i in range(8):
        a = i * math.pi / 4
        line(d, [(64 + 20 * math.cos(a), 64 + 20 * math.sin(a)),
                 (64 + 34 * math.cos(a), 64 + 34 * math.sin(a))], 10)
    d.ellipse(box(38, 38, 90, 90), outline=FG, width=11 * S)


def services(d):
    for y in (32, 56, 80):
        d.rounded_rectangle(box(30, y, 98, y + 18), radius=3 * S, outline=FG, width=4 * S)
        d.ellipse(box(38, y + 6, 44, y + 12), fill=FG)
        line(d, [(54, y + 9), (88, y + 9)], 3)


def logs(d):
    d.rounded_rectangle(box(36, 26, 92, 102), radius=5 * S, outline=FG, width=5 * S)
    for y in (44, 58, 72, 86):
        line(d, [(47, y), (81, y)], 4)


def engineering(d):
    line(d, [(38, 90), (70, 58)], 12)
    d.arc(box(58, 26, 102, 70), start=20, end=250, fill=FG, width=9 * S)


def dealer(d):
    line(d, [(32, 72), (40, 50), (88, 50), (96, 72)], 6)
    d.rounded_rectangle(box(26, 70, 102, 90), radius=5 * S, outline=FG, width=6 * S)
    d.ellipse(box(38, 84, 54, 100), fill=FG)
    d.ellipse(box(74, 84, 90, 100), fill=FG)


def check(d):
    line(d, [(26, 66), (54, 94), (102, 36)], 14)


def render(name, draw, tile=True):
    im = Image.new("RGBA", (SIZE * S, SIZE * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if tile:
        d.rounded_rectangle(box(6, 6, 122, 122), radius=22 * S, fill=TILE)
    draw(d)
    im.resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"{name}.png", optimize=True)


def main() -> None:
    render("ic_home_store", store)
    render("ic_home_apps", apps)
    render("ic_home_settings", settings)
    render("ic_home_services", services)
    render("ic_home_logs", logs)
    render("ic_home_engineering", engineering)
    render("ic_home_dealer", dealer)
    render("ic_check", check, tile=False)


if __name__ == "__main__":
    main()
