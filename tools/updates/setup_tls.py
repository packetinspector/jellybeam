#!/usr/bin/env python3
"""Create ignored local fixture CA and server certificates before building fixture APKs."""
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'internal/updates'
CA_DAYS, LEAF_DAYS = '3650', '825'  # local-only fixture; long enough not to expire mid-project
GENERATED = ('ca.pem', 'ca-key.pem', 'ca.srl', 'server.pem', 'server-key.pem', 'server.csr', 'server.ext')

def command(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def leaf(ca, key):
    server_key = DIRECTORY / 'server-key.pem'
    csr = DIRECTORY / 'server.csr'
    command('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(server_key), '-out', str(csr), '-subj', '/CN=localhost')
    extensions = DIRECTORY / 'server.ext'
    extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n')
    # Emulator snapshots restore old guest clocks; a backdated start keeps TLS valid after a restore.
    now = datetime.now(timezone.utc)
    stamp = lambda t: t.strftime('%Y%m%d%H%M%SZ')
    command('openssl', 'x509', '-req', '-in', str(csr), '-CA', str(ca), '-CAkey', str(key), '-CAcreateserial',
            '-out', str(DIRECTORY / 'server.pem'), '-not_before', stamp(now - timedelta(days=30)),
            '-not_after', stamp(now + timedelta(days=int(LEAF_DAYS))), '-extfile', str(extensions))
    server_key.chmod(0o600)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rotate', action='store_true', help='Replace the existing CA and leaf; fixture APKs must then be rebuilt')
    parser.add_argument('--reissue-leaf', action='store_true', help='Replace only the server leaf; fixture APKs embed the CA, so they stay valid')
    args = parser.parse_args()
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if args.reissue_leaf:
        leaf(DIRECTORY / 'ca.pem', DIRECTORY / 'ca-key.pem')
        print('Fixture server leaf reissued.')
        return
    if (DIRECTORY / 'ca.pem').exists():
        if not args.rotate:
            raise SystemExit('Fixture CA already exists; fixture APKs embed it. To replace it, run with --rotate '
                             'and then rebuild every fixture APK (./build.sh update-fixture).')
        for name in GENERATED:
            (DIRECTORY / name).unlink(missing_ok=True)
    ca = DIRECTORY / 'ca.pem'
    key = DIRECTORY / 'ca-key.pem'
    command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(ca),
            '-days', CA_DAYS, '-subj', '/CN=Local Update Test CA', '-addext', 'basicConstraints=critical,CA:TRUE')
    leaf(ca, key)
    key.chmod(0o600)
    print('Local fixture certificates created; never publish internal/ artifacts.')

if __name__ == '__main__': main()
