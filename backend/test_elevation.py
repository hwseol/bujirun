"""python -m unittest test_elevation"""

import os
import sqlite3
import struct
import tempfile
import unittest
import zlib

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import elevation  # noqa: E402
import places  # noqa: E402

CELL = 1 / 1800


def put_block(key: str, height_of):
    """A 180 x 180 block; height_of(row, col) in metres (row 0 = north edge)."""
    vals = [round(height_of(r, c) * 10) for r in range(180) for c in range(180)]
    conn = sqlite3.connect(places.db_path())
    conn.execute("CREATE TABLE IF NOT EXISTS elevation_blocks (key TEXT PRIMARY KEY, heights BLOB NOT NULL)")
    conn.execute("INSERT OR REPLACE INTO elevation_blocks VALUES (?, ?)",
                 (key, zlib.compress(struct.pack(f"<{len(vals)}h", *vals))))
    conn.commit()
    conn.close()
    elevation._block.cache_clear()


class ElevationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        places.init_db()
        put_block("375_1269", lambda r, c: 20)                 # flat ground (central Seoul key)
        # 37.5..37.6 N, 127.0..127.1 E: flat at 20 m in the south half; a hill in the north
        # half rising 1 m per cell (~62 m) toward the north edge: ~1.6% grade.
        put_block("375_1270", lambda r, c: 20 if r >= 90 else 20 + (90 - r))

    def test_heights_interpolate_between_cells(self):
        self.assertAlmostEqual(elevation.height(37.51, 127.05), 20, delta=0.01)
        north = elevation.height(37.6 - 10.5 * CELL, 127.05)  # centre of row 10
        self.assertAlmostEqual(north, 100, delta=0.5)
        between = elevation.height(37.6 - 11 * CELL, 127.05)  # halfway to row 11
        self.assertAlmostEqual(between, 99.5, delta=0.5)
        self.assertIsNone(elevation.height(36.0, 128.0))

    def test_flat_route_is_flat(self):
        s = elevation.summary([[37.51, 127.01], [37.52, 127.05]])
        self.assertEqual(s["ascent_m"], 0)
        self.assertEqual(s["terrain"], "평지")

    def test_climb_up_the_hill_and_back(self):
        up = [[37.55, 127.05], [37.59, 127.05]]       # 4.4 km north, rising ~65 m
        s = elevation.summary(up + up[::-1])
        self.assertAlmostEqual(s["ascent_m"], 65, delta=8)
        self.assertAlmostEqual(s["descent_m"], 65, delta=8)
        self.assertAlmostEqual(s["max_grade"], 1.6, delta=0.4)

    def test_steep_short_hill_reads_as_a_hill(self):
        put_block("376_1270", lambda r, c: 20 + max(0, 30 - abs(r - 90)) * 3)  # 90 m dome, ~5%
        dome = [[37.7 - 60 * CELL, 127.05], [37.7 - 120 * CELL, 127.05]]
        s = elevation.summary(dome)
        self.assertGreater(s["max_grade"], 4)
        self.assertEqual(s["terrain"], "오르막이 많은 언덕 코스예요")


if __name__ == "__main__":
    unittest.main()
