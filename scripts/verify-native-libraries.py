"""Check 16 KB ELF segments and APK storage alignment, without external programs."""
import struct
import sys
import zipfile

def require(condition, message):
    if not condition:
        raise ValueError(message)

def check_apk(path, verbose=True):
    checked = 0
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        libraries = {name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if any(name.endswith("/liblxmp3.so") for name in libraries):
            for abi in {name.split("/")[1] for name in libraries}:
                for library in ("liblxmp3.so", "libmp3lame.so"):
                    require(f"lib/{abi}/{library}" in libraries, f"Missing encoder ABI: {abi}/{library}")
        for item in apk.infolist():
            if not item.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not item.filename.endswith(".so"):
                continue
            data = apk.read(item)
            require(data[:6] == b"\x7fELF\x02\x01", item.filename)
            phoff = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                segment = struct.unpack_from("<IIQQQQQQ", data, phoff + index * size)
                if segment[0] == 1:
                    require(segment[-1] >= 16384 and (segment[3] - segment[2]) % 16384 == 0, f"ELF alignment: {item.filename}")
            if item.compress_type == zipfile.ZIP_STORED:
                raw.seek(item.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                require((item.header_offset + 30 + name_size + extra_size) % 16384 == 0, f"APK alignment: {item.filename}")
            if verbose:
                print(f"16 KB aligned: {item.filename}")
            checked += 1
    require(checked, "No 64-bit native libraries checked")
    return checked

if __name__ == "__main__":
    for path in sys.argv[1:]:
        check_apk(path)
