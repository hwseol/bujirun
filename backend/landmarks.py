"""Landmarks at a course's turns, for the voice: "100미터 앞 역삼역사거리에서 오른쪽".

The app sends the turn points once when a course run starts (and after a reroute); for each we
look for the most useful named thing right there in OpenStreetMap: a named intersection first
(Korean junctions have names, and they're what people navigate by), then a station, a bridge,
a park, a school or other public building, a convenience store. One Overpass query per request,
and answers are kept per ~10 m spot, so a course run again costs nothing.

Data © OpenStreetMap contributors, ODbL.
"""

import json
import math
import os
import sqlite3
import time
import urllib.parse
import urllib.request
from pathlib import Path

import osm
import places

M = places.M_PER_DEG
# For queries someone is waiting on: kumi answers in well under a second (its data can lag a few
# months, fine for paths and landmarks), the main server is current but often slow, and
# private.coffee was answering 500s (2026-10).
QUICK_SERVERS = ["https://overpass.kumi.systems/api/interpreter", "https://overpass-api.de/api/interpreter"]
CACHE_S = 60 * 86400
MAX_POINTS = 40

# (kind, Overpass filter, how close it must be in metres), best first.
KINDS = [
    ("junction", 'node["name"]["highway"="traffic_signals"]', 45),
    ("junction", 'node["name"]["junction"]', 45),
    ("station", 'node["name"]["railway"="station"]', 120),
    ("bridge", 'way["name"]["bridge"]', 30),
    ("park", 'way["name"]["leisure"="park"]', 30),
    ("building", 'nwr["name"]["amenity"~"^(school|library|post_office|police|townhall|hospital|community_centre)$"]', 40),
    ("store", 'node["name"]["shop"="convenience"]', 25),
]


def _db_path() -> Path:
    return Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "landmarks.db"


def _db():
    db = sqlite3.connect(_db_path())
    db.execute("CREATE TABLE IF NOT EXISTS landmarks (key TEXT PRIMARY KEY, at REAL, value TEXT)")
    return db


def _key(p) -> str:
    return f"{p[0]:.4f},{p[1]:.4f}"


def query(points: list[list[float]]) -> str:
    """One Overpass query for everything near any of [points], geometry clipped to their area."""
    reach = max(r for _, _, r in KINDS)
    lats = [p[0] for p in points]; lons = [p[1] for p in points]
    pad_lat = reach / M; pad_lon = reach / (M * math.cos(math.radians(sum(lats) / len(lats))))
    bbox = f"{min(lats) - pad_lat},{min(lons) - pad_lon},{max(lats) + pad_lat},{max(lons) + pad_lon}"
    parts = []
    for p in points:
        for _, flt, r in KINDS:
            parts.append(f"{flt}(around:{r},{p[0]:.6f},{p[1]:.6f});")
    return f"[out:json][timeout:25];({''.join(parts)});out tags geom({bbox});"


def _seg_dist(p, a, b) -> float:
    kx = M * math.cos(math.radians(p[0]))
    ax, ay = (a[1] - p[1]) * kx, (a[0] - p[0]) * M
    bx, by = (b[1] - p[1]) * kx, (b[0] - p[0]) * M
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def distance(p, el: dict) -> float:
    """Metres from p to a node, or to the nearest part of a way's (or relation's) line."""
    if "lat" in el:
        return places.distance_m(p[0], p[1], el["lat"], el["lon"])
    best = math.inf
    for line in osm.lines_of(el) if el.get("type") in ("way", "relation") else []:
        if len(line) == 1:
            best = min(best, places.distance_m(p[0], p[1], *line[0]))
        for a, b in zip(line, line[1:]):
            best = min(best, _seg_dist(p, a, b))
    return best


def kind_of(el: dict) -> tuple[int, str, float] | None:
    t = el.get("tags", {})
    if el["type"] == "node" and (t.get("highway") == "traffic_signals" or "junction" in t):
        return 0, "junction", 45
    if el["type"] == "node" and t.get("railway") == "station":
        return 2, "station", 120
    if el["type"] == "way" and "bridge" in t and t.get("bridge") != "no":
        return 3, "bridge", 30
    if t.get("leisure") == "park":
        return 4, "park", 30
    if t.get("amenity"):
        return 5, "building", 40
    if t.get("shop") == "convenience":
        return 6, "store", 25
    return None


def pick(p, elements: list[dict]) -> dict | None:
    """The best landmark at turn point p: the first kind that has one in reach, nearest of it."""
    best = None
    for el in elements:
        k = kind_of(el)
        name = el.get("tags", {}).get("name", "").strip()
        if not k or not name:
            continue
        d = distance(p, el)
        if d > k[2]:
            continue
        if best is None or (k[0], d) < (best[0], best[1]):
            best = (k[0], d, k[1], name)
    if best is None:
        return None
    name = best[3]
    if best[2] == "station" and not name.endswith("역"):
        name += "역"
    return {"name": name, "kind": best[2]}


def lookup(points: list[list[float]], fetch=None) -> list[dict | None]:
    """Landmarks for each of [points] (None where nothing useful is there)."""
    points = points[:MAX_POINTS]
    db = _db()
    now = time.time()
    found: dict[str, dict | None] = {}
    for p in points:
        row = db.execute("SELECT at, value FROM landmarks WHERE key = ?", (_key(p),)).fetchone()
        if row and now - row[0] < CACHE_S:
            found[_key(p)] = json.loads(row[1])
    missing = [p for p in points if _key(p) not in found]
    if missing:
        elements = _overpass(query(missing), fetch)
        if elements is not None:
            for p in missing:
                found[_key(p)] = pick(p, elements)
                db.execute("INSERT OR REPLACE INTO landmarks VALUES (?, ?, ?)", (_key(p), now, json.dumps(found[_key(p)])))
            db.commit()
    db.close()
    return [found.get(_key(p)) for p in points]


def _overpass(q: str, fetch=None) -> list[dict] | None:
    """Quick and forgiving (the runner is already on the way): two servers, short timeouts, no
    waiting - None (and nothing cached) if both are busy."""
    fetch = fetch or _fetch
    for url in QUICK_SERVERS:
        try:
            body = fetch(url, q)
            if body[:1] == b"{":
                return json.loads(body)["elements"]
        except Exception:
            pass
    return None


def _fetch(url: str, q: str) -> bytes:
    req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(),
                                 headers={"User-Agent": osm.USER_AGENT})
    # 9 s: both servers within the app's 20 s read timeout.
    with urllib.request.urlopen(req, timeout=9) as r:
        return r.read()
