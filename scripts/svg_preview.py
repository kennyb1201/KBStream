#!/usr/bin/env python3
"""Tiny SVG-path -> ASCII rasterizer (dev tool, stdlib only).

Used to eyeball an Android vector drawable's glyph at the size a chip draws it,
because nothing in this repo can render a vector drawable off-device. Pass the
path `d` data; the shape is printed as ASCII art with the nonzero fill rule, so
a logo that only "works" at 200dp is obvious at chip size.
"""
import math
import re
import sys

NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
CMDS = set("MmLlHhVvCcSsQqTtAaZz")
PARAMS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def tokenize(d):
    out, i, n = [], 0, len(d)
    while i < n:
        c = d[i]
        if c in " \t\r\n,":
            i += 1
        elif c in CMDS:
            out.append(c)
            i += 1
        else:
            m = NUM.match(d, i)
            if not m:
                raise ValueError("bad char %r at %d" % (c, i))
            out.append(float(m.group()))
            i = m.end()
    return out


def cubic(p0, p1, p2, p3, steps=12):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append(
            (
                u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
            )
        )
    return out


def quad(p0, p1, p2, steps=12):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append(
            (
                u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
            )
        )
    return out


def arc(p0, rx, ry, rot, large, sweep, p1, steps=24):
    if rx == 0 or ry == 0 or p0 == p1:
        return [p1]
    rot = math.radians(rot % 360)
    dx2, dy2 = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1p = math.cos(rot) * dx2 + math.sin(rot) * dy2
    y1p = -math.sin(rot) * dx2 + math.cos(rot) * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = math.cos(rot) * cxp - math.sin(rot) * cyp + (p0[0] + p1[0]) / 2
    cy = math.sin(rot) * cxp + math.cos(rot) * cyp + (p0[1] + p1[1]) / 2
    t0 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    t1 = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
    delta = t1 - t0
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    out = []
    for i in range(1, steps + 1):
        t = t0 + delta * i / steps
        out.append(
            (
                math.cos(rot) * rx * math.cos(t) - math.sin(rot) * ry * math.sin(t) + cx,
                math.sin(rot) * rx * math.cos(t) + math.cos(rot) * ry * math.sin(t) + cy,
            )
        )
    return out


def parse(d):
    """Path data -> list of closed polylines."""
    toks, i, subs, cur = tokenize(d), 0, [], []
    x = y = sx = sy = 0.0
    cmd = None
    ctl = None

    def flush(closed):
        if len(cur) > 2:
            subs.append(list(cur) + ([cur[0]] if closed else []))

    while i < len(toks):
        tok = toks[i]
        if isinstance(tok, str):
            cmd = tok
            i += 1
            if cmd in "Zz":
                flush(True)
                cur = []
                x, y = sx, sy
                ctl = None
                continue
        c = cmd.upper()
        rel = cmd.islower()

        def nums(k):
            nonlocal i
            vals = [float(toks[i + j]) for j in range(k)]
            i += k
            return vals

        if c == "M":
            flush(False)
            cur = []
            nx, ny = nums(2)
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            sx, sy = x, y
            cur = [(x, y)]
            cmd = "l" if rel else "L"
            ctl = None
        elif c == "L":
            nx, ny = nums(2)
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            cur.append((x, y))
            ctl = None
        elif c == "H":
            (nx,) = nums(1)
            x = x + nx if rel else nx
            cur.append((x, y))
            ctl = None
        elif c == "V":
            (ny,) = nums(1)
            y = y + ny if rel else ny
            cur.append((x, y))
            ctl = None
        elif c == "C":
            a, b, e, f, g, h = nums(6)
            p1, p2, p3 = (a, b), (e, f), (g, h)
            if rel:
                p1, p2, p3 = (x + a, y + b), (x + e, y + f), (x + g, y + h)
            cur += cubic((x, y), p1, p2, p3)
            ctl, (x, y) = p2, p3
        elif c == "S":
            e, f, g, h = nums(4)
            p1 = (2 * x - ctl[0], 2 * y - ctl[1]) if ctl else (x, y)
            p2, p3 = (e, f), (g, h)
            if rel:
                p2, p3 = (x + e, y + f), (x + g, y + h)
            cur += cubic((x, y), p1, p2, p3)
            ctl, (x, y) = p2, p3
        elif c == "Q":
            a, b, e, f = nums(4)
            p1, p2 = (a, b), (e, f)
            if rel:
                p1, p2 = (x + a, y + b), (x + e, y + f)
            cur += quad((x, y), p1, p2)
            ctl, (x, y) = p1, p2
        elif c == "T":
            e, f = nums(2)
            p1 = (2 * x - ctl[0], 2 * y - ctl[1]) if ctl else (x, y)
            p2 = (x + e, y + f) if rel else (e, f)
            cur += quad((x, y), p1, p2)
            ctl, (x, y) = p1, p2
        elif c == "A":
            a, b, r, la, sw, e, f = nums(7)
            p1 = (x + e, y + f) if rel else (e, f)
            cur += arc((x, y), a, b, r, la, sw, p1)
            x, y = p1
            ctl = None
        else:
            raise ValueError("unsupported " + cmd)
    flush(False)
    return subs


def render(subs, cols=88, rows=44, spp=3, pad=0.03):
    xs = [p[0] for s in subs for p in s]
    ys = [p[1] for s in subs for p in s]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    # A little margin: without it the outermost row/column of the glyph lands
    # exactly on the grid edge and gets clipped by the half-pixel sample.
    m = max(x1 - x0, y1 - y0) * pad
    x0, x1, y0, y1 = x0 - m, x1 + m, y0 - m, y1 + m
    side = max(x1 - x0, y1 - y0) or 1.0
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w, h = cols * spp, rows * spp
    # Fit BOTH axes: scaling by the width alone blew a square glyph up to
    # `w` tall and pushed its top and bottom bands off the grid, so every
    # icon was silently cropped to its middle third.
    scale = min(w, h) / side
    polys = [
        [((p[0] - cx) * scale + w / 2, (p[1] - cy) * scale + h / 2) for p in s]
        for s in subs
    ]
    # Edge list, bucketed per scanline so the fill stays linear-ish.
    ymin, ymax = math.floor(min(p[1] for s in polys for p in s)), math.ceil(
        max(p[1] for s in polys for p in s)
    )
    coverage = [[0] * cols for _ in range(rows)]
    for py in range(max(0, ymin), min(h, ymax + 1)):
        yc = py + 0.5
        cross = []
        for pts in polys:
            for a, b in zip(pts, pts[1:]):
                if (a[1] <= yc < b[1]) or (b[1] <= yc < a[1]):
                    t = (yc - a[1]) / (b[1] - a[1])
                    cross.append((a[0] + t * (b[0] - a[0]), 1 if b[1] > a[1] else -1))
        if not cross:
            continue
        cross.sort()
        wind, start = 0, None
        spans = []
        for xc, d in cross:
            if wind == 0:
                start = xc
            wind += d
            if wind == 0 and start is not None:
                spans.append((start, xc))
        for gx in range(cols):
            px0, px1 = gx * spp, gx * spp + spp
            filled = 0
            for sx in range(spp):
                px = px0 + sx + 0.5
                if any(a <= px < b for a, b in spans):
                    filled += 1
            if filled:
                coverage[py // spp][gx] += filled
    shades = " .:-=+*#%@"
    out = []
    for row in coverage:
        line = ""
        for v in row:
            if not v:
                line += " "
                continue
            frac = v / (spp * spp)
            idx = min(len(shades) - 1, int(frac * (len(shades) - 1) + 0.5))
            line += shades[idx]
        out.append(line)
    return out


if __name__ == "__main__":
    data = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
    rows = int(sys.argv[2]) if len(sys.argv) > 2 else 44
    for line in render(parse(data), cols=rows * 2, rows=rows):
        print(line)
