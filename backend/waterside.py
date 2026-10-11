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
import threading
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
    # Prebuilt: per 0.01 degree cell (~1 km), only what along() uses (see prebuild).
    db.execute("CREATE TABLE IF NOT EXISTS cells (key TEXT PRIMARY KEY, elements TEXT)")
    return db


def query(s, w, n, e) -> str:
    box = f"{s:.4f},{w:.4f},{n:.4f},{e:.4f}"
    return f"""[out:json][timeout:25];
(
  way["highway"~"^(footway|path|cycleway|pedestrian|track|living_street)$"]({box});
  way["highway"]["bridge"]["highway"!~"^(motorway|motorway_link|trunk_link)$"]({box});
  way["waterway"~"^(river|stream|canal)$"]({box});
  way["natural"="water"]({box}); relation["natural"="water"]({box});
  way["waterway"="riverbank"]({box});
);
out geom({box});"""


def elements_around(points, fetch=None) -> list | None:
    """OSM paths, bridges (the road bridges are how most streams are crossed - they join the two
    banks' paths) and water around [points] (padded 300 m, snapped to a 0.01 degree grid so
    nearby requests share the cache), or None if Overpass can't be reached."""
    pad = 0.003
    s = math.floor((min(p[0] for p in points) - pad) * 100) / 100
    w = math.floor((min(p[1] for p in points) - pad) * 100) / 100
    n = math.ceil((max(p[0] for p in points) + pad) * 100) / 100
    e = math.ceil((max(p[1] for p in points) + pad) * 100) / 100
    if (n - s) * (e - w) > 0.0025:  # over ~5 x 4.5 km: too big a query for a course stretch
        return None
    # Built ahead of time on the PC (prebuild below) for the rivers people run by: no waiting.
    if (els := _from_cells(s, w, n, e)) is not None:
        return els
    key = f"{s:.2f},{w:.2f},{n:.2f},{e:.2f}"
    db = _db()
    try:
        row = db.execute("SELECT at, elements FROM areas WHERE key = ?", (key,)).fetchone()
        if row and time.time() - row[0] < CACHE_S:
            return json.loads(row[1])
    finally:
        db.close()
    q = query(s, w, n, e)
    els = landmarks._overpass(q, fetch)
    if els is not None:
        _store(key, els)
    elif fetch is None and key not in _fetching:
        # The public Overpass servers are often busy: someone is waiting now, so don't make them
        # wait longer - but keep trying in the background with patience, so the next request
        # for this area (planner.cached_plan doesn't keep this one) gets the waterside path.
        _fetching.add(key)
        threading.Thread(target=_patient_fetch, args=(key, q), daemon=True).start()
    return els


_fetching: set = set()


def fetching() -> bool:
    """Whether some area's paths are still being fetched in the background."""
    return bool(_fetching)


def _store(key, els):
    db = _db()
    try:
        db.execute("INSERT OR REPLACE INTO areas VALUES (?, ?, ?)", (key, time.time(), json.dumps(els)))
        db.commit()
    finally:
        db.close()


def _patient_fetch(key, q):
    try:
        for attempt in range(4):
            for url in landmarks.QUICK_SERVERS:
                try:
                    body = landmarks._fetch(url, q, timeout=60)
                    if body[:1] == b"{":
                        _store(key, json.loads(body)["elements"])
                        return
                except Exception:
                    pass
            time.sleep(15 * (attempt + 1))
    finally:
        _fetching.discard(key)


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

    def bank(self, p, lines) -> int:
        """+1 / -1: which bank of the river [lines] point p is on. OSM draws waterways in the
        direction they flow, so "left of the line" is the same bank all the way along."""
        best, sign = math.inf, 0
        kx = M * math.cos(math.radians(p[0]))
        for line in lines:
            for a, b in zip(line, line[1:]):
                d = _seg_dist(p, a, b)
                if d < best:
                    cross = (b[1] - a[1]) * kx * (p[0] - a[0]) * M - (b[0] - a[0]) * M * (p[1] - a[1]) * kx
                    best, sign = d, (1 if cross > 0 else -1)
        return sign

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


def along(points, start=None, fetch=None, within_m: float = 150, sides=None, river=None) -> list | None:
    """A route by the water through [points] in order (entry, ..., turn), or None if there's no
    connected waterside path for it - the caller then asks TMAP. [start]: where the runner comes
    from - the waterside path nearest it is the bank to use. [sides]: +1 for "the bank the
    course starts on", -1 for the other one, per point - up one bank and back down the other,
    even where a narrow stream puts both within reach of a point. The banks are told apart by
    OSM's own line of the river named [river] (ours in places.db can be off to one side)."""
    els = elements_around(points, fetch)
    if not els:
        return None
    g = graph(els)
    if g is None:
        return None
    side_of = None
    if sides is not None:
        lines = [l for el in els if el.get("tags", {}).get("waterway") and el["tags"].get("name") == river
                 for l in osm.lines_of(el)]
        if not lines:
            return None
        water = Water([])
        side_of = lambda n: water.bank(n, lines)  # noqa: E731

    flip = 1

    def on_side(n, k):
        return sides is None or side_of is None or side_of(n) == sides[k] * flip

    # Where the course meets the water: of the waterside paths there, the one nearest the runner.
    # (Looked for a little wider than the other points: the river line there is only roughly where
    # the water is.) Whichever bank that is becomes "this side" - the start's own bank may have
    # no path there (a side stream joining, say) - and the far points go on the other one.
    near_entry = [n for n in g.adj if parkpaths._dist(n, points[0]) <= max(within_m, 150)]
    # Only paths that go somewhere: a 10 m stub by the water (a viewing deck, a stair) is no
    # way along the river.
    comps: dict = {}
    for n in near_entry:
        if n not in comps:
            c = _component(g, n)
            size = sum(w for a in c for w in g.adj[a].values()) / 2
            comps.update({m: (c, size) for m in c})
    near_entry = [n for n in near_entry if comps[n][1] >= 500]
    if not near_entry:
        return None
    first = min(near_entry, key=lambda n: parkpaths._dist(n, start or points[0]))
    if sides is not None and side_of is not None and side_of(first) != sides[0]:
        flip = -1
    # Only what's joined to that path (a bridge joins the far bank too, which is fine).
    comp = comps[first][0]
    nodes = [first]
    for k, p in enumerate(points[1:], start=1):
        options = [n for n in comp if on_side(n, k)]
        if not options:
            return None
        n = min(options, key=lambda q: parkpaths._dist(q, p))
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


# --- Prebuilt cells ---------------------------------------------------------------------------
# Fetching an area from Overpass while someone waits for a course takes seconds to minutes (the
# public servers are busy). So for the rivers in places.db the paths by the water are fetched
# ahead of time on the PC and shipped in waterside.db, per 0.01 degree cell, cut down to what
# along() uses: path segments within NEAR_M of the water, the water's lines and outlines, and
# the river names. Each piece is stored in the cell of its midpoint, so cells add up without
# overlap.

def _cell_of(lat, lon) -> tuple[int, int]:
    return math.floor(lat * 100), math.floor(lon * 100)


def _from_cells(s, w, n, e) -> list | None:
    """The prebuilt elements for the box (whole cells), or None if any cell isn't built."""
    keys = [f"{i},{j}" for i in range(round(s * 100), round(n * 100)) for j in range(round(w * 100), round(e * 100))]
    if not keys:
        return None
    db = _db()
    try:
        marks = ",".join("?" * len(keys))
        rows = db.execute(f"SELECT elements FROM cells WHERE key IN ({marks})", keys).fetchall()
    finally:
        db.close()
    if len(rows) < len(keys):
        return None
    return [el for (els,) in rows for el in json.loads(els)]


_KEEP_TAGS = ("highway", "bridge", "waterway", "natural", "name")


def _split(a, b, max_m=200):
    """[a, b] cut into pieces no longer than max_m (so a long edge lands in the cells it crosses)."""
    k = max(1, math.ceil(parkpaths._dist(a, b) / max_m))
    pts = [[a[0] + (b[0] - a[0]) * i / k, a[1] + (b[1] - a[1]) * i / k] for i in range(k + 1)]
    return list(zip(pts, pts[1:]))


def cells_from(elements, wanted: set) -> dict:
    """Overpass elements (an area padded beyond the [wanted] cells, so the water near their edges
    is there) as compact per-cell element lists for the wanted cells."""
    water = Water(elements)
    out: dict = {c: [] for c in wanted}
    for el in elements:
        t = el.get("tags", {})
        is_water = "waterway" in t or t.get("natural") == "water"
        if not is_water and "highway" not in t:
            continue
        if is_water:
            lines = osm.lines_of(el)
        else:
            lines = [[[p["lat"], p["lon"]] for p in el.get("geometry") or [] if p]]
        tags = {k: t[k] for k in _KEEP_TAGS if k in t}
        for line in lines:
            runs: dict = {}  # cell -> the line being extended there
            for a, b in zip(line, line[1:]):
                for pa, pb in _split(a, b):
                    mid = [(pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2]
                    cell = _cell_of(*mid)
                    if cell not in wanted or (not is_water and water.distance(mid) > NEAR_M):
                        runs.pop(cell, None)
                        continue
                    run = runs.get(cell)
                    if run is not None and run[-1] == pa:
                        run.append(pb)
                    else:
                        run = [pa, pb]
                        runs[cell] = run
                        out[cell].append((tags, run))
    return {
        c: [{"type": "way", "tags": tags, "geometry": [{"lat": round(p[0], 6), "lon": round(p[1], 6)} for p in run]}
            for tags, run in pieces]
        for c, pieces in out.items()
    }


def store_cells(cells: dict) -> None:
    db = _db()
    try:
        db.executemany("INSERT OR REPLACE INTO cells VALUES (?, ?)",
                       [(f"{c[0]},{c[1]}", json.dumps(els, separators=(",", ":"), ensure_ascii=False))
                        for c, els in cells.items()])
        db.commit()
    finally:
        db.close()


def river_cells(places_db: str, s, w, n, e) -> set:
    """Cells a river or stream line in places.db passes through, and their neighbours (a course
    request pads 300 m and rounds out to whole cells)."""
    conn = sqlite3.connect(places_db)
    rows = conn.execute("SELECT shape FROM places WHERE kind = 'river' AND shape IS NOT NULL "
                        "AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?", (s, n, w, e)).fetchall()
    conn.close()
    near = set()
    for (shape,) in rows:
        for line in json.loads(shape):
            for a, b in zip(line, line[1:]):
                for pa, pb in _split(a, b, 300):
                    for q in (pa, pb):
                        if s <= q[0] < n and w <= q[1] < e:
                            near.add(_cell_of(*q))
    return {(i + di, j + dj) for i, j in near for di in (-1, 0, 1) for dj in (-1, 0, 1)}


def prebuild(places_db: str, s, w, n, e, tile=0.05, log=print) -> None:
    """Fetches and stores the cells for every river in the box, tile by tile, patiently (this
    runs on the PC, nobody is waiting). Cells already built are skipped, so it can be resumed."""
    wanted = river_cells(places_db, s, w, n, e)
    db = _db()
    done = {r[0] for r in db.execute("SELECT key FROM cells")}
    db.close()

    def tile_of(c):
        return math.floor(c[0] / 100 / tile + 1e-9), math.floor(c[1] / 100 / tile + 1e-9)

    todo = sorted({tile_of(c) for c in wanted if f"{c[0]},{c[1]}" not in done})
    log(f"{len(wanted)} cells, {len(todo)} tiles to fetch")
    for k, (ti, tj) in enumerate(todo, 1):
        ts, tw = ti * tile, tj * tile
        cells = {c for c in wanted if tile_of(c) == (ti, tj)}
        pad = 0.003
        q = query(ts - pad, tw - pad, ts + tile + pad, tw + tile + pad).replace("[timeout:25]", "[timeout:180]")
        els = None
        for attempt in range(6):
            for url in landmarks.QUICK_SERVERS[::-1]:
                try:
                    body = landmarks._fetch(url, q, timeout=200)
                    if body[:1] == b"{":
                        els = json.loads(body)["elements"]
                        break
                except Exception as ex:  # busy, rate-limited: wait and retry
                    log(f"  {url.split('/')[2]}: {ex}")
            if els is not None:
                break
            time.sleep(30 * (attempt + 1))
        if els is None:
            log(f"tile {ts:.2f},{tw:.2f}: gave up")
            continue
        store_cells(cells_from(els, cells))
        log(f"[{k}/{len(todo)}] tile {ts:.2f},{tw:.2f}: {len(els)} elements -> {len(cells)} cells")
        time.sleep(5)  # be kind to the public server


if __name__ == "__main__":
    import sys
    if len(sys.argv) == 7 and sys.argv[1] == "prebuild":
        prebuild(sys.argv[2], *map(float, sys.argv[3:7]))
    else:
        sys.exit("usage: waterside.py prebuild <places.db> <south> <west> <north> <east>   (BJ_DATA_DIR set)")
