import runpy
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
check_apk = runpy.run_path(str(ROOT / "scripts/verify-native-libraries.py"))["check_apk"]

class NativeAlignmentTest(unittest.TestCase):
    def apk(self, directory, alignment=16384, compression=zipfile.ZIP_DEFLATED):
        elf = bytearray(120)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", elf, 32, 64)
        struct.pack_into("<HH", elf, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", elf, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
        path = Path(directory) / "fixture.apk"
        with zipfile.ZipFile(path, "w", compression=compression) as apk:
            apk.writestr("lib/arm64-v8a/libfixture.so", elf)
        return path

    def test_aligned_elf_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(1, check_apk(self.apk(directory), verbose=False))

    def test_four_kib_elf_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "ELF alignment"):
                check_apk(self.apk(directory, alignment=4096), verbose=False)

    def test_unaligned_uncompressed_apk_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "APK alignment"):
                check_apk(self.apk(directory, compression=zipfile.ZIP_STORED), verbose=False)

    def test_universal_apk_cannot_have_an_abi_without_its_encoder(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.apk(directory)
            with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_DEFLATED) as apk:
                apk.writestr("lib/arm64-v8a/liblxmp3.so", b"fixture")
                apk.writestr("lib/arm64-v8a/libmp3lame.so", b"fixture")
                apk.writestr("lib/x86_64/libquickjs-android-wrapper.so", b"fixture")
            with self.assertRaisesRegex(ValueError, "Missing encoder ABI: x86_64"):
                check_apk(path, verbose=False)
