#!/usr/bin/env python3
"""Render a short wordmark from a bundled TTF as a vector-drawable path.

Dev tool behind `res/drawable/ic_rating_tmdb.xml`: the rating chips draw small
brand marks, and hand-drawing a wordmark by eye produced unreadable shapes
twice, so the letters come from the app's own display font instead. Contour
winding is preserved from the font, which is what the vector drawable's default
nonzero fill expects, so it renders exactly as the typeface designs it.

Usage: font_glyphs.py <font.ttf> <TEXT> [box] [pad] [tracking]
"""
import struct
import sys


class Font:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        num = struct.unpack(">H", self.data[4:6])[0]
        self.tables = {}
        for i in range(num):
            off = 12 + i * 16
            tag = self.data[off:off + 4].decode("latin1")
            toff, _ = struct.unpack(">II", self.data[off + 8:off + 16])
            self.tables[tag] = toff
        head = self.tables["head"]
        self.upem = struct.unpack(">H", self.data[head + 18:head + 20])[0]
        self.loc_format = struct.unpack(">h", self.data[head + 50:head + 52])[0]
        self.num_glyphs = struct.unpack(">H", self.data[self.tables["maxp"] + 4:self.tables["maxp"] + 6])[0]
        self.num_hmetrics = struct.unpack(">H", self.data[self.tables["hhea"] + 34:self.tables["hhea"] + 36])[0]
        self._cmap()
        self._loca()

    def _cmap(self):
        base = self.tables["cmap"]
        n = struct.unpack(">H", self.data[base + 2:base + 4])[0]
        sub = None
        for i in range(n):
            rec = base + 4 + i * 8
            pid, eid, off = struct.unpack(">HHI", self.data[rec:rec + 8])
            if (pid, eid) in ((3, 1), (0, 3), (0, 4)):
                sub = base + off
        if sub is None:
            raise ValueError("no usable cmap subtable")
        if struct.unpack(">H", self.data[sub:sub + 2])[0] != 4:
            raise ValueError("only cmap format 4 is supported")
        seg_x2 = struct.unpack(">H", self.data[sub + 6:sub + 8])[0]
        seg = seg_x2 // 2
        self.ends = struct.unpack(">%dH" % seg, self.data[sub + 14:sub + 14 + seg_x2])
        self.starts = struct.unpack(">%dH" % seg, self.data[sub + 16 + seg_x2:sub + 16 + 2 * seg_x2])
        self.deltas = struct.unpack(">%dh" % seg, self.data[sub + 16 + 2 * seg_x2:sub + 16 + 3 * seg_x2])
        self.range_off = sub + 16 + 3 * seg_x2
        self.ranges = struct.unpack(">%dH" % seg, self.data[self.range_off:self.range_off + seg_x2])

    def gid(self, ch):
        code = ord(ch)
        for i in range(len(self.ends)):
            if self.starts[i] <= code <= self.ends[i]:
                if self.ranges[i] == 0:
                    return (code + self.deltas[i]) & 0xFFFF
                addr = self.range_off + i * 2 + self.ranges[i] + (code - self.starts[i]) * 2
                g = struct.unpack(">H", self.data[addr:addr + 2])[0]
                return (g + self.deltas[i]) & 0xFFFF if g else 0
        return 0

    def advance(self, gid):
        off = self.tables["hmtx"] + min(gid, self.num_hmetrics - 1) * 4
        return struct.unpack(">H", self.data[off:off + 2])[0]

    def _loca(self):
        base = self.tables["loca"]
        count = self.num_glyphs + 1
        if self.loc_format == 0:
            vals = struct.unpack(">%dH" % count, self.data[base:base + count * 2])
            self.loca = [v * 2 for v in vals]
        else:
            self.loca = list(struct.unpack(">%dI" % count, self.data[base:base + count * 4]))

    def contours(self, gid):
        """Glyph outlines as contours of (x, y, on_curve) in font units."""
        start = self.tables["glyf"] + self.loca[gid]
        end = self.tables["glyf"] + self.loca[gid + 1]
        if end <= start:
            return []
        raw = self.data[start:end]
        ncont = struct.unpack(">h", raw[0:2])[0]
        if ncont < 0:
            raise ValueError("composite glyph %d is not supported" % gid)
        if ncont == 0:
            return []
        ends = struct.unpack(">%dH" % ncont, raw[10:10 + ncont * 2])
        npts = ends[-1] + 1
        p = 10 + ncont * 2
        p += 2 + struct.unpack(">H", raw[p:p + 2])[0]  # skip hinting
        flags = []
        while len(flags) < npts:
            f = raw[p]
            p += 1
            flags.append(f)
            if f & 8:
                flags.extend([f] * raw[p])
                p += 1
        xs, x = [], 0
        for f in flags:
            if f & 2:
                d = raw[p]
                p += 1
                x += d if f & 16 else -d
            elif not f & 16:
                x += struct.unpack(">h", raw[p:p + 2])[0]
                p += 2
            xs.append(x)
        ys, y = [], 0
        for f in flags:
            if f & 4:
                d = raw[p]
                p += 1
                y += d if f & 32 else -d
            elif not f & 32:
                y += struct.unpack(">h", raw[p:p + 2])[0]
                p += 2
            ys.append(y)
        pts = [(xs[i], ys[i], bool(flags[i] & 1)) for i in range(npts)]
        out, first = [], 0
        for e in ends:
            out.append(pts[first:e + 1])
            first = e + 1
        return out


def contour_cmds(contour, tx, ty, s):
    """One contour as SVG path commands, in drawable units."""
    pts = list(contour)
    if not pts[0][2]:
        if not pts[-1][2]:
            pts = [((pts[0][0] + pts[-1][0]) / 2, (pts[0][1] + pts[-1][1]) / 2, True)] + pts
        else:
            pts = [pts[-1]] + pts[:-1]

    def P(p):
        return (p[0] * s + tx, ty - p[1] * s)

    x, y = P(pts[0])
    cmds = ["M%.2f %.2f" % (x, y)]
    i, n = 1, len(pts)
    while i <= n:
        p = pts[i % n]
        if p[2]:
            x, y = P(p)
            cmds.append("L%.2f %.2f" % (x, y))
            i += 1
        else:
            q = pts[(i + 1) % n]
            cx, cy = P(p)
            x, y = P(q)
            cmds.append("Q%.2f %.2f %.2f %.2f" % (cx, cy, x, y))
            i += 2
    cmds.append("Z")
    return "".join(cmds)


def build(font, text, box=24.0, pad=1.5, tracking=0.04):
    glyphs = []
    x = 0.0
    for ch in text:
        gid = font.gid(ch)
        gly = font.contours(gid)
        glyphs.append((gly, x))
        x += font.advance(gid) + font.upem * tracking
    width = x - font.upem * tracking
    y0 = min(p[1] for g, _ in glyphs for c in g for p in c)
    y1 = max(p[1] for g, _ in glyphs for c in g for p in c)
    inner = box - 2 * pad
    s = min(inner / width, inner / (y1 - y0))
    tx = pad + (inner - width * s) / 2
    ty = pad + (inner - (y1 - y0) * s) / 2 + y1 * s
    return "".join(
        contour_cmds(c, ox * s + tx, ty, s) for gly, ox in glyphs for c in gly
    )


if __name__ == "__main__":
    font = Font(sys.argv[1])
    text = sys.argv[2]
    box = float(sys.argv[3]) if len(sys.argv) > 3 else 24.0
    pad = float(sys.argv[4]) if len(sys.argv) > 4 else 1.5
    tracking = float(sys.argv[5]) if len(sys.argv) > 5 else 0.04
    print(build(font, text, box, pad, tracking))
