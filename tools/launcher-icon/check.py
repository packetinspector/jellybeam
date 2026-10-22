#!/usr/bin/env python3
"""Launcher icon check (docs/brand.md §2): the adaptive icon must tint on Plume and theme cleanly.

Rasterises the shipped VectorDrawable layers (stdlib only) and replays the Plume launcher's
glyph heuristic: the foreground drawn at 96dp with layer bounds -s/4..5s/4 must be <=70%
opaque, and the full icon at 32x32 must put >51 of 1024 pixels (alpha>200, S>0.35, V>0.3)
in one 30-degree hue bucket or the tile stays grey. We assert double that margin.

Usage: check.py [--preview out.png]   (exit 1 with reasons on failure)
"""
import colorsys
import math
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
A = "{http://schemas.android.com/apk/res/android}"
SS = 4  # supersamples per pixel axis
VIEWPORT = 108.0
SAFE_RADIUS = 33.0  # 66dp safe zone
MIN_BUCKET = 103  # Plume needs >51; we keep 2x headroom
MAX_OPAQUE = 0.70
MIN_FACE_HOLES = 3  # two eyes and the mouth read as see-through in the glyph


def parse_path(d):
    """Subpaths as point lists from absolute/relative M L H V C Q A Z; curves flattened."""
    toks = re.findall(r"[A-DF-Za-df-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)
    subs, cur, i, cmd = [], [], 0, None
    x = y = sx = sy = 0.0

    def num():
        nonlocal i
        i += 1
        return float(toks[i - 1])

    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
        rel = cmd.islower()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        c = cmd.upper()
        if c == "Z":
            if cur:
                subs.append(cur)
            cur, x, y = [], sx, sy
            continue
        if c == "M":
            if cur:
                subs.append(cur)
            x, y = ox + num(), oy + num()
            sx, sy, cur = x, y, [(x, y)]
            cmd = "l" if rel else "L"
        elif c == "L":
            x, y = ox + num(), oy + num()
            cur.append((x, y))
        elif c == "H":
            x = ox + num()
            cur.append((x, y))
        elif c == "V":
            y = oy + num()
            cur.append((x, y))
        elif c in "CQ":
            pts = [(x, y)] + [(ox + num(), oy + num()) for _ in range(3 if c == "C" else 2)]
            for k in range(1, 17):
                t = k / 16
                if c == "C":
                    w = ((1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3)
                else:
                    w = ((1 - t) ** 2, 2 * (1 - t) * t, t * t)
                cur.append((sum(a * p[0] for a, p in zip(w, pts)), sum(a * p[1] for a, p in zip(w, pts))))
            x, y = pts[-1]
        elif c == "A":
            rx, ry, rot, large, sweep = abs(num()), abs(num()), num(), num(), num()
            ex, ey = ox + num(), oy + num()
            cur += arc_points(x, y, rx, ry, rot, large != 0, sweep != 0, ex, ey)
            x, y = ex, ey
        else:
            raise ValueError(f"unsupported path command {cmd}")
    if cur:
        subs.append(cur)
    return subs


def arc_points(x0, y0, rx, ry, rot, large, sweep, x1, y1):
    """SVG elliptical arc (endpoint form, SVG 1.1 F.6.5) flattened to 32 points."""
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    px, py = c * dx + s * dy, -s * dx + c * dy
    lam = (px / rx) ** 2 + (py / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num_ = rx * rx * ry * ry - rx * rx * py * py - ry * ry * px * px
    k = math.sqrt(max(0.0, num_ / (rx * rx * py * py + ry * ry * px * px)))
    if large == sweep:
        k = -k
    cxp, cyp = k * rx * py / ry, -k * ry * px / rx
    cx, cy = c * cxp - s * cyp + (x0 + x1) / 2, s * cxp + c * cyp + (y0 + y1) / 2
    ang = lambda ux, uy: math.atan2(uy, ux)
    t0 = ang((px - cxp) / rx, (py - cyp) / ry)
    dt = ang((-px - cxp) / rx, (-py - cyp) / ry) - t0
    if sweep and dt < 0:
        dt += 2 * math.pi
    elif not sweep and dt > 0:
        dt -= 2 * math.pi
    out = []
    for k in range(1, 33):
        t = t0 + dt * k / 32
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        out.append((cx + c * ex - s * ey, cy + s * ex + c * ey))
    return out


def coverage(subs, size, scale, offset, even_odd):
    """Per-pixel coverage 0..1 of the filled subpaths, nonzero or even-odd, supersampled."""
    edges = []
    for s in subs:
        for (x0, y0), (x1, y1) in zip(s, s[1:] + s[:1]):
            p0 = (x0 * scale + offset, y0 * scale + offset)
            p1 = (x1 * scale + offset, y1 * scale + offset)
            if p0[1] != p1[1]:
                edges.append((p0, p1))
    cov = [0.0] * (size * size)
    n = size * SS
    for row in range(n):
        yc = (row + 0.5) / SS
        xs = []
        for (ax, ay), (bx, by) in edges:
            if (ay <= yc < by) or (by <= yc < ay):
                xs.append((ax + (yc - ay) * (bx - ax) / (by - ay), 1 if by > ay else -1))
        xs.sort()
        wind, base = 0, (row // SS) * size
        for k, (xa, dw) in enumerate(xs[:-1]):
            wind += dw
            if (wind % 2) if even_odd else wind:
                c0 = max(0, math.ceil(xa * SS - 0.5))
                c1 = min(n, math.ceil(xs[k + 1][0] * SS - 0.5))
                for col in range(c0, c1):
                    cov[base + col // SS] += 1.0 / (SS * SS)
    return cov


def color(v):
    v = v.lstrip("#")
    if len(v) == 6:
        v = "FF" + v
    return tuple(int(v[k:k + 2], 16) for k in (2, 4, 6, 0))


def resolve(ref, kind):
    name = ref.split("/", 1)[1]
    if kind == "color":
        for f in (RES / "values").glob("*.xml"):
            for c in ET.parse(f).getroot().iter("color"):
                if c.get("name") == name:
                    return color(c.text.strip())
        raise ValueError(f"no color {ref}")
    found = sorted(RES.glob(f"drawable*/{name}.xml"))
    return found[0] if found else RES / "drawable" / f"{name}.xml"


def render_vector(path, size, scale, offset, bg=None):
    """RGBA rows (floats 0..255) of a VectorDrawable, src-over onto bg (default transparent)."""
    root = ET.parse(path).getroot()
    if root.tag != "vector" or float(root.get(A + "viewportWidth")) != VIEWPORT:
        raise ValueError(f"{path.name}: expected a {VIEWPORT:g}-unit <vector>")
    if any(e.tag == "group" for e in root.iter()):
        raise ValueError(f"{path.name}: <group> transforms are not modelled")
    px = [list(bg) if bg else [0.0, 0.0, 0.0, 0.0] for _ in range(size * size)]
    for p in root.iter("path"):
        r, g, b, a = color(p.get(A + "fillColor"))
        a *= float(p.get(A + "fillAlpha", "1"))
        cov = coverage(parse_path(p.get(A + "pathData")), size, scale, offset,
                       p.get(A + "fillType") == "evenOdd")
        for k, c in enumerate(cov):
            if c:
                sa = a * c / 255
                q = px[k]
                da = q[3] / 255
                oa = sa + da * (1 - sa)
                for ch, sv in enumerate((r, g, b)):
                    q[ch] = (sv * sa + q[ch] * da * (1 - sa)) / oa
                q[3] = oa * 255
    return px


def layer(path, size, bg=None):
    """A layer as an AdaptiveIconDrawable draws it: 1.5x the icon, offset by -size/4."""
    return render_vector(path, size, size * 1.5 / VIEWPORT, -size / 4, bg)


def opaque_fraction(px):
    return sum(1 for p in px if p[3] > 200) / len(px)


def glyph_alpha(px):
    """Plume's glyph: alpha = luminance x alpha, normalised to the max."""
    raw = [(0.2126 * r + 0.7152 * g + 0.0722 * b) * a / 255 for r, g, b, a in px]
    m = max(raw) or 1
    return [v / m * 255 for v in raw]


def hue_bucket(px):
    """(winning bucket size, its average RGB) over pixels Plume counts as colour."""
    buckets = {}
    for r, g, b, a in px:
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if a > 200 and s > 0.35 and v > 0.3:
            buckets.setdefault(int(h * 360) // 30 % 12, []).append((r, g, b))
    if not buckets:
        return 0, None
    win = max(buckets.values(), key=len)
    return len(win), tuple(round(sum(c[i] for c in win) / len(win)) for i in range(3))


def enclosed_holes(alpha, size, cut=40):
    """Transparent regions (alpha<=cut) not connected to the image border."""
    seen = [False] * len(alpha)
    holes = 0
    for start in range(len(alpha)):
        if seen[start] or alpha[start] > cut:
            continue
        stack, edge, n = [start], False, 0
        seen[start] = True
        while stack:
            k = stack.pop()
            n += 1
            x, y = k % size, k // size
            edge |= x in (0, size - 1) or y in (0, size - 1)
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                j = ny * size + nx
                if 0 <= nx < size and 0 <= ny < size and not seen[j] and alpha[j] <= cut:
                    seen[j] = True
                    stack.append(j)
        holes += not edge and n >= 4
    return holes


def outside_safe_zone(path, size=216):
    """Pixels past the 66dp circle plus 1dp of slack; the launcher's own circle mask is at 72dp."""
    s = size / VIEWPORT
    px = render_vector(path, size, s, 0)
    c, r = size / 2, SAFE_RADIUS * s
    return sum(1 for k, p in enumerate(px)
               if p[3] > 0 and math.hypot(k % size + 0.5 - c, k // size + 0.5 - c) > r + s)


def write_png(path, rows, w, h):
    raw = b"".join(b"\0" + bytes(int(min(255, max(0, v)) + 0.5) for p in rows[y * w:(y + 1) * w] for v in p)
                   for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    Path(path).write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main(argv):
    fails = []
    for icon in ("ic_launcher", "ic_launcher_round"):
        root = ET.parse(RES / "mipmap-anydpi-v26" / f"{icon}.xml").getroot()
        refs = {e.tag: e.get(A + "drawable") for e in root}
        fg = resolve(refs["foreground"], "drawable")
        mono = resolve(refs.get("monochrome", ""), "drawable") if refs.get("monochrome") else None
        bg = resolve(refs["background"], "color")
        if not fg.exists() or mono is None or not mono.exists():
            fails.append(f"{icon}: foreground and monochrome must be VectorDrawables in drawable*/")
            continue

        crop = layer(fg, 192)
        opaque = opaque_fraction(crop)
        if opaque > MAX_OPAQUE:
            fails.append(f"{icon}: foreground {opaque:.0%} opaque; Plume skips the glyph above 70%")
        glyph = glyph_alpha(crop)
        holes = enclosed_holes(glyph, 192)
        if holes < MIN_FACE_HOLES:
            fails.append(f"{icon}: glyph has {holes} see-through details, want >= {MIN_FACE_HOLES}")
        n, tint = hue_bucket(layer(fg, 32, [float(v) for v in bg]))
        if n < MIN_BUCKET:
            fails.append(f"{icon}: largest hue bucket {n}/1024, want >= {MIN_BUCKET}")
        if mono != fg:
            ma, fa = layer(mono, 96), layer(fg, 96)
            if any(m[3] > 8 and f[3] < m[3] - 8 for m, f in zip(ma, fa)):
                fails.append(f"{icon}: monochrome covers pixels the foreground does not")
        for p in {fg, mono}:
            out = outside_safe_zone(p)
            if out:
                fails.append(f"{icon}: {p.name} has {out} pixels outside the 66dp safe zone")
        print(f"{icon}: bucket {n}/1024 tint #{''.join(f'{v:02X}' for v in tint or (0, 0, 0))} "
              f"opaque {opaque:.0%} holes {holes}")

        if "--preview" in argv and icon == "ic_launcher":
            ink = tint if n > 51 else (176, 176, 176)
            tile = [[42.0, 42.0, 42.0, 255.0] for _ in range(192 * 192)]
            for k, a in enumerate(glyph):
                if a > 40:
                    f = a / 255
                    tile[k] = [ink[0] * f + 42 * (1 - f), ink[1] * f + 42 * (1 - f), ink[2] * f + 42 * (1 - f), 255.0]
            write_png(argv[argv.index("--preview") + 1], tile, 192, 192)

    for f in fails:
        print("FAIL", f)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
