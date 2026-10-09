"""Places a course can pass: parks, lakes, beaches and rivers (docs/NL_COURSES.md).

Like trails.py, public catalogues are copied into our own SQLite instead of being called per app
request, so a course request costs a bounding-box lookup. Two sources:
 - 공공데이터포털 전국도시공원정보표준데이터 (`python places.py sync`): ~18,000 parks run by city
   and district offices, with type and area; positions only.
 - OpenStreetMap (`python osm.py sync`): the big parks the first one lacks (서울숲, 한강공원...),
   lakes, beaches and named rivers and streams, with outlines and lines. ODbL: the app shows
   "© OpenStreetMap 기여자".
Both are refreshed monthly; each sync replaces only its own rows.
"""

import json
import math
import os
import sqlite3
import sys
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.data.go.kr/openapi/tn_pubr_public_cty_park_info_api"
SOURCES = {"data.go.kr": "공공데이터포털 전국도시공원정보표준데이터", "osm": "© OpenStreetMap 기여자"}
SOURCE = SOURCES["data.go.kr"]
M_PER_DEG = 111_195.0

# Park types worth running in or through, in the standard data's own words. 어린이공원 and
# 소공원 are mostly a playground on a few hundred square metres; they're kept but never offered.
RUNNABLE_TYPES = {"근린공원", "수변공원", "체육공원", "도시자연공원", "도시자연공원구역", "역사공원",
                  "문화공원", "생태공원", "도시농업공원", "묘지공원", "방재공원"}
MIN_RUNNABLE_AREA_M2 = 10_000  # about 100 m x 100 m: a lap is at least ~400 m


def db_path() -> Path:
    return Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "places.db"


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(db_path())
    conn.row_factory = sqlite3.Row
    return conn


COLUMNS = ["id", "source", "kind", "name", "subtype", "lat", "lon", "min_lat", "max_lat", "min_lon",
           "max_lon", "area_m2", "length_m", "region", "address", "shape", "aliases", "updated"]


def init_db() -> None:
    db_path().parent.mkdir(parents=True, exist_ok=True)
    conn = _connect()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS places (
                id TEXT PRIMARY KEY,          -- data.go.kr: manageNo; osm: osm:way:123 / osm:river:123
                source TEXT NOT NULL,         -- data.go.kr / osm
                kind TEXT NOT NULL,           -- park / lake / beach / river
                name TEXT NOT NULL,
                subtype TEXT,                 -- data.go.kr parks: 근린공원, 수변공원, ...
                lat REAL NOT NULL,            -- a point on or in the place
                lon REAL NOT NULL,
                min_lat REAL NOT NULL, max_lat REAL NOT NULL, min_lon REAL NOT NULL, max_lon REAL NOT NULL,
                area_m2 REAL,
                length_m REAL,                -- rivers
                region TEXT,
                address TEXT,
                shape TEXT,                   -- JSON [[[lat, lon], ...], ...]: outlines or river lines
                aliases TEXT NOT NULL DEFAULT '[]',  -- JSON list of other names people use
                updated TEXT
            );
            CREATE INDEX IF NOT EXISTS places_box ON places (min_lat, max_lat, min_lon, max_lon);
            CREATE INDEX IF NOT EXISTS places_name ON places (name);
            """
        )
        conn.commit()
    finally:
        conn.close()


def replace_source(source: str, rows: list[dict]) -> int:
    """Swaps all rows of [source] for [rows], keeping aliases already given to surviving ids."""
    init_db()
    conn = _connect()
    try:
        aliases = dict(conn.execute("SELECT id, aliases FROM places WHERE source = ?", (source,)).fetchall())
        conn.execute("DELETE FROM places WHERE source = ?", (source,))
        conn.executemany(
            f"INSERT OR REPLACE INTO places ({', '.join(COLUMNS)}) VALUES ({', '.join(':' + c for c in COLUMNS)})",
            [{**{c: None for c in COLUMNS}, **r, "source": source, "aliases": aliases.get(r["id"], "[]")} for r in rows],
        )
        conn.commit()
    finally:
        conn.close()
    return len(rows)


# --- pure helpers ----------------------------------------------------------------------------

def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    kx = M_PER_DEG * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lon2 - lon1) * kx, (lat2 - lat1) * M_PER_DEG)


def _inside(lat: float, lon: float, ring) -> bool:
    inside = False
    for (y1, x1), (y2, x2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > lat) != (y2 > lat) and lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def distance_to_shape(lat: float, lon: float, lines, closed: bool) -> float:
    """Metres from (lat, lon) to the nearest point of [lines]; 0 inside a closed outline."""
    if closed and any(len(r) >= 3 and _inside(lat, lon, r) for r in lines):
        return 0.0
    kx = M_PER_DEG * math.cos(math.radians(lat))
    best = float("inf")
    for line in lines:
        xy = [((p[1] - lon) * kx, (p[0] - lat) * M_PER_DEG) for p in line]
        if len(xy) == 1:
            best = min(best, math.hypot(*xy[0]))
        for (ax, ay), (bx, by) in zip(xy, xy[1:]):
            dx, dy = bx - ax, by - ay
            L2 = dx * dx + dy * dy
            t = 0.0 if L2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
            best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


def parse_park(item: dict) -> dict | None:
    """One row of the standard park data -> our columns; None if it can't be placed in Korea."""
    try:
        lat, lon = float(item["latitude"]), float(item["longitude"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (33.0 <= lat <= 38.7 and 124.5 <= lon <= 132.0):
        return None  # missing or swapped coordinates
    name = " ".join((item.get("parkNm") or "").split())
    if not name or not item.get("manageNo"):
        return None
    try:
        area = float(item.get("parkAr") or 0) or None
    except ValueError:
        area = None
    return {
        "id": item["manageNo"].strip(), "kind": "park", "name": name,
        "subtype": (item.get("parkSe") or "").strip() or None, "lat": lat, "lon": lon,
        "min_lat": lat, "max_lat": lat, "min_lon": lon, "max_lon": lon, "area_m2": area,
        "region": (item.get("insttNm") or "").strip() or None,
        "address": (item.get("rdnmadr") or item.get("lnmadr") or "").strip() or None,
        "updated": item.get("referenceDate"),
    }


def runnable(p) -> bool:
    """Worth offering as somewhere to run: no playground-sized parks or ponds."""
    if p["kind"] in ("river", "beach"):
        return True
    if p["source"] == "data.go.kr" and p["subtype"] not in RUNNABLE_TYPES:
        return False
    return (p["area_m2"] or 0) >= MIN_RUNNABLE_AREA_M2


def _public(row, here: tuple[float, float] | None = None, away: float | None = None) -> dict:
    d = {
        "id": row["id"], "kind": row["kind"], "name": row["name"], "subtype": row["subtype"],
        "lat": row["lat"], "lon": row["lon"], "area_m2": row["area_m2"], "length_m": row["length_m"],
        "region": row["region"], "source": SOURCES[row["source"]],
    }
    if here:
        d["away_m"] = round(away if away is not None else _away(row, *here))
    return d


def _away(row, lat: float, lon: float) -> float:
    if row["shape"]:
        return distance_to_shape(lat, lon, json.loads(row["shape"]), closed=row["kind"] != "river")
    return distance_m(lat, lon, row["lat"], row["lon"])


# --- sync (data.go.kr parks) -----------------------------------------------------------------

def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def fetch_parks(key: str, fetch=_fetch, page_size: int = 1000) -> list[dict]:
    rows, page = [], 1
    while True:
        qs = urllib.parse.urlencode({"serviceKey": key, "pageNo": page, "numOfRows": page_size, "type": "json"})
        data = json.loads(fetch(f"{API}?{qs}"))
        data = data.get("response", data)
        if data.get("header", {}).get("resultCode") not in ("00", None):
            raise RuntimeError(f"park API: {data['header']}")
        items = data["body"].get("items") or {}
        items = items.get("item", []) if isinstance(items, dict) else items
        items = items if isinstance(items, list) else [items]
        rows += items
        if not items or len(rows) >= int(data["body"].get("totalCount", 0)):
            return rows
        page += 1


def sync(key: str, fetch=_fetch) -> int:
    """Replaces the stored data.go.kr parks with the current catalogue. Returns how many."""
    return replace_source("data.go.kr", [p for p in map(parse_park, fetch_parks(key, fetch)) if p])


# --- queries ---------------------------------------------------------------------------------

def _rows_near(lat: float, lon: float, radius_m: float, kinds) -> list:
    dlat = radius_m / M_PER_DEG
    dlon = radius_m / (M_PER_DEG * math.cos(math.radians(lat)))
    conn = _connect()
    try:
        return conn.execute(
            f"SELECT * FROM places WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? "
            f"AND kind IN ({', '.join('?' * len(kinds))})",
            (lat - dlat, lat + dlat, lon - dlon, lon + dlon, *kinds),
        ).fetchall()
    finally:
        conn.close()


def nearby(lat: float, lon: float, radius_m: float = 3000, limit: int = 50,
           kinds=("park", "lake", "beach", "river"), all_parks: bool = False) -> list[dict]:
    """Places within [radius_m] of (lat, lon) - measured to their edge or line - nearest first.
    Only ones worth running at, unless [all_parks]."""
    init_db()
    found = []
    for r in _rows_near(lat, lon, radius_m, kinds):
        if all_parks or runnable(r):
            away = _away(r, lat, lon)
            if away <= radius_m:
                found.append((away, r))
    found.sort(key=lambda t: t[0])
    # The same park from both sources: keep the first (nearest) of a name.
    seen, out = set(), []
    for away, r in found:
        key = (r["kind"], "".join(r["name"].split()))
        if key not in seen:
            seen.add(key)
            out.append(_public(r, (lat, lon), away))
    return out[:limit]


def find(name: str, lat: float, lon: float, limit: int = 5) -> list[dict]:
    """Places called [name] (or containing it, or with it as an alias), the likeliest first.
    Spaces are ignored: "올림픽 공원" finds 올림픽공원."""
    init_db()
    q = "".join(name.split())
    if len(q) < 2:
        return []
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM places WHERE replace(name, ' ', '') LIKE ? OR aliases LIKE ?",
            (f"%{q}%", f'%"{q}"%'),
        ).fetchall()
    finally:
        conn.close()

    def rank(r):
        exact = "".join(r["name"].split()) == q or q in json.loads(r["aliases"])
        size = r["area_m2"] or (r["length_m"] or 0) * 50 or 1
        # An exact name beats a partial one; then bigger places (more likely the one meant) and
        # nearer ones, a place twice as far counting like one a quarter the size.
        return (not exact, distance_m(lat, lon, r["lat"], r["lon"]) / math.sqrt(max(size, 1)))

    out, seen = [], set()
    for r in sorted(rows, key=rank):
        key = ("".join(r["name"].split()), round(r["lat"], 2), round(r["lon"], 2))
        if key not in seen:
            seen.add(key)
            out.append(_public(r, (lat, lon)))
    return out[:limit]


def shape(place_id: str) -> list | None:
    """A place's outline or lines, for the course planner."""
    conn = _connect()
    try:
        r = conn.execute("SELECT shape FROM places WHERE id = ?", (place_id,)).fetchone()
    finally:
        conn.close()
    return json.loads(r["shape"]) if r and r["shape"] else None


if __name__ == "__main__":
    if sys.argv[1:] != ["sync"]:
        sys.exit("usage: places.py sync   (needs DATA_GO_KR_KEY or DURUNUBI_KEY, and BJ_DATA_DIR)")
    key = os.environ.get("DATA_GO_KR_KEY") or os.environ["DURUNUBI_KEY"]
    print(f"stored {sync(key)} parks in {db_path()}")
