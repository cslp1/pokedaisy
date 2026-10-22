#!/usr/bin/env python3
"""Download the unmodified official Azahar Android core, pinned by release and SHA-256."""
import hashlib, io, pathlib, urllib.request, zipfile
VERSION = '2126.2'
SHA256 = '1998ccf0a1f803cd66ff90a36280c64d3ff915b48acf1c4b5b0e8fd2b263faf3'
CORE_SHA256 = 'c2d1e3415f132a627eb5d0f66dc4cb0dd8304c95a6725bad357d32c116d6c226'
DEST = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/jniLibs/arm64-v8a/libazahar_libretro.so'
if DEST.is_file() and hashlib.sha256(DEST.read_bytes()).hexdigest() == CORE_SHA256:
    print('Official Azahar core already verified.')
else:
    url = f'https://github.com/azahar-emu/azahar/releases/download/{VERSION}/azahar-libretro-android-arm64-v8a-{VERSION}.zip'
    data = urllib.request.urlopen(url, timeout=120).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('Official Azahar download checksum mismatch.')
    core = zipfile.ZipFile(io.BytesIO(data)).read('azahar_libretro.so')
    if hashlib.sha256(core).hexdigest() != CORE_SHA256:
        raise SystemExit('Azahar library checksum mismatch.')
    DEST.parent.mkdir(parents=True, exist_ok=True)
    DEST.write_bytes(core)
    print('Official Azahar Android core installed and verified.')
