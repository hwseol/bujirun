"""Tree shade along a route, and where the sun is (docs/SHADE.md).

The shade map is in places.db (table shade_blocks, built on the dev PC by
tools/shade/build_shade.py from ESA WorldCover 2021, CC BY 4.0): 0.1-degree blocks of
~18 m cells, a bit per cell, 1 = tree cover. Reading it is a few bit lookups per route point;
no outside calls.
"""

import functools
import math
import sqlite3
import zlib
from datetime import datetime, timezone

import places

SOURCE = "ESA WorldCover 2021"
CELLS_PER_DEG = 6000
BLOCK_CELLS = 600
STEP_M = 10.0


@functools.lru_cache(maxsize=256)
def _block(key: str) -> bytes | None:
    try:
        conn = sqlite3.connect(places.db_path())
        try:
            r = conn.execute("SELECT bits FROM shade_blocks WHERE key = ?", (key,)).fetchone()
        finally:
            conn.close()
    except sqlite3.OperationalError:  # no shade map loaded
        return None
    return zlib.decompress(r[0]) if r else None


def tree(lat: float, lon: float) -> bool:
    """Whether the ~18 m cell at (lat, lon) is under trees."""
    by, bx = math.floor(lat * 10), math.floor(lon * 10)
    bits = _block(f"{by}_{bx}")
    if bits is None:
        return False
    r = min(BLOCK_CELLS - 1, int(((by + 1) / 10 - lat) * CELLS_PER_DEG))
    c = min(BLOCK_CELLS - 1, int((lon - bx / 10) * CELLS_PER_DEG))
    i = r * BLOCK_CELLS + c
    return bool(bits[i >> 3] & (0x80 >> (i & 7)))


def shaded(lat: float, lon: float) -> bool:
    """A point counts as shaded when its own cell is under trees, or at least four of the eight
    around it are: a path through a wood often shows as a gap in the canopy (it is one), while a
    road along the edge of a park has trees on one side only. (Two neighbours counted half the
    open Han river parks as shade; four matched what's on the ground in Seoul samples.)"""
    if tree(lat, lon):
        return True
    d = 1 / CELLS_PER_DEG
    around = sum(tree(lat + dy * d, lon + dx * d) for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx)
    return around >= 4


def ratio(points) -> float:
    """Share (0..1) of the route's length that is shaded, checked every STEP_M metres."""
    total = shade = 0.0
    for a, b in zip(points, points[1:]):
        seg = places.distance_m(a[0], a[1], b[0], b[1])
        n = max(1, int(seg / STEP_M))
        for k in range(n):
            t = (k + 0.5) / n
            if shaded(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t):
                shade += seg / n
        total += seg
    return shade / total if total else 0.0


def canopy(rings, samples: int = 12) -> float:
    """Share (0..1) of an outline's inside that is under trees, from a grid of sample points."""
    if not rings:
        return 0.0
    lats = [p[0] for r in rings for p in r]
    lons = [p[1] for r in rings for p in r]
    inside = covered = 0
    for i in range(samples):
        for j in range(samples):
            lat = min(lats) + (max(lats) - min(lats)) * (i + 0.5) / samples
            lon = min(lons) + (max(lons) - min(lons)) * (j + 0.5) / samples
            if any(places._inside(lat, lon, r) for r in rings):
                inside += 1
                covered += tree(lat, lon)
    return covered / inside if inside else 0.0


def has_map() -> bool:
    return _block("375_1269") is not None  # central Seoul


# --- the sun ---------------------------------------------------------------------------------

def sun(lat: float, lon: float, when: datetime | None = None) -> tuple[float, float]:
    """(elevation, azimuth) in degrees at [when] (UTC; default now). Azimuth clockwise from
    north. NOAA's simplified solar position formulas: good to well under a degree."""
    when = (when or datetime.now(timezone.utc)).astimezone(timezone.utc)
    day = when.timetuple().tm_yday
    hour = when.hour + when.minute / 60 + when.second / 3600
    g = 2 * math.pi / 365 * (day - 1 + (hour - 12) / 24)
    eqtime = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g)
                       - 0.014615 * math.cos(2 * g) - 0.040849 * math.sin(2 * g))
    decl = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g)
            + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    solar_min = hour * 60 + eqtime + 4 * lon
    ha = math.radians(solar_min / 4 - 180)
    la = math.radians(lat)
    cos_zen = math.sin(la) * math.sin(decl) + math.cos(la) * math.cos(decl) * math.cos(ha)
    zen = math.acos(max(-1.0, min(1.0, cos_zen)))
    az = math.degrees(math.atan2(math.sin(ha), math.cos(ha) * math.sin(la) - math.tan(decl) * math.cos(la))) + 180
    return 90 - math.degrees(zen), az % 360


def strong_sun(lat: float, lon: float, when: datetime | None = None) -> bool:
    """Sun high enough that shade matters for a run: 35 degrees or more (late morning to mid
    afternoon in summer; around noon in spring and autumn; never in deep winter)."""
    return sun(lat, lon, when)[0] >= 35
