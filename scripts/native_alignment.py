"""Check 64-bit native ELF LOAD alignment without installing an NDK.

ZIP alignment is checked separately with the SDK's zipalign. Device tests also
load the actual native library; neither static check substitutes for execution.
"""
import struct
import zipfile

PAGE = 16384


def elf_load_alignment(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian 64-bit ELF library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or offset + entry_size * count > len(data):
        raise ValueError("Invalid ELF program headers")
    loads = []
    for index in range(count):
        kind, flags, file_offset, address, physical, file_size, memory_size, alignment = \
            struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
        if kind == 1:  # PT_LOAD
            if alignment < PAGE or alignment & (alignment - 1) or (address - file_offset) % PAGE:
                raise ValueError("Native LOAD segment is not 16 KB aligned")
            loads.append(alignment)
    if not loads:
        raise ValueError("Native library has no LOAD segments")
    return loads


def inspect_apk(apk):
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith(".so") and name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                try:
                    loads = elf_load_alignment(archive.read(name))
                except ValueError as error:
                    raise ValueError(name + ": " + str(error)) from error
                libraries.append({"library": name, "load_alignments": loads})
    return libraries
