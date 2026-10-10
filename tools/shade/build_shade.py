"""Builds the tree-shade map in places.db from ESA WorldCover 2021 (docs/SHADE.md). Runs on the
dev PC (needs numpy and tifffile); the server only reads the result (backend/shade.py).

    python -P build_shade.py <dir with ESA_WorldCover_10m_2021_v200_*_Map.tif> <places.db>

WorldCover is 1/12000 degree (~9 m) per pixel, class 10 = tree cover. Cells here are 2 x 2
pixels (1/6000 degree, ~18 m), shaded when at least two of the four are trees. They are stored
as bitmaps of 0.1 x 0.1 degree blocks (600 x 600 cells, row 0 at the north edge, bits packed
most significant first), zlib-compressed, one row per block that has any tree. Only blocks that
overlap a place in places.db are kept: the tiles also cover North Korea, China and Japan.

Source credit: ESA WorldCover 2021 (CC BY 4.0).
"""

import glob
import math
import os
import re
import sqlite3
import sys
import zlib

import numpy as np
import tifffile

PX_PER_DEG = 12000
CELLS_PER_DEG = 6000
BLOCK_DEG = 0.1
TREE = 10


class Tile:
    """One WorldCover GeoTIFF, decoded 1024 x 1024 at a time (the whole tile is 1.3 GB)."""

    def __init__(self, path):
        m = re.search(r"_([NS])(\d+)([EW])(\d+)_Map", path)
        self.south = int(m.group(2)) * (1 if m.group(1) == "N" else -1)
        self.west = int(m.group(4)) * (1 if m.group(3) == "E" else -1)
        self.north = self.south + 3
        self.file = tifffile.TiffFile(path)
        self.page = self.file.pages[0]
        assert self.page.compression == 8 and self.page.predictor == 1, "expected plain deflate"
        self.tw, self.th = self.page.tilewidth, self.page.tilelength
        self.across = math.ceil(self.page.shape[1] / self.tw)
        self.fh = open(path, "rb")
        self.cache = {}

    def _tile(self, ty, tx):
        key = (ty, tx)
        if key not in self.cache:
            if len(self.cache) > 80:
                self.cache.clear()
            i = ty * self.across + tx
            self.fh.seek(self.page.dataoffsets[i])
            raw = zlib.decompress(self.fh.read(self.page.databytecounts[i]))
            self.cache[key] = np.frombuffer(raw, dtype=np.uint8).reshape(self.th, self.tw)
        return self.cache[key]

    def window(self, row0, col0, h, w):
        out = np.zeros((h, w), dtype=np.uint8)
        for ty in range(row0 // self.th, (row0 + h - 1) // self.th + 1):
            for tx in range(col0 // self.tw, (col0 + w - 1) // self.tw + 1):
                t = self._tile(ty, tx)
                r0, c0 = ty * self.th, tx * self.tw
                rs, re_ = max(row0, r0), min(row0 + h, r0 + self.th)
                cs, ce = max(col0, c0), min(col0 + w, c0 + self.tw)
                if rs < re_ and cs < ce:
                    out[rs - row0:re_ - row0, cs - col0:ce - col0] = t[rs - r0:re_ - r0, cs - c0:ce - c0]
        return out


def korea_blocks(db):
    """Block keys (lat*10, lon*10 floored) overlapped by some place's bounding box."""
    keys = set()
    for a, b, c, d in db.execute("SELECT min_lat, max_lat, min_lon, max_lon FROM places"):
        for y in range(math.floor(a * 10), math.floor(b * 10) + 1):
            for x in range(math.floor(c * 10), math.floor(d * 10) + 1):
                keys.add((y, x))
    return keys


def main():
    tiles = [Tile(p) for p in sorted(glob.glob(os.path.join(sys.argv[1], "ESA_WorldCover_10m_2021_v200_*_Map.tif")))]
    db = sqlite3.connect(sys.argv[2])
    db.execute("CREATE TABLE IF NOT EXISTS shade_blocks (key TEXT PRIMARY KEY, bits BLOB NOT NULL)")
    db.execute("DELETE FROM shade_blocks")
    wanted = korea_blocks(db)
    stored = trees = 0
    px = int(PX_PER_DEG * BLOCK_DEG)  # 1200 pixels a side
    for t in tiles:
        for y in range(t.south * 10, t.north * 10):
            for x in range(t.west * 10, (t.west + 3) * 10):
                if (y, x) not in wanted:
                    continue
                row0 = round((t.north - (y + 1) / 10) * PX_PER_DEG)
                col0 = round((x / 10 - t.west) * PX_PER_DEG)
                w = t.window(row0, col0, px, px) == TREE
                cells = w.reshape(px // 2, 2, px // 2, 2).sum(axis=(1, 3)) >= 2
                if not cells.any():
                    continue
                db.execute("INSERT OR REPLACE INTO shade_blocks VALUES (?, ?)",
                           (f"{y}_{x}", zlib.compress(np.packbits(cells).tobytes(), 9)))
                stored += 1
                trees += int(cells.sum())
        db.commit()
        print(f"{os.path.basename(t.file.filehandle.path)}: {stored} blocks so far")
    print(f"{stored} blocks, {trees * (1 / CELLS_PER_DEG * 111_195) ** 2 * 0.8 / 1e6:.0f} km2 of tree cover (approx.)")


if __name__ == "__main__":
    main()
