#!/usr/bin/env python3
"""Emulator-only diagnostics. Raw captures stay in ignored local storage."""

import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from server import USER, synthetic_id


def private_text(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as file:
        file.write(value)


def target():
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True)
    matches = [line.split()[0] for line in result.stdout.splitlines()
               if re.fullmatch(r"emulator-\d+\s+device", line.strip())]
    if len(matches) != 1:
        raise SystemExit("Exactly one emulator must be connected; no physical device is selected.")
    return matches[0]


class Emulator:
    def __init__(self):
        self.serial = target()

    def adb(self, *args, timeout=30):
        result = subprocess.run(["adb", "-s", self.serial, *args],
                                capture_output=True, timeout=timeout)
        if result.returncode or (args[:3] == ("shell", "am", "start") and b"Error:" in result.stdout + result.stderr):
            raise RuntimeError("Emulator command failed; diagnostic output suppressed for privacy.")
        return result.stdout.decode(errors="replace")

    def snapshot(self):
        self.adb("shell", "uiautomator", "dump", "/data/local/tmp/jellybeam-stress-ui.xml")
        xml = self.adb("exec-out", "cat", "/data/local/tmp/jellybeam-stress-ui.xml")
        return xml, ET.fromstring(xml)

    def require_synthetic(self):
        sessions = json.loads(self.adb("exec-out", "cat", "/data/user/0/tv.jellybeam/files/jellybeam/sessions.json"))
        active = sessions["sessions"][sessions["active"]]
        if (not active.get("mirror_dir", "").startswith("mirror-synthetic-stress")
                or active.get("server_url") != "http://127.0.0.1:18096"
                or active.get("user_id") != USER["Id"]):
            raise SystemExit("This test requires the isolated synthetic session.")

    def wait_ui(self, predicate):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            _, tree = self.snapshot()
            if predicate(tree):
                return tree
            time.sleep(0.1)
        raise RuntimeError("Expected emulator focus/screen state did not appear.")


def has_text(tree, text):
    return any(n.get("text") == text for n in tree.iter("node"))


def has_text_prefix(tree, prefix):
    return any((n.get("text") or "").startswith(prefix) for n in tree.iter("node"))


def has_focus(tree):
    return any(n.get("focused") == "true" for n in tree.iter("node"))


def focused_node(tree):
    return next((n for n in tree.iter("node") if n.get("focused") == "true"), None)


def focus_is(tree, text):
    """True only when the focused node is the element labelled [text]: the label sits on the node
    itself or on one of its descendants, and no focusable lies beneath it. A focused container that
    merely contains the label (a row, a page) fails, so focus-routing regressions cannot pass."""
    node = focused_node(tree)
    if node is None:
        return False
    if any(d.get("focusable") == "true" for d in node.iter("node") if d is not node):
        return False
    return any(d.get("text") == text or d.get("content-desc") == text for d in node.iter("node"))


# Stable markers of the two screens the focus cycle passes through, from the synthetic fixture.
DETAIL_MENU_DOOR = "\u00b7\u00b7\u00b7"
MENU_PANEL_KICKER = "THIS TITLE"
HOME_SHELF_PREFIX = "Latest in Synthetic Library"


def cycle_state_after_menu_close(tree):
    return not has_text(tree, MENU_PANEL_KICKER) and focus_is(tree, DETAIL_MENU_DOOR)


def cycle_state_on_home(tree):
    return (has_text_prefix(tree, HOME_SHELF_PREFIX) and not has_text(tree, DETAIL_MENU_DOOR)
            and has_focus(tree))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["snapshot", "keys", "metrics", "measure", "playback", "focus-cycles", "launch", "provision", "updates"])
    # tools/updates/runner.py owns the scenario list and rejects unknown names.
    parser.add_argument("--update-scenario", default="install")
    parser.add_argument("--keys", default="")
    parser.add_argument("--interval", type=float, default=0.15)
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--seconds", type=float, default=15)
    parser.add_argument("--fresh-mirror", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.rounds <= 1000 or not 0 < args.seconds <= 300 or args.interval < 0:
        parser.error("Use 1–1000 rounds, 0–300 seconds, and a nonnegative interval.")
    emulator = Emulator()
    if args.action == "updates":
        subprocess.run(["python3", str(Path(__file__).resolve().parents[1] / "updates/runner.py"), "--scenario", args.update_scenario], check=True)
    elif args.action == "focus-cycles":
        emulator.require_synthetic()
        for index in range(args.rounds):
            emulator.adb("shell", "am", "start", "-a", "tv.jellybeam.action.OPEN_DETAIL", "-n", "tv.jellybeam/.MainActivity",
                         "--es", "tv.jellybeam.extra.ITEM_ID", synthetic_id(f"movie-{index}"))
            emulator.wait_ui(lambda tree: has_text(tree, DETAIL_MENU_DOOR) and has_focus(tree))
            emulator.adb("shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT")
            emulator.wait_ui(lambda tree: focus_is(tree, DETAIL_MENU_DOOR))
            emulator.adb("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
            emulator.wait_ui(lambda tree: has_text(tree, MENU_PANEL_KICKER) and has_focus(tree))
            emulator.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            emulator.wait_ui(cycle_state_after_menu_close)
            emulator.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            emulator.wait_ui(cycle_state_on_home)
            print(json.dumps({"completed_focus_cycles": index + 1}), flush=True)
    elif args.action == "playback":
        emulator.require_synthetic()
        results = []
        for _ in range(args.rounds):
            emulator.adb("shell", "am", "force-stop", "tv.jellybeam")
            emulator.adb("shell", "setprop", "log.tag.JellybeamTV", "DEBUG")
            emulator.adb("shell", "am", "start", "-a", "tv.jellybeam.action.PLAY", "-p", "tv.jellybeam",
                         "--es", "tv.jellybeam.extra.ITEM_ID", synthetic_id("movie-0"))
            deadline = time.monotonic() + 20
            phases = {}
            while time.monotonic() < deadline:
                pid = emulator.adb("shell", "pidof tv.jellybeam || true").strip()
                if not pid:
                    time.sleep(0.2)
                    continue
                logs = emulator.adb("logcat", "-d", "--pid=" + pid, "-s", "JellybeamTV:D", "*:S")
                phases = {name: int(at) for name, at in re.findall(r"perf playback phase=([\w.]+) atMs=(\d+)", logs)}
                if "firstFrame" in phases:
                    break
                time.sleep(0.5)
            result = {"rendered": "firstFrame" in phases}
            for name, start, end in [("activity_to_first_frame_ms", "activity.create", "firstFrame"),
                                      ("pre_exo_ms", "viewModel.start", "exo.prepare"),
                                      ("exo_to_first_frame_ms", "exo.prepare", "firstFrame")]:
                if start in phases and end in phases:
                    result[name] = phases[end] - phases[start]
            if not result["rendered"]:
                result["phases_seen"] = sorted(phases)
            results.append(result)
            print(json.dumps(result), flush=True)
            emulator.adb("shell", "am", "force-stop", "tv.jellybeam")
        output = Path(__file__).resolve().parents[2] / "internal/stress"
        output.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_text(output / ("playback-" + str(time.time_ns()) + ".json"), json.dumps(results, indent=2))
        if any(not result["rendered"] for result in results):
            raise SystemExit("Playback did not render in every test attempt.")
    elif args.action == "provision":
        if emulator.adb("shell", "id", "-u").strip() != "0":
            raise SystemExit("Provisioning requires a rooted emulator.")
        activities = emulator.adb("shell", "dumpsys", "activity", "activities")
        if re.search(r"(?:mResumedActivity|topResumedActivity).*tv.jellybeam/.*PlaybackActivity", activities):
            raise SystemExit("Exit playback before provisioning the emulator.")
        base = "/data/user/0/tv.jellybeam/files/jellybeam"
        owner = emulator.adb("shell", "stat", "-c", "%u:%g", base).strip()
        if not re.fullmatch(r"\d+:\d+", owner):
            raise SystemExit("Unable to resolve app data ownership.")
        emulator.adb("shell", "am", "force-stop", "tv.jellybeam")
        raw = emulator.adb("exec-out", "cat", base + "/sessions.json")
        sessions = json.loads(raw)
        output = Path(__file__).resolve().parents[2] / "internal/stress"
        output.mkdir(parents=True, exist_ok=True, mode=0o700)
        output.chmod(0o700)
        backup = output / ("sessions-before-" + str(time.time_ns()) + ".json")
        private_text(backup, raw)
        session = {"server_url": "http://127.0.0.1:18096", "user_id": USER["Id"],
                   "user_name": USER["Name"], "token": "synthetic-test-token",
                   "device_id": "synthetic-emulator", "mirror_dir": "mirror-synthetic-stress",
                   "server_version": "12.0.0"}
        existing = next((i for i, s in enumerate(sessions["sessions"])
                         if s.get("server_url") == session["server_url"] and s.get("user_id") == USER["Id"]), None)
        if args.fresh_mirror:
            session["mirror_dir"] += "-" + str(time.time_ns())
        if existing is None:
            existing = len(sessions["sessions"])
            sessions["sessions"].append(session)
        elif args.fresh_mirror:
            sessions["sessions"][existing] = session
        sessions["active"] = existing
        staged = output / "sessions-staged.json"
        private_text(staged, json.dumps(sessions))
        emulator.adb("push", str(staged), base + "/sessions.stress.tmp")
        emulator.adb("shell", "chown", owner, base + "/sessions.stress.tmp")
        emulator.adb("shell", "chmod", "600", base + "/sessions.stress.tmp")
        emulator.adb("shell", "mv", base + "/sessions.stress.tmp", base + "/sessions.json")
        staged.unlink()
        emulator.adb("reverse", "tcp:18096", "tcp:18096")
        print("Synthetic session provisioned; original sessions backed up in ignored local storage.")
    elif args.action == "snapshot":
        xml, tree = emulator.snapshot()
        output = Path(__file__).resolve().parents[2] / "internal/stress"
        output.mkdir(parents=True, exist_ok=True, mode=0o700)
        output.chmod(0o700)
        private_text(output / "ui.xml", xml)
        allowed = {"Home", "Settings", "Sign in", "Add server", "Server URL",
                   "Username", "Password", "Use Quick Connect", "Servers", "Search"}
        nodes = []
        for node in tree.iter("node"):
            a = node.attrib
            if a.get("focusable") == "true" or a.get("text") in allowed:
                nodes.append({k: (v if k != "text" or v in allowed else "<REDACTED>")
                              for k, v in a.items()
                              if k in {"class", "text", "bounds", "focused", "focusable"}})
        print(json.dumps(nodes, indent=2))
    elif args.action == "keys":
        keys = args.keys.upper().split(",")
        allowed = {"UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK"}
        if not keys or any(key not in allowed for key in keys):
            parser.error("Keys must be UP, DOWN, LEFT, RIGHT, CENTER or BACK.")
        for _ in range(args.rounds):
            for key in keys:
                emulator.adb("shell", "input", "keyevent", "KEYCODE_" +
                             (key if key == "BACK" else "DPAD_" + key))
                time.sleep(max(0, args.interval))
        print(json.dumps({"keys_sent": len(keys) * args.rounds}))
    elif args.action == "launch":
        emulator.adb("shell", "setprop", "log.tag.JellybeamTV", "DEBUG")
        emulator.adb("shell", "am", "start", "-W", "-n", "tv.jellybeam/.MainActivity")
        print("Emulator app launched; performance logging enabled.")
    else:
        if args.action == "measure":
            emulator.adb("shell", "dumpsys", "gfxinfo", "tv.jellybeam", "reset")
            started = time.monotonic()
            keys = args.keys.upper().split(",") if args.keys else []
            if any(k not in {"UP", "DOWN", "LEFT", "RIGHT"} for k in keys):
                parser.error("Measurement keys are directional only; no accidental playback starts.")
            sent = 0
            while time.monotonic() - started < args.seconds:
                if keys:
                    key = keys[sent % len(keys)]
                    emulator.adb("shell", "input", "keyevent", "KEYCODE_DPAD_" + key)
                    sent += 1
                time.sleep(max(0.02, args.interval))
            print(json.dumps({"duration_seconds": round(time.monotonic() - started, 2),
                              "keys_sent": sent}))
        frames = emulator.adb("shell", "dumpsys", "gfxinfo", "tv.jellybeam")
        memory = emulator.adb("shell", "dumpsys", "meminfo", "tv.jellybeam")
        logs = emulator.adb("logcat", "-d", "-s", "JellybeamTV:D", "*:S")
        # Allow numeric aggregate telemetry only, never titles, IDs or URLs.
        frame_count = re.search(r"Total frames rendered:\s*(\d+)", frames)
        for line in frames.splitlines():
            if re.match(r"\s*(Total frames rendered:|Janky frames:|\d+th percentile:)", line):
                if "percentile:" in line and (frame_count is None or int(frame_count[1]) == 0):
                    continue
                print(line.strip())
        for line in memory.splitlines():
            if re.match(r"\s*(TOTAL\s|TOTAL PSS:|TOTAL RSS:)", line):
                print(line.strip())
        for line in (logs.splitlines() if args.action != "measure" else []):
            match = re.search(r"perf frames count=\d+ janky=\d+ p50Ms=[\d.]+ p90Ms=[\d.]+", line)
            if match:
                print(match.group())


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit("Emulator command timed out; command details suppressed for privacy.")
    except RuntimeError as error:
        raise SystemExit(str(error))
