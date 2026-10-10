"""Hills along a route: total climb, steepest stretch, and a plain-words verdict ("평지",
"완만한 오르막", "언덕 코스").

The height map is in places.db (table elevation_blocks, built on the dev PC by
tools/elevation/build_elevation.py from the Copernicus DEM GLO-30): 0.1-degree blocks of
2-arc-second cells (~60 m), heights in decimetres as int16, row 0 at the north edge. Heights are
interpolated between cells; no outside calls.

Source credit: Copernicus DEM GLO-30, © DLR e.V. 2010-2014 and © Airbus Defence and Space
GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA.
"""

import functools
import math
import sqlite3
import struct
import zlib

import places

SOURCE = "Copernicus DEM (ESA)"
CELLS_PER_DEG = 1800        # 2 arc seconds
BLOCK = 180                 # cells a side in a 0.1-degree block
STEP_M = 30.0
NOISE_M = 3.0               # climbs smaller than this between turning points are ignored


@functools.lru_cache(maxsize=128)
def _block(key: str):
    try:
        conn = sqlite3.connect(places.db_path())
        try:
            r = conn.execute("SELECT heights FROM elevation_blocks WHERE key = ?", (key,)).fetchone()
        finally:
            conn.close()
    except sqlite3.OperationalError:
        return None
    if not r:
        return None
    raw = zlib.decompress(r[0])
    return struct.unpack(f"<{BLOCK * BLOCK}h", raw)


def _cell(by: int, bx: int, r: int, c: int):
    """Height (m) of cell (r, c) of block (by, bx); rows/cols past the edge go to the neighbour."""
    while r < 0:
        by += 1
        r += BLOCK
    while r >= BLOCK:
        by -= 1
        r -= BLOCK
    while c < 0:
        bx -= 1
        c += BLOCK
    while c >= BLOCK:
        bx += 1
        c -= BLOCK
    h = _block(f"{by}_{bx}")
    if h is None:
        return None
    v = h[r * BLOCK + c]
    return None if v == -32768 else v / 10


def height(lat: float, lon: float) -> float | None:
    """Ground height (m) at (lat, lon), interpolated between the four nearest cell centres."""
    by, bx = math.floor(lat * 10), math.floor(lon * 10)
    fr = ((by + 1) / 10 - lat) * CELLS_PER_DEG - 0.5
    fc = (lon - bx / 10) * CELLS_PER_DEG - 0.5
    r0, c0 = math.floor(fr), math.floor(fc)
    tr, tc = fr - r0, fc - c0
    vals = [_cell(by, bx, r0 + dr, c0 + dc) for dr in (0, 1) for dc in (0, 1)]
    if any(v is None for v in vals):
        known = [v for v in vals if v is not None]
        return sum(known) / len(known) if known else None
    top = vals[0] * (1 - tc) + vals[1] * tc
    bottom = vals[2] * (1 - tc) + vals[3] * tc
    return top * (1 - tr) + bottom * tr


def has_map() -> bool:
    return _block("375_1269") is not None  # central Seoul


def profile(points) -> list[tuple[float, float]]:
    """(distance along, height) every STEP_M metres."""
    out = []
    along = 0.0
    for a, b in zip(points, points[1:]):
        seg = places.distance_m(a[0], a[1], b[0], b[1])
        n = max(1, int(seg / STEP_M))
        for k in range(n):
            t = k / n
            h = height(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            if h is not None:
                out.append((along + seg * t, h))
        along += seg
    if points:
        h = height(*points[-1][:2])
        if h is not None:
            out.append((along, h))
    return out


def summary(points) -> dict | None:
    """{ascent_m, descent_m, max_grade (%), terrain} for a route, or None without the map.

    Ascent counts climbs between turning points that differ by more than NOISE_M, so the
    map's metre-scale wobble doesn't add up over a long flat route. The steepest grade is over
    150 m stretches (a single 60 m cell edge isn't a hill)."""
    prof = profile(points)
    if len(prof) < 2:
        return None
    # The Copernicus DEM is a surface model: it includes trees and buildings, so a path beside
    # them reads spikes of 10-20 m. A running median over ~210 m removes those (a real hill is
    # longer than that), then a short mean smooths the steps between cells.
    raw = [h for _, h in prof]
    med = [sorted(raw[max(0, i - 3):i + 4])[len(raw[max(0, i - 3):i + 4]) // 2] for i in range(len(raw))]
    hs = [med[0]] + [(med[i - 1] + med[i] + med[i + 1]) / 3 for i in range(1, len(med) - 1)] + [med[-1]]
    ascent = descent = 0.0
    ref = hs[0]
    for h in hs[1:]:
        if h - ref > NOISE_M:
            ascent += h - ref
            ref = h
        elif ref - h > NOISE_M:
            descent += ref - h
            ref = h
    steepest = 0.0
    j = 0
    for i in range(len(prof)):
        while j < len(prof) - 1 and prof[j][0] - prof[i][0] < 150:
            j += 1
        span = prof[j][0] - prof[i][0]
        if span >= 120:
            steepest = max(steepest, abs(hs[j] - hs[i]) / span * 100)
    length_km = prof[-1][0] / 1000 or 1
    climb_per_km = ascent / length_km
    if climb_per_km < 8 and steepest < 4:
        terrain = "평지"
    elif climb_per_km < 20 and steepest < 8:
        terrain = "완만한 오르막이 조금 있어요"
    else:
        terrain = "오르막이 많은 언덕 코스예요"
    return {"ascent_m": round(ascent), "descent_m": round(descent), "max_grade": round(steepest, 1),
            "terrain": terrain}
