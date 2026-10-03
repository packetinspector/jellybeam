#!/usr/bin/env python3
"""Prepare local, verified GitHub release assets. Never creates or publishes a release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "tv.jellybeam"
SIGNER = re.search(r'pub const SIGNER: &str = "([a-f0-9]+)"', (ROOT / 'core/app-updates/src/policy.rs').read_text())[1]


def sdk_tool(tool, *args):
    service = os.environ.get('JELLYBEAM_BUILD_SERVICE', 'android-build-arm64')
    command = ['docker', 'compose', 'exec', '-T', service, f'/opt/android-sdk/build-tools/35.0.0/{tool}', *args]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise ValueError('APK inspection or signature verification failed')
    return result.stdout


def container_path(path):
    return '/app/' + str(path.resolve().relative_to(ROOT))


def parse_signers(certs):
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', certs)
    if len(signers) != 1:
        raise ValueError('Exactly one verified signing certificate is required')
    return signers[0]


def parse_badging(text):
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", text)
    sdk = re.search(r"sdkVersion:'([0-9]+)'", text)
    abis = re.search(r"^native-code: (.*)$", text, re.M)  # anchored: not alt-native-code
    if not match or not sdk or not abis or 'application-debuggable' in text:
        raise ValueError('A non-debuggable native release APK is required')
    package, code, version = match.groups()
    if package != PACKAGE or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid official package or release version')
    return dict(package=package, version_code=int(code), version_name=version, min_sdk=int(sdk[1]),
                abis=re.findall(r"'([^']+)'", abis[1]))


def apk_facts(apk):
    certs = sdk_tool('apksigner', 'verify', '--print-certs', container_path(apk))
    facts = parse_badging(sdk_tool('aapt', 'dump', 'badging', container_path(apk)))
    facts.update(signer=parse_signers(certs), debug_identity='CN=Android Debug' in certs)
    return facts


def profile_checksums(data):
    def unpack(offset, size, expanded):
        if not (0 <= offset <= len(data) and 0 <= size <= len(data) - offset and 0 <= expanded <= 32 * 1024 * 1024):
            raise ValueError('Invalid profile section bounds')
        raw = data[offset:offset + size]
        if expanded:
            decoder = zlib.decompressobj()
            raw = decoder.decompress(raw, expanded + 1)
            if len(raw) != expanded or not decoder.eof or decoder.unused_data:
                raise ValueError('Invalid compressed profile')
        return raw
    checksums = {}
    def insert(key, value):
        key = key.split('!')[-1].split(':')[-1]
        if key.endswith('.apk'):
            key = 'classes.dex'
        if key in checksums:
            raise ValueError('Duplicate profile DEX')
        checksums[key] = value
    if data[:8] == b'pro\x00010\x00':
        if len(data) < 17:
            raise ValueError('Truncated profile header')
        if data[8] < 1:
            raise ValueError('Invalid profile DEX count')
        expanded, size = struct.unpack_from('<II', data, 9)
        body = unpack(17, size, expanded)
        if len(body) != expanded:
            raise ValueError('Invalid profile size')
        offset = 0
        blobs = 0
        for _ in range(data[8]):
            length, class_set, hot, checksum, methods = struct.unpack_from('<HHIII', body, offset)
            offset += 16
            if length == 0 or length > len(body) - offset:
                raise ValueError('Invalid profile key length')
            insert(body[offset:offset + length].decode(), checksum)
            offset += length
            blobs += hot + 2 * class_set + (methods * 2 + 7) // 8
        if len(body) - offset != blobs:
            raise ValueError('Invalid profile data size')
    elif data[:8] == b'pro\x00015\x00':
        if len(data) < 12:
            raise ValueError('Truncated profile header')
        count, = struct.unpack_from('<I', data, 8)
        if not 1 <= count <= 8:
            raise ValueError('Invalid profile section count')
        found = False
        for index in range(count):
            kind, start, size, expanded = struct.unpack_from('<IIII', data, 12 + index * 16)
            if kind == 0:
                if found:
                    raise ValueError('Duplicate DEX section')
                found = True
                body = unpack(start, size, expanded)
                dex_count, = struct.unpack_from('<H', body, 0)
                if not 1 <= dex_count <= 256:
                    raise ValueError('Invalid profile DEX count')
                offset = 2
                for _ in range(dex_count):
                    checksum, _, _, length = struct.unpack_from('<IIIH', body, offset)
                    offset += 14
                    insert(body[offset:offset + length].decode(), checksum)
                    offset += length
                if offset != len(body):
                    raise ValueError('Invalid DEX section size')
        if not found:
            raise ValueError('Missing DEX section')
    else:
        raise ValueError('Unsupported profile version')
    return checksums


def validate_profile(apk, profile):
    with zipfile.ZipFile(apk) as zip_apk:
        expected = {}
        for info in zip_apk.infolist():
            if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', info.filename):
                expected[info.filename] = info.CRC
    with zipfile.ZipFile(profile) as zip_profile:
        infos = zip_profile.infolist()
        if not 1 <= len(infos) <= 3 or len({i.filename for i in infos}) != len(infos):
            raise ValueError('Invalid profile archive')
        if any(i.filename not in {'primary.prof', 'primary.profm', 'manifest.json'} or i.file_size > 32 * 1024 * 1024 for i in infos):
            raise ValueError('Unexpected or oversized profile entry')
        if zip_profile.getinfo('primary.prof').file_size > 16 * 1024 * 1024:
            raise ValueError('Profile too large')
        try:
            actual = profile_checksums(zip_profile.read('primary.prof'))
        except (struct.error, UnicodeDecodeError):
            raise ValueError('Truncated or malformed profile')
        if not actual or any(expected.get(name) != checksum for name, checksum in actual.items()):
            raise ValueError('Profile does not belong to this APK')


MAX_PROFILES = 8  # policy.rs Metadata validation: profiles.len() > 8 rejected
MAX_ASSETS = 64   # policy.rs Release validation: assets.len() > 64 rejected
NOTES_LIMIT = 16 * 1024
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86_64'}
INSTALL_CUT = '## Install'


def safe_name(name):
    """Mirror of policy.rs safe_name."""
    return bool(name) and len(name) <= 128 and not name.startswith('.') and bool(re.fullmatch(r'[A-Za-z0-9._-]+', name))


def viewer_notes(body):
    """Mirror of policy.rs viewer_notes cut: a line equal to `## Install` (trimmed, ASCII
    case-insensitive) ends the viewer text. Vectors: notes_hide_install_and_bound_utf8."""
    kept = []
    for line in body.split('\n'):
        line = line[:-1] if line.endswith('\r') else line
        trimmed = line.strip()
        if trimmed.isascii() and trimmed.lower() == INSTALL_CUT.lower():
            break
        kept.append(line)
    return '\n'.join(kept).strip()


def check_notes(body):
    viewer = viewer_notes(body)
    if not viewer or len(viewer.encode()) > NOTES_LIMIT:
        raise ValueError('Viewer release notes must be nonempty and at most 16 KiB')
    return viewer


def check_version(code, previous_code):
    if code <= previous_code:
        raise ValueError('Version code must increase beyond the newest official release')


def check_abis(abis):
    if set(abis) != ABIS:
        raise ValueError('The release must include all three supported ABIs')


def check_signer(facts, is_fixture, fixture):
    if fixture:
        if not is_fixture:
            raise ValueError('--fixture requires a fixture build (./build.sh update-fixture)')
        return
    if is_fixture:
        raise ValueError('Fixture artifacts cannot be published; build with ./build.sh release')
    if facts['debug_identity'] or facts['signer'] != SIGNER:
        raise ValueError('APK not signed with the production key; add keystore.properties (docs/06)')


def check_buckets(ranges):
    if not ranges:
        raise ValueError('Baseline profiles are required for every supported SDK from API 28 onward')
    if len(ranges) > MAX_PROFILES:
        raise ValueError('Too many profile SDK buckets')
    if not all(type(v) is int for pair in ranges for v in pair):
        raise ValueError('Overlapping or invalid profile SDK buckets')
    ordered = sorted(ranges)
    for lo, hi in ordered:
        if lo < 28 or hi < lo:
            raise ValueError('Overlapping or invalid profile SDK buckets')
    for (_, end), (start, _) in zip(ordered, ordered[1:]):
        if start <= end:
            raise ValueError('Overlapping or invalid profile SDK buckets')
        if start != end + 1:
            raise ValueError('Profiles must cover every supported SDK from API 28 onward')
    if ordered[0][0] != 28 or ordered[-1][1] != 2147483647:
        raise ValueError('Profiles must cover every supported SDK from API 28 onward')


def check_asset_names(apk_name, profile_names):
    names = [apk_name, 'jellybeam-tv-update.json', *profile_names]
    if len(names) > MAX_ASSETS:
        raise ValueError('Too many release assets')
    if len(set(names)) != len(names) or not all(safe_name(n) for n in names):
        raise ValueError('Unsafe or duplicate release asset name')
    if not apk_name.endswith('.apk') or not all(n.endswith('.dm') for n in profile_names):
        raise ValueError('Release asset names must end in .apk / .dm')


def is_fixture_apk(apk):
    with zipfile.ZipFile(apk) as zip_apk:
        return any(b'https://localhost:18443' in zip_apk.read(i) for i in zip_apk.namelist() if i.endswith('/libjellybeam_core.so'))


def read_buckets(agp):
    try:
        buckets = agp['baselineProfiles']
        return [(b['minApi'], b['maxApi'], b['baselineProfiles'][0]) for b in buckets]
    except (KeyError, IndexError, TypeError):
        raise ValueError('Baseline profiles are required: output-metadata.json lists none')


def prepare(apk, metadata, output, notes, previous_code, fixture=False):
    facts = apk_facts(apk)
    check_version(facts['version_code'], previous_code)
    check_abis(facts['abis'])
    check_signer(facts, is_fixture_apk(apk), fixture)
    body = notes.read_text()
    check_notes(body)
    agp = json.loads(metadata.read_text())
    if agp['elements'][0]['versionCode'] != facts['version_code'] or agp['applicationId'] != PACKAGE:
        raise ValueError('Stale AGP output metadata')
    buckets = read_buckets(agp)
    check_buckets([(lo, hi) for lo, hi, _ in buckets])
    version = facts['version_name']
    apk_name = f'jellybeam-tv-{version}.apk'
    manifest = {k: facts[k] for k in ('package', 'version_code', 'min_sdk', 'abis')}
    manifest.update(schema=1, apk_asset=apk_name, profiles=[])
    sources = []
    for lo, hi, relative in buckets:
        source = (metadata.parent / relative).resolve()
        if not source.is_relative_to(metadata.parent.resolve()):
            raise ValueError('Invalid profile path')
        validate_profile(apk, source)
        name = f'jellybeam-tv-{version}-api{lo}-{hi}.dm'
        sources.append((source, name))
        manifest['profiles'].append(dict(min_sdk=lo, max_sdk=hi, asset=name))
    check_asset_names(apk_name, [name for _, name in sources])
    encoded = json.dumps(manifest, indent=2) + '\n'
    if len(encoded.encode()) > 4096:
        raise ValueError('Update metadata exceeds 4 KiB')
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError('Output directory must not exist or be empty')
    # Everything is validated; write into a sibling temp dir and move it into place so a
    # failed run never leaves stale assets behind.
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.prepare-', dir=output.parent))
    try:
        for source, name in sources:
            shutil.copyfile(source, staging / name)
        shutil.copyfile(apk, staging / apk_name)
        (staging / 'jellybeam-tv-update.json').write_text(encoded)
        (staging / 'release-notes.md').write_text(body)
        files = [apk_name, 'jellybeam-tv-update.json'] + [name for _, name in sources]
        digests = {name: {'size': (staging / name).stat().st_size, 'digest': 'sha256:' + hashlib.sha256((staging / name).read_bytes()).hexdigest()} for name in files}
        (staging / 'local-checksums.json').write_text(json.dumps(digests, indent=2) + '\n')
        if output.exists():
            output.rmdir()
        os.rename(staging, output)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--output-metadata', type=Path, default=ROOT / 'app/build/outputs/apk/release/output-metadata.json')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--previous-version-code', type=int, required=True)
    parser.add_argument('--fixture', action='store_true', help='Prepare local emulator-only assets; production validation rejects them')
    args = parser.parse_args()
    try:
        manifest = prepare(args.apk, args.output_metadata, args.output_dir, args.notes, args.previous_version_code, args.fixture)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error, zlib.error) as error:
        raise SystemExit(str(error))
    print(json.dumps({'version_code': manifest['version_code'], 'profile_buckets': len(manifest['profiles']), 'fixture': args.fixture}))

if __name__ == '__main__':
    main()
