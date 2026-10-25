#!/usr/bin/env python3
"""Frame-counter readout for tools/sp-loop.sh.

The test clips burn a timecode box into the top-left corner: "HH:MM:SS.mmm" over the frame
number. This reads both by nearest-template matching on monospace cells (digits.json, built
with `calibrate` from labelled captures) and only accepts a read where the two agree.

  frames.py calibrate OUT.json PNG=HHMMSSmmm:FRAME ...
  frames.py ocr PNG                      -> "<frame> <timecode_ms>" or "none"
  frames.py pair S1 S2 OUTDIR TAG [N]    -> N paired captures; prints per-pair and median drift
  frames.py still S1 S2 OUTDIR TAG       -> two pairs 1.5 s apart; reports whether each side moved
"""
import concurrent.futures
import json
import os
import re
import statistics
import subprocess
import sys
import time

from PIL import Image

FPS = 24.0
# The burned-in box sits at about x 0-260, y 0-80 of a 1920x1080 capture; scaled for other sizes.
BOX = (0, 0, 270, 84)
# Glyph cells are compared at this resolution.
CW, CH = 8, 12


def emulator_only(serial):
    if not re.fullmatch(r"emulator-\d+", serial):
        sys.exit(f"refusing: {serial} is not an emulator serial")


def dark_box(region):
    """The burned-in box is near-black behind the digits: the columns and rows that are mostly
    dark, so the clip's coloured background never reaches the matcher."""
    w, h = region.size
    px = region.load()
    dark = [[max(px[x, y]) < 90 for x in range(w)] for y in range(h)]
    cols = [x for x in range(w) if sum(dark[y][x] for y in range(h)) > h * 0.3]
    rows = [y for y in range(h) if sum(dark[y][x] for x in range(w)) > w * 0.3]
    if not cols or not rows:
        return None
    return (cols[0], rows[0], cols[-1] + 1, rows[-1] + 1)


def ink_grid(path):
    """Ink (0..1, from the red channel: orange digits on a dark box) of the timecode box, or None."""
    img = Image.open(path).convert("RGB")
    sx, sy = img.width / 1920.0, img.height / 1080.0
    region = img.crop((int(BOX[0] * sx), int(BOX[1] * sy), int(BOX[2] * sx), int(BOX[3] * sy)))
    b = dark_box(region)
    if not b or b[2] - b[0] < 60 or b[3] - b[1] < 30:
        return None
    box = region.crop(b)
    w, h = box.size
    px = box.load()
    return [[min(1.0, max(0.0, (px[x, y][0] - 70) / 130.0)) for x in range(w)] for y in range(h)]


def bands(ink):
    """Row bands that hold ink: the timecode line and the frame-number line."""
    rows = [sum(r) > 0.8 for r in ink]
    out, start = [], None
    for y, on in enumerate(rows + [False]):
        if on and start is None:
            start = y
        elif not on and start is not None:
            if y - start >= 5:
                out.append((start, y))
            start = None
    return out


def cell(ink, y0, y1, x0, x1):
    """A glyph cell resampled to CW x CH by area averaging."""
    out = []
    for j in range(CH):
        ya, yb = y0 + (y1 - y0) * j / CH, y0 + (y1 - y0) * (j + 1) / CH
        for i in range(CW):
            xa, xb = x0 + (x1 - x0) * i / CW, x0 + (x1 - x0) * (i + 1) / CW
            acc = n = 0
            for y in range(int(ya), max(int(ya) + 1, int(yb + 0.999))):
                for x in range(int(xa), max(int(xa) + 1, int(xb + 0.999))):
                    if 0 <= y < len(ink) and 0 <= x < len(ink[0]):
                        acc += ink[y][x]
                        n += 1
            out.append(acc / n if n else 0.0)
    return out


def glyphs(ink):
    """(timecode cells, frame cells): 12 monospace cells on line one, right-aligned digits on line two."""
    bs = bands(ink)
    if len(bs) < 2:
        return None
    (t0, t1), (f0, f1) = bs[0], bs[1]
    cols = [x for x in range(len(ink[0])) if sum(ink[y][x] for y in range(t0, t1)) > 0.5]
    if not cols:
        return None
    # Monospace grid: the hours always start with a full-width "0" and the box pads both sides
    # equally, so the grid comes from the box, never from a narrow glyph such as "1".
    left = cols[0]
    pitch = (len(ink[0]) - 2 * left) / 12.0
    tc = [cell(ink, t0, t1, left + i * pitch, left + (i + 1) * pitch) for i in range(12)]
    # The frame number is right-aligned on the same grid: cells from the last one leftwards while inked.
    fr = []
    for i in range(11, -1, -1):
        x0, x1 = left + i * pitch, left + (i + 1) * pitch
        if sum(ink[y][x] for y in range(f0, f1) for x in range(int(x0), min(len(ink[0]), int(x1)))) < 15:
            break
        fr.insert(0, cell(ink, f0, f1, x0, x1))
    if not fr:
        return None
    return tc, fr


def load_templates():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "digits.json")
    with open(path) as f:
        return {d: [v / 100.0 for v in vals] for d, vals in json.load(f).items()}


def classify(c, templates):
    return min(templates, key=lambda d: sum((a - b) ** 2 for a, b in zip(c, templates[d])))


def ocr(path):
    """Returns (frame, timecode_ms) or None. The timecode and the frame number must agree to count."""
    ink = ink_grid(path)
    g = ink and glyphs(ink)
    if not g:
        return None
    t = load_templates()
    tc, fr = g
    digits = "".join(classify(tc[i], t) for i in (0, 1, 3, 4, 6, 7, 9, 10, 11))
    frame = int("".join(classify(c, t) for c in fr))
    ms = ((int(digits[0:2]) * 60 + int(digits[2:4])) * 60 + int(digits[4:6])) * 1000 + int(digits[6:9])
    if abs(frame * 1000 / FPS - ms) > 1000 / FPS + 1:
        return None
    return frame, ms


def calibrate(out, labelled):
    """Builds digits.json from captures labelled with their true timecode and frame, "png=HHMMSSmmm:frame"."""
    acc = {}
    for item in labelled:
        path, label = item.split("=")
        tcs, fs = label.split(":")
        g = glyphs(ink_grid(path))
        tc, fr = g
        for ch, c in zip(tcs, [tc[i] for i in (0, 1, 3, 4, 6, 7, 9, 10, 11)]):
            acc.setdefault(ch, []).append(c)
        for ch, c in zip(fs, fr):
            acc.setdefault(ch, []).append(c)
    tmpl = {d: [round(100 * sum(v) / len(v)) for v in zip(*cs)] for d, cs in sorted(acc.items())}
    with open(out, "w") as f:
        json.dump(tmpl, f, separators=(",", ":"))
        f.write("\n")
    print(f"templates for {''.join(sorted(tmpl))}")
    return 0


def capture(serial, path):
    t0 = time.monotonic()
    with open(path, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=f, timeout=20)
    t1 = time.monotonic()
    return t0, t1


def pair(s1, s2, outdir, tag, n):
    """Captures both serials at once n times. Drift = (posA - posB) minus the gap between the
    capture midpoints; skew = half the summed capture durations (the midpoint's uncertainty)."""
    rows = []
    with concurrent.futures.ThreadPoolExecutor(2) as ex:
        for i in range(n):
            pa, pb = f"{outdir}/{tag}-{i}-a.png", f"{outdir}/{tag}-{i}-b.png"
            fa, fb = ex.submit(capture, s1, pa), ex.submit(capture, s2, pb)
            (a0, a1), (b0, b1) = fa.result(), fb.result()
            ra, rb = ocr(pa), ocr(pb)
            if ra and rb:
                gap = ((a0 + a1) / 2 - (b0 + b1) / 2) * 1000
                drift = (ra[1] - rb[1]) - gap
                skew = ((a1 - a0) + (b1 - b0)) / 2 * 1000
                rows.append((drift, skew, ra, rb))
                print(f"  pair {i}: a={ra[1]}ms(f{ra[0]}) b={rb[1]}ms(f{rb[0]}) gap={gap:.0f} drift={drift:.0f}ms skew={skew:.0f}ms")
            else:
                print(f"  pair {i}: unreadable a={ra} b={rb}")
            if i < n - 1:
                time.sleep(0.4)
    if not rows:
        print("RESULT none")
        return 1
    med = statistics.median(r[0] for r in rows)
    sk = statistics.median(r[1] for r in rows)
    last = rows[-1]
    print(f"RESULT drift_ms={med:.0f} skew_ms={sk:.0f} n={len(rows)} a_ms={last[2][1]} b_ms={last[3][1]}")
    return 0


def still(s1, s2, outdir, tag):
    """Two paired captures 1.5 s apart: frozen sides read the same frame twice."""
    reads = []
    for k in range(2):
        for attempt in range(3):  # an unreadable capture is retaken at once
            with concurrent.futures.ThreadPoolExecutor(2) as ex:
                pa, pb = f"{outdir}/{tag}-{k}-a.png", f"{outdir}/{tag}-{k}-b.png"
                fa, fb = ex.submit(capture, s1, pa), ex.submit(capture, s2, pb)
                fa.result(); fb.result()
            ra, rb = ocr(pa), ocr(pb)
            if ra and rb:
                break
        reads.append((ra, rb))
        if k == 0:
            time.sleep(1.5)
    (a0, b0), (a1, b1) = reads
    if not all((a0, b0, a1, b1)):
        print(f"RESULT none reads={reads}")
        return 1
    print(f"RESULT a_ms={a0[1]},{a1[1]} b_ms={b0[1]},{b1[1]} a_moved={a1[1] != a0[1]} b_moved={b1[1] != b0[1]} diff_ms={a1[1] - b1[1]}")
    return 0


def main(argv):
    if len(argv) >= 2 and argv[0] == "ocr":
        r = ocr(argv[1])
        print(f"{r[0]} {r[1]}" if r else "none")
        return 0 if r else 1
    if len(argv) >= 3 and argv[0] == "calibrate":
        return calibrate(argv[1], argv[2:])
    if len(argv) >= 5 and argv[0] == "pair":
        emulator_only(argv[1]); emulator_only(argv[2])
        return pair(argv[1], argv[2], argv[3], argv[4], int(argv[5]) if len(argv) > 5 else 5)
    if len(argv) >= 5 and argv[0] == "still":
        emulator_only(argv[1]); emulator_only(argv[2])
        return still(argv[1], argv[2], argv[3], argv[4])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
