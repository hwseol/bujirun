"""Builds the height map in places.db from the Copernicus DEM GLO-30 (backend/elevation.py reads
it). Runs on the dev PC (numpy + tifffile).

    python -P build_elevation.py <dir with Copernicus_DSM_COG_10_*_DEM.tif> <places.db>

Each tile is 1x1 degree of 1-arc-second (~30 m) float32 heights, deflate-compressed with the
floating-point predictor (TIFF predictor 3), which tifffile can't undo without imagecodecs - so
it's undone here: per row, the bytes were differenced and split into byte planes, most
significant first. Cells here are 2x2 of those (2 arc seconds, ~60 m), averaged, stored as int16
decimetres in 0.1-degree blocks (180 x 180, row 0 north), zlib-compressed, only for blocks that
already have a shade block (i.e. land in South Korea with places).

Credit: Copernicus DEM GLO-30, © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH
2014-2018, provided under COPERNICUS by the European Union and ESA.
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

PX = 3600       # pixels per degree (1 arc second)
BLOCK_PX = 360  # 0.1 degree
CELL = 2        # pixels per stored cell side


def decode_tile(fh, page, i):
    """One 1024x1024 tile of float32 heights, undoing deflate + floating-point predictor."""
    fh.seek(page.dataoffsets[i])
    raw = np.frombuffer(zlib.decompress(fh.read(page.databytecounts[i])), dtype=np.uint8)
    h, w = page.tilelength, page.tilewidth
    rows = raw.reshape(h, w * 4)
    rows = np.cumsum(rows, axis=1, dtype=np.uint32).astype(np.uint8)     # undo byte differencing
    planes = rows.reshape(h, 4, w)                                        # MSB plane first
    be = np.stack([planes[:, 0], planes[:, 1], planes[:, 2], planes[:, 3]], axis=-1)
    return be.reshape(h, w * 4).view(">f4").astype(np.float32)


def read_tile(path):
    with tifffile.TiffFile(path) as t:
        page = t.pages[0]
        assert page.compression == 8 and page.predictor == 3, (page.compression, page.predictor)
        across = math.ceil(page.shape[1] / page.tilewidth)
        down = math.ceil(page.shape[0] / page.tilelength)
        out = np.zeros((down * page.tilelength, across * page.tilewidth), dtype=np.float32)
        with open(path, "rb") as fh:
            for ty in range(down):
                for tx in range(across):
                    out[ty * page.tilelength:(ty + 1) * page.tilelength,
                        tx * page.tilewidth:(tx + 1) * page.tilewidth] = decode_tile(fh, page, ty * across + tx)
        return out[:page.shape[0], :page.shape[1]]


def main():
    db = sqlite3.connect(sys.argv[2])
    db.execute("CREATE TABLE IF NOT EXISTS elevation_blocks (key TEXT PRIMARY KEY, heights BLOB NOT NULL)")
    db.execute("DELETE FROM elevation_blocks")
    wanted = {k for (k,) in db.execute("SELECT key FROM shade_blocks")}
    stored = 0
    for path in sorted(glob.glob(os.path.join(sys.argv[1], "Copernicus_DSM_COG_10_*_DEM.tif"))):
        m = re.search(r"_N(\d+)_00_E(\d+)_00_", path)
        lat0, lon0 = int(m.group(1)), int(m.group(2))
        z = read_tile(path)
        if z.shape != (PX, PX):
            print(f"skip {path}: shape {z.shape}")
            continue
        for y in range(lat0 * 10, lat0 * 10 + 10):
            for x in range(lon0 * 10, lon0 * 10 + 10):
                if f"{y}_{x}" not in wanted:
                    continue
                r0 = (lat0 + 1) * PX - (y + 1) * BLOCK_PX  # rows from the tile's north edge
                c0 = x * BLOCK_PX - lon0 * PX
                part = z[r0:r0 + BLOCK_PX, c0:c0 + BLOCK_PX]
                cells = part.reshape(BLOCK_PX // CELL, CELL, BLOCK_PX // CELL, CELL).mean(axis=(1, 3))
                dm = np.clip(np.round(cells * 10), -32000, 32000).astype("<i2")
                db.execute("INSERT OR REPLACE INTO elevation_blocks VALUES (?, ?)", (f"{y}_{x}", zlib.compress(dm.tobytes(), 9)))
                stored += 1
        db.commit()
        print(f"{os.path.basename(path)}: min {z.min():.0f} m, max {z.max():.0f} m; {stored} blocks so far")


if __name__ == "__main__":
    main()
