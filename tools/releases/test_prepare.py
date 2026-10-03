import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def profile(crc):
    dex = struct.pack('<HI IIH', 1, crc, 1, 5, 11) + b'classes.dex'
    return b'pro\x00015\x00' + struct.pack('<IIIII', 1, 0, 28, len(dex), 0) + dex

class ReleaseProfileTest(unittest.TestCase):
    def test_profiles_match_zip_crc_and_need_not_cover_unprofiled_dex(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'synthetic.apk'
            dm = Path(directory) / 'synthetic.dm'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'synthetic DEX')
                archive.writestr('classes2.dex', b'unprofiled synthetic DEX')
            with zipfile.ZipFile(dm, 'w') as archive:
                archive.writestr('primary.prof', profile(zlib.crc32(b'synthetic DEX')))
            prepare.validate_profile(apk, dm)
            with zipfile.ZipFile(dm, 'w') as archive:
                archive.writestr('primary.prof', profile(123))
            with self.assertRaises(ValueError): prepare.validate_profile(apk, dm)

    def test_decompression_is_bounded(self):
        body = b'x' * 1000
        packed = zlib.compress(body)
        data = b'pro\x00010\x00' + bytes([1]) + struct.pack('<II', 10, len(packed)) + packed
        with self.assertRaises(ValueError): prepare.profile_checksums(data)

    def test_unsupported_profile_rejected(self):
        with self.assertRaises(ValueError): prepare.profile_checksums(b'pro\x00099\x00')


def profile010(dexes, count=None, trim=0, extra=b''):
    """dexes: (key, crc, class_set, hot, methods); data regions are nonzero filler."""
    head, blobs = b'', b''
    for name, crc, class_set, hot, methods in dexes:
        head += struct.pack('<HHIII', len(name), class_set, hot, crc, methods) + name.encode()
        blobs += b'\x07' * (hot + 2 * class_set + (methods * 2 + 7) // 8)
    body = (head + blobs + extra)[:len(head + blobs + extra) - trim]
    packed = zlib.compress(body)
    return b'pro\x00010\x00' + bytes([len(dexes) if count is None else count]) + struct.pack('<II', len(body), len(packed)) + packed


class Profile010Test(unittest.TestCase):
    def test_parses_dex_checksums(self):
        data = profile010([('base.apk', 11, 3, 10, 40), ('base.apk!classes2.dex', 22, 0, 5, 9)])
        self.assertEqual(prepare.profile_checksums(data), {'classes.dex': 11, 'classes2.dex': 22})

    def test_inconsistent_layouts_rejected(self):
        one = ('base.apk', 1, 2, 3, 16)
        two = ('base.apk!classes2.dex', 2, 0, 0, 0)
        for data in (profile010([one], trim=1), profile010([one], extra=b'\x00'), profile010([one, one]),
                     profile010([one, two], count=1), profile010([]), profile010([one], count=0)):
            with self.assertRaises(ValueError): prepare.profile_checksums(data)
        good = profile010([one])
        bad = good[:9] + struct.pack('<I', struct.unpack_from('<I', good, 9)[0] + 1) + good[13:]
        with self.assertRaises(ValueError): prepare.profile_checksums(bad)

    def test_short_header_is_a_clean_error(self):
        for data in (b'pro\x00010\x00', b'pro\x00010\x00\x01' + b'\x00' * 4, b'pro\x00015\x00\x01'):
            with self.assertRaises(ValueError): prepare.profile_checksums(data)

    def test_truncated_body_is_a_clean_error(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'a.apk'
            dm = Path(directory) / 'a.dm'
            with zipfile.ZipFile(apk, 'w') as archive: archive.writestr('classes.dex', b'x')
            with zipfile.ZipFile(dm, 'w') as archive: archive.writestr('primary.prof', profile010([('base.apk', 1, 0, 0, 0)], count=3))
            with self.assertRaises(ValueError): prepare.validate_profile(apk, dm)


# Same strings as the Rust test notes_hide_install_and_bound_utf8 (core/app-updates/src/lib.rs),
# plus the line-trim/case rules of policy.rs viewer_notes.
NOTE_VECTORS = [
    ('## Changes\n- Faster\n## Install\nHidden', '## Changes\n- Faster'),
    ('## Changes\n- Faster\n  ## INSTALL  \nHidden', '## Changes\n- Faster'),
    ('## Changes\r\n- Faster\r\n## Install\r\nHidden', '## Changes\n- Faster'),
    ('\n\n## New\n- A\n\n', '## New\n- A'),
    ('## New\n- A\n## Installation\nKept', '## New\n- A\n## Installation\nKept'),
    ('## New\n- A\n## Install extra\nKept', '## New\n- A\n## Install extra\nKept'),
    ('## Install\nOnly publisher text', ''),
]


class NotesTest(unittest.TestCase):
    def test_viewer_cut_matches_rust_vectors(self):
        for body, expected in NOTE_VECTORS:
            self.assertEqual(prepare.viewer_notes(body), expected, body)

    def test_empty_and_oversize_rejected(self):
        for body in ('', '   \n', '## Install\nonly'):
            with self.assertRaises(ValueError): prepare.check_notes(body)
        with self.assertRaises(ValueError): prepare.check_notes('x' * (16 * 1024 + 1))
        with self.assertRaises(ValueError): prepare.check_notes('\U0001F3AC' * 4097)
        prepare.check_notes('x' * (16 * 1024) + '\n## Install\n' + 'y' * 100000)


def facts(**over):
    base = dict(signer=prepare.SIGNER, debug_identity=False)
    base.update(over)
    return base


class DecisionTest(unittest.TestCase):
    def test_signer(self):
        prepare.check_signer(facts(), False, False)
        prepare.check_signer(facts(signer='0' * 64), True, True)
        with self.assertRaisesRegex(ValueError, 'production key'): prepare.check_signer(facts(signer='0' * 64), False, False)
        with self.assertRaisesRegex(ValueError, 'production key'): prepare.check_signer(facts(debug_identity=True), False, False)
        with self.assertRaisesRegex(ValueError, 'Fixture artifacts'): prepare.check_signer(facts(), True, False)
        with self.assertRaisesRegex(ValueError, 'requires a fixture'): prepare.check_signer(facts(), False, True)

    def test_version_must_increase(self):
        prepare.check_version(8, 7)
        for code in (7, 6): 
            with self.assertRaises(ValueError): prepare.check_version(code, 7)

    def test_abi_set_is_exact(self):
        prepare.check_abis(['x86_64', 'arm64-v8a', 'armeabi-v7a'])
        for abis in (['arm64-v8a'], ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86']):
            with self.assertRaises(ValueError): prepare.check_abis(abis)

    def test_buckets(self):
        top = 2147483647
        prepare.check_buckets([(28, 30), (31, top)])
        prepare.check_buckets([(31, top), (28, 30)])
        prepare.check_buckets([(28, top)])
        for bad in ([], [(28, 30)], [(31, top)], [(28, 31), (31, top)], [(28, 29), (31, top)], [(23, top)], [(30, 28)],
                    [(i, i) for i in range(28, 37)] + [(37, top)]):
            with self.assertRaises(ValueError, msg=bad): prepare.check_buckets(bad)

    def test_asset_names(self):
        prepare.check_asset_names('jellybeam-tv-1.2.3.apk', ['jellybeam-tv-1.2.3-api28-30.dm'])
        for apk, dms in (('.hidden.apk', []), ('a b.apk', []), ('x' * 130 + '.apk', []), ('jellybeam-tv-1.0.0+1.apk', []),
                         ('a.apk', ['p.zip']), ('a.apk', ['p.dm', 'p.dm']), ('a.apk', [f'p{i}.dm' for i in range(63)])):
            with self.assertRaises(ValueError, msg=(apk, dms)): prepare.check_asset_names(apk, dms)

    def test_badging_native_code_is_anchored(self):
        text = ("package: name='tv.jellybeam' versionCode='7' versionName='0.1.6'\nsdkVersion:'23'\n"
                "alt-native-code: 'x86'\nnative-code: 'arm64-v8a' 'x86_64'\n")
        self.assertEqual(prepare.parse_badging(text)['abis'], ['arm64-v8a', 'x86_64'])
        with self.assertRaises(ValueError): prepare.parse_badging(text.replace('\nnative-code', '\nxnative-code'))
        with self.assertRaises(ValueError): prepare.parse_badging(text + 'application-debuggable\n')

    def test_missing_baseline_profiles_is_a_clear_error(self):
        with self.assertRaisesRegex(ValueError, 'Baseline profiles are required'): prepare.read_buckets({'elements': []})
        with self.assertRaisesRegex(ValueError, 'Baseline profiles are required'): prepare.read_buckets({'baselineProfiles': [{'minApi': 28}]})


class OutputDirTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        root = Path(self.dir.name)
        self.apk = root / 'x.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive: archive.writestr('classes.dex', b'synthetic DEX')
        (root / 'bucket').mkdir()
        with zipfile.ZipFile(root / 'bucket/app.dm', 'w') as archive: archive.writestr('primary.prof', profile(zlib.crc32(b'synthetic DEX')))
        self.meta = root / 'output-metadata.json'
        self.meta.write_text('{"applicationId":"tv.jellybeam","elements":[{"versionCode":8}],'
                             '"baselineProfiles":[{"minApi":28,"maxApi":2147483647,"baselineProfiles":["bucket/app.dm"]}]}')
        self.notes = root / 'notes.md'
        self.notes.write_text('## New\n- A\n## Install\nhidden')
        self.out = root / 'out'
        self.orig = (prepare.apk_facts, prepare.is_fixture_apk)
        good = dict(package='tv.jellybeam', version_code=8, version_name='1.2.3', min_sdk=23,
                    abis=sorted(prepare.ABIS), signer=prepare.SIGNER, debug_identity=False)
        prepare.apk_facts = lambda apk: good
        prepare.is_fixture_apk = lambda apk: False

    def tearDown(self):
        prepare.apk_facts, prepare.is_fixture_apk = self.orig
        self.dir.cleanup()

    def run_prepare(self):
        return prepare.prepare(self.apk, self.meta, self.out, self.notes, 7)

    def test_emits_exact_asset_set_and_refuses_reuse(self):
        self.run_prepare()
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), [
            'jellybeam-tv-1.2.3-api28-2147483647.dm', 'jellybeam-tv-1.2.3.apk', 'jellybeam-tv-update.json',
            'local-checksums.json', 'release-notes.md'])
        with self.assertRaises(ValueError): self.run_prepare()

    def test_failure_leaves_no_output_or_temp(self):
        (self.out.parent / 'p').mkdir()
        self.meta.write_text(self.meta.read_text().replace('"versionCode":8', '"versionCode":9'))
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertFalse(self.out.exists())
        self.assertEqual([p.name for p in self.out.parent.iterdir() if p.name.startswith('.prepare-')], [])

    def test_non_empty_output_is_untouched(self):
        self.out.mkdir()
        (self.out / 'stale.dm').write_bytes(b'x')
        with self.assertRaises(ValueError): self.run_prepare()
        self.assertEqual([p.name for p in self.out.iterdir()], ['stale.dm'])

    def test_empty_output_dir_is_accepted(self):
        self.out.mkdir()
        self.run_prepare()
        self.assertTrue((self.out / 'jellybeam-tv-update.json').is_file())


if __name__ == '__main__': unittest.main()
