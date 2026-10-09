"""Official walking trails (Korea Tourism Organization's Durunubi: 코리아둘레길 etc.).

We copy the whole catalogue (~140 courses) into our own SQLite once (`python trails.py sync`,
re-run occasionally - courses change rarely) instead of calling the public API per app request:
the dev quota is 1,000 calls a day, and each course's GPX is a separate download. Routes are
simplified to ~5 m before storing. The app must credit the source (한국관광공사 두루누비).
"""

import html
import json
import math
import os
import re
import sqlite3
import sys
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://apis.data.go.kr/B551011/Durunubi/courseList"
SOURCE = "한국관광공사 두루누비"
M_PER_DEG = 111_195.0


def db_path() -> Path:
    return Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "trails.db"


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(db_path())
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    db_path().parent.mkdir(parents=True, exist_ok=True)
    conn = _connect()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS trails (
                id TEXT PRIMARY KEY,          -- Durunubi crsIdx
                name TEXT NOT NULL,
                region TEXT,
                distance_m REAL NOT NULL,     -- measured from the route
                hours REAL,                   -- official estimate
                level INTEGER,                -- 1 easy .. 3 hard
                loop INTEGER NOT NULL,
                summary TEXT,
                start_lat REAL NOT NULL, start_lon REAL NOT NULL,
                min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL,
                points TEXT NOT NULL,         -- JSON [[lat, lon, ele|null], ...]
                modified TEXT
            )
            """
        )
        conn.commit()
    finally:
        conn.close()


# --- pure helpers ----------------------------------------------------------------------------

_TRKPT = re.compile(r"<(?:\w+:)?trkpt\b[^>]*?lat=\"([-0-9.]+)\"[^>]*?lon=\"([-0-9.]+)\"[^>]*?(?:/>|>(.*?)</(?:\w+:)?trkpt>)", re.S)
_TRKPT_LONFIRST = re.compile(r"<(?:\w+:)?trkpt\b[^>]*?lon=\"([-0-9.]+)\"[^>]*?lat=\"([-0-9.]+)\"[^>]*?(?:/>|>(.*?)</(?:\w+:)?trkpt>)", re.S)
_ELE = re.compile(r"<(?:\w+:)?ele>\s*([-0-9.]+)\s*<")


def parse_gpx(text: str) -> list[list]:
    """Track points as [lat, lon, ele|None]. A regex rather than an XML parser: the files come
    from outside and we only need three numbers per point."""
    pts = []
    for m in _TRKPT.finditer(text):
        e = _ELE.search(m.group(3) or "")
        pts.append([float(m.group(1)), float(m.group(2)), float(e.group(1)) if e else None])
    if not pts:  # some writers put lon before lat
        for m in _TRKPT_LONFIRST.finditer(text):
            e = _ELE.search(m.group(3) or "")
            pts.append([float(m.group(2)), float(m.group(1)), float(e.group(1)) if e else None])
    return pts


def _xy(points):
    kx = M_PER_DEG * math.cos(math.radians(points[0][0]))
    return [((p[1] - points[0][1]) * kx, (p[0] - points[0][0]) * M_PER_DEG) for p in points]


def length_m(points) -> float:
    xy = _xy(points)
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(xy, xy[1:]))


def simplify(points, tol_m: float = 5.0):
    """Douglas-Peucker (as the app does for imported GPX)."""
    if len(points) < 3:
        return points
    xy = _xy(points)
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        s, e = stack.pop()
        ax, ay = xy[s]; bx, by = xy[e]
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        best, idx = 0.0, -1
        for i in range(s + 1, e):
            px, py = xy[i]
            t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
            d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > best:
                best, idx = d, i
        if idx >= 0 and best > tol_m:
            keep[idx] = True
            stack += [(s, idx), (idx, e)]
    return [p for p, k in zip(points, keep) if k]


def clean_text(s: str | None) -> str:
    if not s:
        return ""
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    return html.unescape(s).strip()


def distance_to_m(lat: float, lon: float, points) -> float:
    """Closest approach (m) from (lat, lon) to the trail line - to its segments, not just its
    vertices: a simplified straight stretch keeps only its two ends."""
    kx = M_PER_DEG * math.cos(math.radians(lat))
    xy = [((p[1] - lon) * kx, (p[0] - lat) * M_PER_DEG) for p in points]
    if len(xy) == 1:
        return math.hypot(*xy[0])
    best = float("inf")
    for (ax, ay), (bx, by) in zip(xy, xy[1:]):
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


# --- sync ------------------------------------------------------------------------------------

def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=30) as r:
        return r.read()


def fetch_courses(key: str, fetch=_fetch) -> list[dict]:
    rows, page = [], 1
    while True:
        qs = urllib.parse.urlencode({
            "serviceKey": key, "numOfRows": 100, "pageNo": page,
            "MobileOS": "AND", "MobileApp": "Bujirun", "_type": "json",
        })
        body = json.loads(fetch(f"{API}?{qs}"))["response"]["body"]
        items = (body.get("items") or {}).get("item") or []
        items = items if isinstance(items, list) else [items]
        rows += items
        if not items or len(rows) >= int(body.get("totalCount", 0)):
            return rows
        page += 1


def sync(key: str, fetch=_fetch, log=print) -> int:
    """Downloads every course and its GPX into trails.db. Returns how many were stored."""
    init_db()
    stored = 0
    conn = _connect()
    try:
        for c in fetch_courses(key, fetch):
            gpx_url = c.get("gpxpath")
            if not gpx_url:
                continue
            try:
                pts = parse_gpx(fetch(gpx_url).decode("utf-8", "replace"))
            except Exception as e:  # one bad file shouldn't stop the rest
                log(f"skip {c.get('crsKorNm')}: {e}")
                continue
            if len(pts) < 2:
                log(f"skip {c.get('crsKorNm')}: no track")
                continue
            line = simplify(pts)
            lats = [p[0] for p in line]; lons = [p[1] for p in line]
            conn.execute(
                "INSERT OR REPLACE INTO trails VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    c["crsIdx"], c.get("crsKorNm", "").strip(), c.get("sigun"),
                    length_m(pts),
                    float(c["crsTotlRqrmHour"]) / 60 if str(c.get("crsTotlRqrmHour", "")).isdigit() else None,
                    int(c["crsLevel"]) if str(c.get("crsLevel", "")).isdigit() else None,
                    1 if c.get("crsCycle") == "순환형" else 0,
                    clean_text(c.get("crsSummary")),
                    line[0][0], line[0][1], min(lats), max(lats), min(lons), max(lons),
                    json.dumps(line, separators=(",", ":")), c.get("modifiedtime"),
                ),
            )
            stored += 1
        conn.commit()
    finally:
        conn.close()
    return stored


# --- queries ---------------------------------------------------------------------------------

def _summary(row, away_m: float | None = None) -> dict:
    d = {
        "id": row["id"], "name": row["name"], "region": row["region"],
        "distance_m": round(row["distance_m"]), "hours": row["hours"], "level": row["level"],
        "loop": bool(row["loop"]), "summary": row["summary"], "source": SOURCE,
        "start": [row["start_lat"], row["start_lon"]],
    }
    if away_m is not None:
        d["away_m"] = round(away_m)
    return d


def nearby(lat: float, lon: float, limit: int = 20) -> list[dict]:
    """Trails ordered by how close they come to (lat, lon), with a light preview line."""
    init_db()
    conn = _connect()
    try:
        rows = conn.execute("SELECT * FROM trails").fetchall()
    finally:
        conn.close()
    scored = []
    for r in rows:
        pts = json.loads(r["points"])
        scored.append((distance_to_m(lat, lon, pts), r, pts))
    scored.sort(key=lambda t: t[0])
    out = []
    for away, r, pts in scored[:limit]:
        d = _summary(r, away)
        d["preview"] = [[p[0], p[1]] for p in simplify(pts, 40.0)]
        out.append(d)
    return out


def get(trail_id: str) -> dict | None:
    init_db()
    conn = _connect()
    try:
        r = conn.execute("SELECT * FROM trails WHERE id = ?", (trail_id,)).fetchone()
    finally:
        conn.close()
    if r is None:
        return None
    d = _summary(r)
    d["points"] = json.loads(r["points"])
    return d


if __name__ == "__main__":
    if sys.argv[1:] != ["sync"]:
        sys.exit("usage: trails.py sync   (needs DURUNUBI_KEY and BJ_DATA_DIR)")
    n = sync(os.environ["DURUNUBI_KEY"])
    print(f"stored {n} trails in {db_path()}")
