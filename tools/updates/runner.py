#!/usr/bin/env python3
"""Emulator-only updater acceptance tests using the existing D-pad/UI hierarchy harness."""
import argparse
import json
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/stress'))
from emulator import Emulator, focus_is, has_text, private_text

OUTPUT = ROOT / 'internal/stress/updates'

def wait(predicate, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate(): return
        time.sleep(.2)
    raise RuntimeError('Expected update test condition did not appear')

def snapshot(emulator):
    xml, tree = emulator.snapshot()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    private_text(OUTPUT / 'ui.xml', xml)
    return tree

def labels(tree):
    return [n.get('text') for n in tree.iter('node') if n.get('text')]

def key(emulator, key):
    emulator.adb('shell', 'input', 'keyevent', 'KEYCODE_' + (key if key in {'BACK', 'HOME'} else 'DPAD_' + key))
    time.sleep(.15)

def focus(emulator, text):
    for _ in range(40):
        tree = snapshot(emulator)
        label = next((t for t in labels(tree) if t.startswith('Settings')), text) if text.startswith('Settings') else text
        if focus_is(tree, label):
            return
        def targets(node, ancestors=()):
            result = []
            if node.get('text') == label or node.get('content-desc') == label:
                candidate = next((n for n in reversed((*ancestors, node)) if n.get('focusable') == 'true'), None)
                if candidate is not None: result.append(candidate)
            for child in node: result.extend(targets(child, (*ancestors, node)))
            return result
        target = next(iter(targets(tree)), None)
        focused = next((n for n in tree.iter('node') if n.get('focused') == 'true'), None)
        def bounds(node):
            return [int(n) for n in re.findall(r'\d+', node.get('bounds', ''))] if node is not None else [0, 0, 0, 0]
        left, top, right, bottom = bounds(target)
        fleft, ftop, fright, fbottom = bounds(focused)
        direction = 'RIGHT' if left >= fright and target is not None else 'LEFT' if right <= fleft and target is not None else 'UP' if target is not None and top < ftop else 'DOWN'
        key(emulator, direction)
    raise RuntimeError('Expected focusable update action was not reachable')

def select(emulator, text):
    focus(emulator, text)
    key(emulator, 'CENTER')

def confirmation_action(tree):
    installer = any('packageinstaller' in n.get('package', '').lower() for n in tree.iter('node'))
    return next((t for t in labels(tree) if t in {'Update', 'Install', 'INSTALL', 'UPDATE'}), None) if installer else None

def control(mode):
    ca = ROOT / 'internal/updates/ca.pem'
    tls = ssl.create_default_context(cafile=str(ca))
    request = urllib.request.Request('https://localhost:18443/__control', data=json.dumps({'mode': mode}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, context=tls) as response: return json.load(response)

def counts():
    tls = ssl.create_default_context(cafile=str(ROOT / 'internal/updates/ca.pem'))
    with urllib.request.urlopen('https://localhost:18443/__control', context=tls) as response: return json.load(response)

def installed_code(emulator):
    output = emulator.adb('shell', 'dumpsys', 'package', 'tv.jellybeam')
    return int(re.search(r'versionCode=(\d+)', output)[1])

def capture(emulator, name):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(['adb', '-s', emulator.serial, 'exec-out', 'screencap', '-p'], capture_output=True, check=True)
    path = OUTPUT / (name + '.png')
    path.write_bytes(result.stdout); path.chmod(0o600)

def open_updates(emulator):
    emulator.adb('shell', 'am', 'start', '-W', '-n', 'tv.jellybeam/.MainActivity')
    wait(lambda: has_text(snapshot(emulator), 'Updates') or any(t.startswith('Synthetic Movie') for t in labels(snapshot(emulator))), 30)
    tree = snapshot(emulator)
    if has_text(tree, 'THIS INSTALL'): return
    if not has_text(tree, 'Updates'):
        # Focus may start mid-row; LEFT until it lands in the drawer's left column.
        for _ in range(12):
            focused = next((n for n in snapshot(emulator).iter('node') if n.get('focused') == 'true'), None)
            if focused is not None and int(re.findall(r'\d+', focused.get('bounds', '[9999,0]'))[0]) < 60: break
            key(emulator, 'LEFT')
        tree = snapshot(emulator)
        settings = next((t for t in labels(tree) if t.startswith('Settings')), None)
        # Many libraries push Settings below the drawer's visible rows.
        for _ in range(30):
            if settings is not None: break
            key(emulator, 'DOWN')
            settings = next((t for t in labels(snapshot(emulator)) if t.startswith('Settings')), None)
        if settings is None: raise RuntimeError('Sign into the synthetic fixture before running update acceptance tests')
        select(emulator, settings)
        wait(lambda: has_text(snapshot(emulator), 'Updates'), 15)
    select(emulator, 'Updates')
    key(emulator, 'RIGHT')
    wait(lambda: has_text(snapshot(emulator), 'THIS INSTALL'), 10)


# Fixture modes that must stop before Android confirmation, with the seconds allowed to settle.
BLOCKED = {'bad-hash': 40, 'missing-digest': 40, 'mutable': 40, 'withdrawn': 40, 'rate-limit': 40, 'stall': 120, 'truncated': 60}
SCENARIOS = ['install', 'cancel', 'notes', 'corrupt-transfer', *BLOCKED]


def run(scenario):
    emulator = Emulator()
    if installed_code(emulator) != 700001:
        raise RuntimeError('Restore the isolated fixture A snapshot before running update acceptance tests')
    control('normal')
    open_updates(emulator)
    tree = snapshot(emulator)
    if not has_text(tree, 'Install update'):
        select(emulator, 'Check again' if has_text(tree, 'Check again') else 'Check for updates')
    wait(lambda: has_text(snapshot(emulator), 'Install update'), 25)
    if scenario == 'notes':
        focus(emulator, next(t for t in labels(snapshot(emulator)) if t.startswith("WHAT'S NEW IN")))
        capture(emulator, 'notes-top')
        wait(lambda: focus_is(snapshot(emulator), next(t for t in labels(snapshot(emulator)) if t.startswith("WHAT'S NEW IN"))), 5)
        for _ in range(40):
            if has_text(snapshot(emulator), 'End of release notes'): break
            key(emulator, 'DOWN')
        if not has_text(snapshot(emulator), 'End of release notes'): raise RuntimeError('Final note was not remotely reachable')
        capture(emulator, 'notes-bottom')
        return
    if scenario == 'cancel':
        control('slow'); select(emulator, 'Install update')
        wait(lambda: has_text(snapshot(emulator), '● DOWNLOADING') and counts()['active_streams'] > 0, 10)
        select(emulator, 'Cancel')
        wait(lambda: counts()['active_streams'] == 0, 5)
        wait(lambda: has_text(snapshot(emulator), 'Install update'), 10)
        return
    if scenario == 'corrupt-transfer':
        # Bytes altered in flight against the digest pinned at check time.
        control('corrupt-transfer'); select(emulator, 'Install update')
        wait(lambda: any('could not be verified' in t for t in labels(snapshot(emulator))), 40)
        return
    if scenario in BLOCKED:
        # Fixture mode changes what the release/transport reports after the check pinned it.
        # Release changes are re-checked after the permission step; grant it so the re-check is what blocks.
        emulator.adb('shell', 'appops', 'set', 'tv.jellybeam', 'REQUEST_INSTALL_PACKAGES', 'allow')
        control(scenario); select(emulator, 'Install update')
        def failed():
            tree = snapshot(emulator)
            if has_text(tree, 'Open settings') or confirmation_action(tree) is not None:
                raise RuntimeError('Update reached Android confirmation despite fixture mode ' + scenario)
            return has_text(tree, 'Try again')
        wait(failed, BLOCKED[scenario])
        if installed_code(emulator) != 700001: raise RuntimeError('Installed code changed in a blocked scenario')
        return
    select(emulator, 'Install update')
    wait(lambda: has_text(snapshot(emulator), 'Open settings') or confirmation_action(snapshot(emulator)) is not None, 90)
    if has_text(snapshot(emulator), 'Open settings'):
        select(emulator, 'Open settings')
        wait(lambda: any('Allow' in t or 'Jellybeam' in t for t in labels(snapshot(emulator))), 10)
        capture(emulator, 'permission')
        tree = snapshot(emulator)
        if has_text(tree, 'Allow from this source'): select(emulator, 'Allow from this source')
        elif has_text(tree, 'Jellybeam'): select(emulator, 'Jellybeam')
        else: raise RuntimeError('Permission screen requires an image-specific remote selector')
        key(emulator, 'BACK')
    tree = snapshot(emulator)
    if has_text(tree, 'Install update'): select(emulator, 'Install update')
    wait(lambda: confirmation_action(snapshot(emulator)) is not None, 60)
    capture(emulator, 'confirmation')
    select(emulator, confirmation_action(snapshot(emulator)))
    wait(lambda: installed_code(emulator) == 700002, 120)
    emulator.adb('shell', 'am', 'start', '-W', '-n', 'tv.jellybeam/.MainActivity')
    def retained_library():
        tree = snapshot(emulator)
        if any(t.startswith('Synthetic Movie') for t in labels(tree)): return True
        # Any other foreground app (the replaced process was killed) means relaunch.
        if any(n.get('package') not in (None, '', 'tv.jellybeam') for n in tree.iter('node')):
            emulator.adb('shell', 'am', 'start', '-W', '-n', 'tv.jellybeam/.MainActivity')
        return False
    wait(retained_library, 30)
    package = emulator.adb('shell', 'dumpsys', 'package', 'tv.jellybeam')
    sdk = int(emulator.adb('shell', 'getprop', 'ro.build.version.sdk'))
    if sdk >= 28 and ('status=speed-profile' not in package or 'reason=install-dm' not in package):
        raise RuntimeError('The delivered profile did not produce installation compilation')
    print(json.dumps({'scenario': scenario, 'installed_code': installed_code(emulator), 'sdk': sdk, 'retained_library': True, 'install_profile': sdk >= 28}))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--scenario', choices=SCENARIOS, default='install')
    a = p.parse_args()
    run(a.scenario)
if __name__ == '__main__': main()
