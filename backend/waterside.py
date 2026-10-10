"""The path right by the water, for courses along a river or stream.

Given only two points, TMAP takes the straightest walkable way between them - often the road a
block back from the stream, not the path by the water that people actually run. And our river
lines (places.db) are too coarse in places to steer it with points "just off the centreline".
So for the stretch of river a course uses, we fetch the paths and the water itself from
OpenStreetMap, keep the paths within a few dozen metres of the water, and route along those.

One Overpass query per ~1 km tile group, cached for a month; nothing for the routing itself.
Data © OpenStreetMap contributors, ODbL.
"""

import json
import math
import os
import sqlite3
import time
from pathlib import Path

import landmarks
import osm
import parkpaths

NEAR_M = 40          # a path this close to the water counts as "by the water"
CACHE_S = 30 * 86400
M = 111_195.0


def _db():
    path = Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "waterside.db"
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE IF NOT EXISTS areas (key TEXT PRIMARY KEY, at REAL, elements TEXT)")
    return db


def query(s, w, n, e) -> str:
    box = f"{s:.4f},{w:.4f},{n:.4f},{e:.4f}"
    return f"""[out:json][timeout:25];
(
  way["highway"~"^(footway|path|cycleway|pedestrian|track|living_street)$"]({box});
  way["waterway"~"^(river|stream|canal)$"]({box});
  way["natural"="water"]({box}); relation["natural"="water"]({box});
  way["waterway"="riverbank"]({box});
);
out geom({box});"""


def elements_around(points, fetch=None) -> list | None:
    """OSM paths and water around [points] (padded 300 m, snapped to a 0.01 degree grid so nearby
    requests share the cache), or None if Overpass can't be reached."""
    pad = 0.003
    s = math.floor((min(p[0] for p in points) - pad) * 100) / 100
    w = math.floor((min(p[1] for p in points) - pad) * 100) / 100
    n = math.ceil((max(p[0] for p in points) + pad) * 100) / 100
    e = math.ceil((max(p[1] for p in points) + pad) * 100) / 100
    if (n - s) * (e - w) > 0.0025:  # over ~5 x 4.5 km: too big a query for a course stretch
        return None
    key = f"{s:.2f},{w:.2f},{n:.2f},{e:.2f}"
    db = _db()
    try:
        row = db.execute("SELECT at, elements FROM areas WHERE key = ?", (key,)).fetchone()
        if row and time.time() - row[0] < CACHE_S:
            return json.loads(row[1])
        els = landmarks._overpass(query(s, w, n, e), fetch)
        if els is not None:
            db.execute("INSERT OR REPLACE INTO areas VALUES (?, ?, ?)", (key, time.time(), json.dumps(els)))
            db.commit()
        return els
    finally:
        db.close()


def _seg_dist(p, a, b) -> float:
    kx = M * math.cos(math.radians(p[0]))
    ax, ay = (a[1] - p[1]) * kx, (a[0] - p[0]) * M
    bx, by = (b[1] - p[1]) * kx, (b[0] - p[0]) * M
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


class Water:
    """The water's lines and outlines, on a 100 m grid for quick "how far is the water"."""

    def __init__(self, elements):
        self.grid: dict = {}
        for el in elements:
            t = el.get("tags", {})
            if not ("waterway" in t or t.get("natural") == "water"):
                continue
            for line in osm.lines_of(el):
                for a, b in zip(line, line[1:]):
                    for cell in self._cells(a, b):
                        self.grid.setdefault(cell, []).append((a, b))

    @staticmethod
    def _cells(a, b):
        steps = max(1, int(parkpaths._dist(a, b) / 50))
        out = set()
        for k in range(steps + 1):
            lat = a[0] + (b[0] - a[0]) * k / steps
            lon = a[1] + (b[1] - a[1]) * k / steps
            out.add((int(lat * 1000), int(lon * 1000)))
        return out

    def distance(self, p) -> float:
        cx, cy = int(p[0] * 1000), int(p[1] * 1000)
        best = math.inf
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for a, b in self.grid.get((cx + dx, cy + dy), ()):
                    best = min(best, _seg_dist(p, a, b))
        return best


def graph(elements) -> parkpaths.PathGraph | None:
    """The paths by the water, as a graph (every piece kept: each bank can be its own)."""
    water = Water(elements)
    if not water.grid:
        return None
    pieces = []
    for el in elements:
        t = el.get("tags", {})
        if "highway" not in t:
            continue
        line = [[round(p["lat"], 6), round(p["lon"], 6)] for p in el.get("geometry") or [] if p]
        for a, b in zip(line, line[1:]):
            mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
            if water.distance(mid) <= NEAR_M:
                pieces.append([a, b])
    return parkpaths.PathGraph(pieces, keep_largest=False) if pieces else None


def _component(g, start) -> set:
    comp, stack = {start}, [start]
    while stack:
        for m in g.adj[stack.pop()]:
            if m not in comp:
                comp.add(m)
                stack.append(m)
    return comp


def along(points, start=None, fetch=None, within_m: float = 150) -> list | None:
    """A route by the water through [points] in order (entry, ..., turn), or None if there's no
    connected waterside path for it - the caller then asks TMAP. [start]: where the runner comes
    from - the waterside path nearest it is the bank to use (the river lines are too rough to
    tell the banks apart reliably)."""
    els = elements_around(points, fetch)
    if not els:
        return None
    g = graph(els)
    if g is None:
        return None
    # Where the course meets the water: of the waterside paths there, the one nearest the runner
    # (their bank).
    near_entry = [n for n in g.adj if parkpaths._dist(n, points[0]) <= within_m]
    if not near_entry:
        return None
    first = min(near_entry, key=lambda n: parkpaths._dist(n, start or points[0]))
    # Only what's joined to that path (a bridge joins the far bank too, which is fine).
    comp = _component(g, first)
    nodes = [first]
    for p in points[1:]:
        n = min(comp, key=lambda k: parkpaths._dist(k, p))
        if parkpaths._dist(n, p) > within_m:
            return None
        if n != nodes[-1]:
            nodes.append(n)
    if len(nodes) < 2:
        return None
    out = []
    for a, b in zip(nodes, nodes[1:]):
        part = g.shortest(a, b)
        if not part:
            return None
        out += part[1:] if out else part
    return [list(n) for n in parkpaths._drop_backtracks(out)]
