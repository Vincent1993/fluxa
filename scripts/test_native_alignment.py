import struct
import unittest
from native_alignment import elf_load_alignment


def elf(alignment=16384, address=16384, offset=0):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, offset, address, 0, 0, 0, alignment)
    return data


class NativeAlignmentChecksTest(unittest.TestCase):
    def test_accepts_16k_and_larger_load_alignment(self):
        self.assertEqual(elf_load_alignment(elf()), [16384])
        self.assertEqual(elf_load_alignment(elf(65536)), [65536])

    def test_rejects_4k_non_power_of_two_and_incongruent_segments(self):
        for data in (elf(4096), elf(24576), elf(address=4096), elf(offset=4096)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                elf_load_alignment(data)

    def test_rejects_truncated_or_wrong_format_binaries(self):
        for data in (b"not ELF", elf()[:100], b"\x7fELF\x01\x01" + bytes(120)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                elf_load_alignment(data)


if __name__ == "__main__":
    unittest.main()
