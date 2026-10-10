"""python -m unittest test_shade"""

import os
import sqlite3
import tempfile
import unittest
import zlib
from datetime import datetime, timezone

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import places  # noqa: E402
import shade  # noqa: E402

CELL = 1 / 6000


def put_block(key: str, tree_rows: range):
    """A 600 x 600 block with trees on [tree_rows] (row 0 = north edge)."""
    bits = bytearray(600 * 600 // 8)
    for r in tree_rows:
        for c in range(600):
            i = r * 600 + c
            bits[i >> 3] |= 0x80 >> (i & 7)
    conn = sqlite3.connect(places.db_path())
    conn.execute("CREATE TABLE IF NOT EXISTS shade_blocks (key TEXT PRIMARY KEY, bits BLOB NOT NULL)")
    conn.execute("INSERT OR REPLACE INTO shade_blocks VALUES (?, ?)", (key, zlib.compress(bytes(bits))))
    conn.commit()
    conn.close()
    shade._block.cache_clear()


class ShadeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        places.init_db()
        # Block 37.5..37.6 N, 127.0..127.1 E: trees on its northern half only.
        put_block("375_1270", range(0, 300))
        put_block("375_1269", range(0))  # an empty block where has_map() looks

    def test_tree_cells(self):
        self.assertTrue(shade.tree(37.59, 127.05))
        self.assertFalse(shade.tree(37.51, 127.05))
        self.assertFalse(shade.tree(36.0, 128.0))  # no block: no trees
        self.assertTrue(shade.has_map())

    def test_a_path_through_the_wood_is_shade_but_the_road_beside_it_is_not(self):
        # A one-cell gap in the canopy (a path through the trees) has trees all around it.
        put_block("376_1270", range(0, 600))
        conn = sqlite3.connect(places.db_path())
        bits = bytearray(zlib.decompress(conn.execute("SELECT bits FROM shade_blocks WHERE key = '376_1270'").fetchone()[0]))
        i = 300 * 600 + 300
        bits[i >> 3] &= ~(0x80 >> (i & 7)) & 0xFF
        conn.execute("UPDATE shade_blocks SET bits = ? WHERE key = '376_1270'", (zlib.compress(bytes(bits)),))
        conn.commit()
        conn.close()
        shade._block.cache_clear()
        gap = (37.7 - 300.5 * CELL, 127.0 + 300.5 * CELL)
        self.assertFalse(shade.tree(*gap))
        self.assertTrue(shade.shaded(*gap))
        # The row just south of the canopy's edge has trees on one side only.
        edge = 37.55 - 0.5 * CELL
        self.assertFalse(shade.shaded(edge, 127.05))

    def test_ratio_is_the_share_of_length_under_trees(self):
        route = [[37.54, 127.05], [37.56, 127.05]]  # half south of 37.55, half north
        self.assertAlmostEqual(shade.ratio(route), 0.5, delta=0.05)
        self.assertEqual(shade.ratio([[37.51, 127.01], [37.52, 127.01]]), 0.0)

    def test_canopy_of_an_outline(self):
        north_half = [[[37.55, 127.02], [37.55, 127.04], [37.59, 127.04], [37.59, 127.02], [37.55, 127.02]]]
        self.assertGreater(shade.canopy(north_half), 0.9)

    def test_sun_in_seoul(self):
        noon_june = datetime(2026, 6, 21, 3, 30, tzinfo=timezone.utc)   # 12:30 KST
        noon_dec = datetime(2026, 12, 21, 3, 30, tzinfo=timezone.utc)
        e, a = shade.sun(37.57, 126.98, noon_june)
        self.assertAlmostEqual(e, 76, delta=1.5)
        self.assertAlmostEqual(a, 180, delta=15)
        self.assertAlmostEqual(shade.sun(37.57, 126.98, noon_dec)[0], 29, delta=1.5)
        evening = datetime(2026, 6, 21, 10, 0, tzinfo=timezone.utc)      # 19:00 KST
        self.assertLess(shade.sun(37.57, 126.98, evening)[0], 15)
        self.assertTrue(shade.strong_sun(37.57, 126.98, noon_june))
        self.assertFalse(shade.strong_sun(37.57, 126.98, noon_dec))


if __name__ == "__main__":
    unittest.main()
