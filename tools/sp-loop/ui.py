#!/usr/bin/env python3
"""uiautomator dump queries for tools/sp-loop.sh. Reads a dump XML file.

  ui.py texts DUMP           every text node, one per line
  ui.py focused DUMP         the text inside the focused node (the label of the focused row/button)
  ui.py members DUMP         N from the lobby's "IN THE GROUP · N", or nothing
"""
import re
import sys


def nodes(path):
    try:
        xml = open(path, encoding="utf-8").read()
    except OSError:
        return []
    out = []
    for n in re.findall(r"<node [^>]*>", xml):
        g = lambda a: (re.search(a + r'="([^"]*)"', n) or [0, ""])[1]
        b = [int(v) for v in re.findall(r"\d+", g("bounds"))] or [0, 0, 0, 0]
        text = g("text").replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        out.append({"text": text, "focused": g("focused") == "true", "b": b})
    return out


def focused(ns):
    f = [n for n in ns if n["focused"]]
    if not f:
        return ""
    fb = f[-1]["b"]
    if f[-1]["text"]:
        return f[-1]["text"]
    for n in ns:
        if not n["text"]:
            continue
        cx, cy = (n["b"][0] + n["b"][2]) / 2, (n["b"][1] + n["b"][3]) / 2
        if fb[0] <= cx <= fb[2] and fb[1] <= cy <= fb[3]:
            return n["text"]
    return ""


def members(ns):
    for n in ns:
        m = re.fullmatch(r"IN THE GROUP · (\d+)", n["text"])
        if m:
            return m[1]
    return ""


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    ns = nodes(argv[1])
    if argv[0] == "texts":
        for n in ns:
            if n["text"]:
                print(n["text"])
    elif argv[0] == "focused":
        print(focused(ns))
    elif argv[0] == "members":
        print(members(ns))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
