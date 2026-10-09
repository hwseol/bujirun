"""Places a course can pass: city parks for now (rivers and sights come later, docs/NL_COURSES.md).

Like trails.py, the public catalogue is copied into our own SQLite (`python places.py sync`,
monthly) rather than called per app request, so a course request costs a bounding-box lookup.
Source: 공공데이터포털 전국도시공원정보표준데이터 (~18,000 parks with type, area and position).
The app must credit it.
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
SOURCE = "공공데이터포털 전국도시공원정보표준데이터"
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


def init_db() -> None:
    db_path().parent.mkdir(parents=True, exist_ok=True)
    conn = _connect()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS places (
                id TEXT PRIMARY KEY,          -- park: the standard data's manageNo
                kind TEXT NOT NULL,           -- park
                name TEXT NOT NULL,
                subtype TEXT,                 -- park: 근린공원, 수변공원, ...
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                area_m2 REAL,
                region TEXT,                  -- the managing city/district
                address TEXT,
                aliases TEXT NOT NULL DEFAULT '[]',  -- JSON list of other names people use
                updated TEXT
            );
            CREATE INDEX IF NOT EXISTS places_lat_lon ON places (lat, lon);
            CREATE INDEX IF NOT EXISTS places_name ON places (name);
            """
        )
        conn.commit()
    finally:
        conn.close()


# --- pure helpers ----------------------------------------------------------------------------

def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    kx = M_PER_DEG * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lon2 - lon1) * kx, (lat2 - lat1) * M_PER_DEG)


def parse_park(item: dict) -> dict | None:
    """One row of the standard data -> our columns; None if it can't be placed in Korea."""
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
        "subtype": (item.get("parkSe") or "").strip() or None, "lat": lat, "lon": lon, "area_m2": area,
        "region": (item.get("insttNm") or "").strip() or None,
        "address": (item.get("rdnmadr") or item.get("lnmadr") or "").strip() or None,
        "updated": item.get("referenceDate"),
    }


def runnable(p) -> bool:
    return p["subtype"] in RUNNABLE_TYPES and (p["area_m2"] or 0) >= MIN_RUNNABLE_AREA_M2


def _public(row, here: tuple[float, float] | None = None) -> dict:
    d = {
        "id": row["id"], "kind": row["kind"], "name": row["name"], "subtype": row["subtype"],
        "lat": row["lat"], "lon": row["lon"], "area_m2": row["area_m2"], "region": row["region"],
    }
    if here:
        d["away_m"] = round(distance_m(here[0], here[1], row["lat"], row["lon"]))
    return d


# --- sync ------------------------------------------------------------------------------------

def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def fetch_parks(key: str, fetch=_fetch, page_size: int = 1000) -> list[dict]:
    rows, page = [], 1
    while True:
        qs = urllib.parse.urlencode({"serviceKey": key, "pageNo": page, "numOfRows": page_size, "type": "json"})
        data = json.loads(fetch(f"{API}?{qs}"))
        header = data.get("response", data).get("header", {})
        if header.get("resultCode") not in ("00", None):
            raise RuntimeError(f"park API: {header}")
        body = data.get("response", data)["body"]
        items = (body.get("items") or {})
        items = items.get("item", items) if isinstance(items, dict) else items
        items = items if isinstance(items, list) else [items]
        rows += items
        if not items or len(rows) >= int(body.get("totalCount", 0)):
            return rows
        page += 1


def sync(key: str, fetch=_fetch) -> int:
    """Replaces the stored parks with the current catalogue, keeping any aliases already added.
    Returns how many were stored."""
    init_db()
    parks = [p for p in map(parse_park, fetch_parks(key, fetch)) if p]
    conn = _connect()
    try:
        aliases = dict(conn.execute("SELECT id, aliases FROM places WHERE kind = 'park'").fetchall())
        conn.execute("DELETE FROM places WHERE kind = 'park'")
        conn.executemany(
            "INSERT OR REPLACE INTO places (id, kind, name, subtype, lat, lon, area_m2, region, address, aliases, updated) "
            "VALUES (:id, :kind, :name, :subtype, :lat, :lon, :area_m2, :region, :address, :aliases, :updated)",
            [{**p, "aliases": aliases.get(p["id"], "[]")} for p in parks],
        )
        conn.commit()
    finally:
        conn.close()
    return len(parks)


# --- queries ---------------------------------------------------------------------------------

def nearby(lat: float, lon: float, radius_m: float = 3000, limit: int = 50, all_parks: bool = False) -> list[dict]:
    """Runnable parks within [radius_m] of (lat, lon), nearest first (any park if [all_parks])."""
    init_db()
    dlat = radius_m / M_PER_DEG
    dlon = radius_m / (M_PER_DEG * math.cos(math.radians(lat)))
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            (lat - dlat, lat + dlat, lon - dlon, lon + dlon),
        ).fetchall()
    finally:
        conn.close()
    found = [(distance_m(lat, lon, r["lat"], r["lon"]), r) for r in rows if all_parks or runnable(r)]
    found = sorted((t for t in found if t[0] <= radius_m), key=lambda t: t[0])
    return [_public(r, (lat, lon)) for _, r in found[:limit]]


def find(name: str, lat: float, lon: float, limit: int = 5) -> list[dict]:
    """Places called [name] (or containing it, or with it as an alias), the closest to (lat, lon)
    first. Spaces are ignored: "올림픽 공원" finds 올림픽공원."""
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
        # An exact name beats a partial one; then bigger parks (more likely the one meant) and
        # nearer ones, a park twice as far counting like one a quarter the size.
        return (not exact, distance_m(lat, lon, r["lat"], r["lon"]) / math.sqrt(max(r["area_m2"] or 1, 1)))

    return [_public(r, (lat, lon)) for r in sorted(rows, key=rank)[:limit]]


if __name__ == "__main__":
    if sys.argv[1:] != ["sync"]:
        sys.exit("usage: places.py sync   (needs DATA_GO_KR_KEY or DURUNUBI_KEY, and BJ_DATA_DIR)")
    key = os.environ.get("DATA_GO_KR_KEY") or os.environ["DURUNUBI_KEY"]
    print(f"stored {sync(key)} parks in {db_path()}")
