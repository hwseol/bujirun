"""OpenStreetMap places for places.db: parks, lakes, beaches with outlines, and named rivers and
streams as lines. `python osm.py sync` (monthly; two nationwide Overpass queries, a few minutes).

Data © OpenStreetMap contributors, ODbL; the app credits it wherever these places are used.
OSM adds the parks the public standard data lacks (서울숲, 보라매공원, the Han river parks...) and
the outlines of the ones it has; places.py prefers the OSM row of a park found in both.
"""

import json
import math
import re
import sys
import time
import urllib.parse
import urllib.request

import places
import trails

SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://overpass.kumi.systems/api/interpreter"]
USER_AGENT = "Bujirun/1.0 (running course app; github.com/hwseol/bujirun)"
KR = 'area["ISO3166-1"="KR"][admin_level=2]->.kr;'
AREAS_QUERY = f"""[out:json][timeout:900];{KR}
(
  way["leisure"="park"]["name"](area.kr); relation["leisure"="park"]["name"](area.kr);
  way["natural"="water"]["water"~"^(lake|reservoir|pond|lagoon)$"]["name"](area.kr);
  relation["natural"="water"]["water"~"^(lake|reservoir|pond|lagoon)$"]["name"](area.kr);
  way["natural"="beach"]["name"](area.kr); relation["natural"="beach"]["name"](area.kr);
);
out geom qt;"""
RIVERS_QUERY = f"""[out:json][timeout:900];{KR}
way["waterway"~"^(river|stream|canal)$"]["name"](area.kr);
out geom qt;"""

M = places.M_PER_DEG
JOIN_M = 300            # river segments with the same name this close are one river
MIN_RIVER_M = 1_000     # shorter named streams aren't worth a course
SIMPLIFY_M = 15


def overpass(query: str, fetch=None) -> list[dict]:
    """Runs [query] on the public Overpass servers, trying the next one (and again later) when
    one is busy - they often are."""
    fetch = fetch or _fetch
    last = None
    for attempt in range(6):
        url = SERVERS[attempt % len(SERVERS)]
        try:
            body = fetch(url, query)
            if body[:1] == b"{":
                return json.loads(body)["elements"]
            last = body[:300]
        except Exception as e:  # network errors, 5xx
            last = e
        time.sleep(20 * (attempt + 1))
    raise RuntimeError(f"Overpass failed: {last!r}")


def _fetch(url: str, query: str) -> bytes:
    req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": query}).encode(),
                                 headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=1000) as r:
        return r.read()


# --- geometry --------------------------------------------------------------------------------

def lines_of(el: dict) -> list[list[list[float]]]:
    """A way's line, or a relation's outer member lines, as [[lat, lon], ...] lists."""
    if el["type"] == "way":
        geoms = [el.get("geometry") or []]
    else:
        members = [m for m in el.get("members", []) if m.get("geometry")]
        outer = [m for m in members if m.get("role") in ("outer", "")] or members
        geoms = [m["geometry"] for m in outer]
    lines = [[[round(p["lat"], 6), round(p["lon"], 6)] for p in g if p] for g in geoms if len(g) >= 2]
    if not lines and "bounds" in el:
        # No member geometry (shouldn't happen with `out geom`): the bounding box beats losing it.
        b = el["bounds"]
        lines = [[[b["minlat"], b["minlon"]], [b["minlat"], b["maxlon"]], [b["maxlat"], b["maxlon"]],
                  [b["maxlat"], b["minlon"]], [b["minlat"], b["minlon"]]]]
    return lines


def ring_area_m2(ring) -> float:
    if len(ring) < 3:
        return 0.0
    lat0 = ring[0][0]
    kx = M * math.cos(math.radians(lat0))
    xy = [((p[1] - ring[0][1]) * kx, (p[0] - lat0) * M) for p in ring]
    return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(xy, xy[1:] + xy[:1]))) / 2


def join_rings(lines):
    """Relation outlines come as pieces; chain pieces that meet end to end into rings."""
    pieces = [list(l) for l in lines]
    rings = []
    while pieces:
        ring = pieces.pop()
        changed = True
        while changed and ring[0] != ring[-1]:
            changed = False
            for i, p in enumerate(pieces):
                if p[0] == ring[-1]:
                    ring += p[1:]
                elif p[-1] == ring[-1]:
                    ring += p[::-1][1:]
                elif p[-1] == ring[0]:
                    ring = p + ring[1:]
                elif p[0] == ring[0]:
                    ring = p[::-1] + ring[1:]
                else:
                    continue
                pieces.pop(i)
                changed = True
                break
        rings.append(ring)
    return rings


def simplify(line, tol_m=SIMPLIFY_M):
    """Douglas-Peucker, the same as the trails use."""
    if len(line) < 3:
        return line
    return [[p[0], p[1]] for p in trails.simplify([[p[0], p[1], None] for p in line], tol_m)]


def length_m(line) -> float:
    return sum(places.distance_m(a[0], a[1], b[0], b[1]) for a, b in zip(line, line[1:]))


def box(lines):
    lats = [p[0] for l in lines for p in l]
    lons = [p[1] for l in lines for p in l]
    return {"min_lat": min(lats), "max_lat": max(lats), "min_lon": min(lons), "max_lon": max(lons)}


def _middle(lines):
    longest = max(lines, key=len)
    return longest[len(longest) // 2]


# --- elements -> rows ------------------------------------------------------------------------

def area_rows(elements: list[dict], existing_parks: list = ()) -> list[dict]:
    """Parks, lakes and beaches, with outlines. Parks the standard data also has are kept: only
    OSM has their outline (and paths); places.nearby/find prefer the row with a shape.
    [existing_parks] is unused, kept for callers."""
    rows = []
    for el in elements:
        tags = el.get("tags", {})
        name = " ".join(tags.get("name", "").split())
        lines = lines_of(el)
        if not name or not lines:
            continue
        kind = "park" if tags.get("leisure") == "park" else "beach" if tags.get("natural") == "beach" else "lake"
        rings = join_rings(lines) if el["type"] == "relation" else lines
        rings = [simplify(r) for r in rings if len(r) >= 2]
        area = sum(ring_area_m2(r) for r in rings if r[0] == r[-1] or len(r) > 3)
        # A point inside: the centre of the box if it is inside, else a vertex.
        b = box(rings)
        c = [(b["min_lat"] + b["max_lat"]) / 2, (b["min_lon"] + b["max_lon"]) / 2]
        if not any(places._inside(c[0], c[1], r) for r in rings):
            c = _middle(rings)
        rows.append({
            "id": f"osm:{el['type']}:{el['id']}", "kind": kind, "name": name,
            "subtype": tags.get("water") if kind == "lake" else None,
            "lat": round(c[0], 6), "lon": round(c[1], 6), **box(rings), "area_m2": round(area),
            "shape": json.dumps(rings, separators=(",", ":")),
        })
    return rows


def chain_lines(lines, tol_m: float = 30):
    """Joins open lines whose ends meet (within [tol_m]) into as few long lines as possible, so a
    river can be followed for kilometres without jumping between pieces."""
    pieces = [list(l) for l in lines]
    out = []
    near = lambda a, b: places.distance_m(a[0], a[1], b[0], b[1]) <= tol_m
    while pieces:
        line = pieces.pop()
        changed = True
        while changed:
            changed = False
            for i, p in enumerate(pieces):
                if near(p[0], line[-1]):
                    line += p[1:]
                elif near(p[-1], line[-1]):
                    line += p[::-1][1:]
                elif near(p[-1], line[0]):
                    line = p[:-1] + line
                elif near(p[0], line[0]):
                    line = p[::-1][:-1] + line
                else:
                    continue
                pieces.pop(i)
                changed = True
                break
        out.append(line)
    return sorted(out, key=len, reverse=True)


def river_rows(elements: list[dict]) -> list[dict]:
    """Named rivers and streams. OSM splits a river into many ways; ways with the same name that
    touch (within JOIN_M) become one place, while same-named streams in different towns stay
    separate."""
    by_name = {}
    for el in elements:
        name = " ".join(el.get("tags", {}).get("name", "").split())
        line = lines_of(el)
        if name and line:
            by_name.setdefault(name, []).append((el["id"], line[0], el["tags"].get("waterway")))
    rows = []
    for name, ways in by_name.items():
        parent = list(range(len(ways)))

        def root(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        ends = [(w[1][0], w[1][-1]) for w in ways]
        # Grid of endpoints so this stays fast for names with thousands of ways.
        grid = {}
        for i, (a, b) in enumerate(ends):
            for p in (a, b):
                grid.setdefault((round(p[0] * 100), round(p[1] * 100)), []).append((i, p))
        for i, (a, b) in enumerate(ends):
            for p in (a, b):
                gx, gy = round(p[0] * 100), round(p[1] * 100)
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        for j, q in grid.get((gx + dx, gy + dy), []):
                            if j != i and places.distance_m(p[0], p[1], q[0], q[1]) <= JOIN_M:
                                parent[root(i)] = root(j)
        groups = {}
        for i in range(len(ways)):
            groups.setdefault(root(i), []).append(ways[i])
        for members in groups.values():
            lines = [simplify(l) for l in chain_lines([l for _, l, _ in members])]
            total = sum(length_m(l) for l in lines)
            if total < MIN_RIVER_M:
                continue
            mid = _middle(lines)
            rows.append({
                "id": f"osm:river:{min(w for w, _, _ in members)}", "kind": "river", "name": name,
                # river / stream / canal: how wide it is, roughly (a river's banks are far apart)
                "subtype": "river" if any(t == "river" for _, _, t in members) else members[0][2],
                "lat": mid[0], "lon": mid[1], **box(lines), "length_m": round(total),
                "shape": json.dumps(lines, separators=(",", ":")),
            })
    return rows


PATHS_QUERY = f"""[out:json][timeout:1200][maxsize:1073741824];{KR}
(way["leisure"="park"](area.kr); relation["leisure"="park"](area.kr);)->.parks;
.parks map_to_area ->.pa;
(
  way["highway"~"^(footway|path|track|cycleway|pedestrian|living_street|service)$"](area.pa);
  way["leisure"="track"](area.pa);
);
out geom qt;"""


def park_path_rows(elements: list[dict], park_rows: list[dict]) -> dict:
    """Footways -> {park id: [lines]}: each way goes to the parks whose outline holds most of
    its points. Ways are kept as mapped, not simplified: the vertices they share are what joins
    them into a network (parkpaths.py), and simplifying could drop a junction."""
    grid = {}
    parks = []
    for r in park_rows:
        if r["kind"] != "park" or not r.get("shape"):
            continue
        rings = json.loads(r["shape"])
        parks.append((r["id"], rings))
        i = len(parks) - 1
        for gx in range(int(r["min_lat"] * 100), int(r["max_lat"] * 100) + 1):
            for gy in range(int(r["min_lon"] * 100), int(r["max_lon"] * 100) + 1):
                grid.setdefault((gx, gy), []).append(i)
    out = {}
    for el in elements:
        line = [[round(p["lat"], 6), round(p["lon"], 6)] for p in el.get("geometry") or [] if p]
        if len(line) < 2:
            continue
        mid = line[len(line) // 2]
        for i in grid.get((int(mid[0] * 100), int(mid[1] * 100)), []):
            pid, rings = parks[i]
            inside = sum(any(places._inside(p[0], p[1], ring) for ring in rings) for p in line)
            if inside * 2 >= len(line):
                out.setdefault(pid, []).append(line)
    return out


NOT_RUNNING = {"cycling", "horse_racing", "equestrian", "motor", "karting", "bmx", "dog_racing",
               "ice_skating", "skateboard", "roller_skating"}


def _closed(line) -> bool:
    return places.distance_m(*line[0], *line[-1]) < 10


def park_track_rows(elements: list[dict], park_rows: list[dict]) -> dict:
    """Running tracks -> {park id: [rings]}: closed leisure=track ways, 200 m to 1.5 km round
    (a school or park track, not a cycling or horse track)."""
    tracks = [el for el in elements if el.get("tags", {}).get("leisure") == "track"
              and not set(el["tags"].get("sport", "").split(";")) & NOT_RUNNING]
    out = {}
    for pid, lines in park_path_rows(tracks, park_rows).items():
        rings = [l for l in lines if len(l) >= 4 and _closed(l) and 200 <= length_m(l) <= 1500]
        if rings:
            out[pid] = rings
    return out


TRACKS_QUERY = f"""[out:json][timeout:900];{KR}
way["leisure"="track"](area.kr);
out geom qt;
(
  way["amenity"~"^(school|university|college)$"]["name"](area.kr);
  relation["amenity"~"^(school|university|college)$"]["name"](area.kr);
  way["leisure"~"^(stadium|sports_centre)$"]["name"](area.kr);
);
out geom qt;"""


def track_rows(elements: list[dict], park_rows: list[dict], river_rows: list[dict]) -> list[dict]:
    """Running tracks anywhere (a university's, a riverside one, a park's) as places to lap,
    kind "track". OSM rarely names a track, so it's named for where it is: the school or
    stadium it's in, else the park, else a river within 300 m ("안양천 트랙"). subtype says
    which: school tracks (초중고) are often locked outside school hours, so the planner doesn't
    pick them by itself."""
    holders = []
    for el in elements:
        t = el.get("tags", {})
        if t.get("leisure") == "track" or not t.get("name"):
            continue
        rings = [l for l in lines_of(el) if len(l) >= 4]
        if rings:
            kind = "university" if t.get("amenity") in ("university", "college") else \
                "school" if t.get("amenity") == "school" else "stadium"
            holders.append((" ".join(t["name"].split()), kind, rings, box(rings)))
    parks = [(r["name"], json.loads(r["shape"]), r) for r in park_rows if r.get("shape")]
    rivers = [(r["name"], json.loads(r["shape"]), r) for r in river_rows if r.get("shape")]
    rows = []
    for el in elements:
        t = el.get("tags", {})
        if t.get("leisure") != "track" or set(t.get("sport", "").split(";")) & NOT_RUNNING:
            continue
        line = [[round(p["lat"], 6), round(p["lon"], 6)] for p in el.get("geometry") or [] if p]
        if len(line) < 4 or not _closed(line) or not 200 <= length_m(line) <= 1500:
            continue
        b = box([line])
        c = [(b["min_lat"] + b["max_lat"]) / 2, (b["min_lon"] + b["max_lon"]) / 2]

        def inside(rings, bb):
            return bb["min_lat"] <= c[0] <= bb["max_lat"] and bb["min_lon"] <= c[1] <= bb["max_lon"] and \
                any(places._inside(c[0], c[1], r) for r in rings)

        own = " ".join(t.get("name", "").split())
        # "트랙", "육상 트랙": no name at all as far as finding it goes.
        generic = own.replace(" ", "") in GENERIC_TRACK_NAMES or re.fullmatch(r"\d+\s*m?\s*트랙", own)
        name, subtype = (own if own and not generic else None), None
        holder = next((h for h in holders if inside(h[2], h[3])), None)
        if holder and MILITARY.search(holder[0]):
            continue  # a base's or academy's track: not open to anyone
        if holder:
            name, subtype = name or f"{holder[0]} 트랙", holder[1]
        else:
            park = next((p for p in parks if inside(p[1], p[2])), None)
            if park:
                name, subtype = name or f"{park[0]} 트랙", "park"
            else:
                near = [(places.distance_to_shape(c[0], c[1], lines, closed=False), n) for n, lines, r in rivers
                        if r["min_lat"] - 0.004 <= c[0] <= r["max_lat"] + 0.004 and r["min_lon"] - 0.005 <= c[1] <= r["max_lon"] + 0.005]
                river = min(near, default=None)
                if river and river[0] <= 300:
                    name, subtype = name or f"{river[1]} 트랙", "river"
        if not name:
            continue  # nowhere to call it by - a track nobody could find from its name
        rows.append({
            "id": f"osm:track:{el['id']}", "kind": "track", "name": " ".join(name.split()), "subtype": subtype,
            "lat": round(c[0], 6), "lon": round(c[1], 6), **b, "length_m": round(length_m(line)),
            "shape": json.dumps([line], separators=(",", ":")),
        })
    # Lanes or a warm-up loop mapped as their own tracks: one place per track, the longest ring.
    rows.sort(key=lambda r: -r["length_m"])
    kept = []
    for r in rows:
        if not any(k["name"] == r["name"] and places.distance_m(k["lat"], k["lon"], r["lat"], r["lon"]) < 300 for k in kept):
            kept.append(r)
    return kept


GENERIC_TRACK_NAMES = {"트랙", "육상트랙", "러닝트랙", "운동장", "트랙운동장", "육상경기장", "track", "runningtrack"}
MILITARY = re.compile(r"부대|사령부|사관학교|군수|비행단|여단|사단|연대|교도소|공군|육군|해군|해병")


def _park_rows_from_db() -> list[dict]:
    conn = places._connect()
    try:
        return [dict(r) for r in conn.execute("SELECT * FROM places WHERE source = 'osm' AND kind = 'park'")]
    finally:
        conn.close()


def sync(fetch=None, log=print) -> int:
    places.init_db()
    conn = places._connect()
    try:
        existing = conn.execute("SELECT name, lat, lon FROM places WHERE source = 'data.go.kr'").fetchall()
    finally:
        conn.close()
    areas = area_rows(overpass(AREAS_QUERY, fetch), [tuple(r) for r in existing])
    log(f"{len(areas)} parks, lakes and beaches")
    rivers = river_rows(overpass(RIVERS_QUERY, fetch))
    log(f"{len(rivers)} rivers and streams")
    n = places.replace_source("osm", areas + rivers)
    path_elements = overpass(PATHS_QUERY, fetch)
    paths = park_path_rows(path_elements, areas)
    log(f"paths for {len(paths)} parks")
    places.replace_park_paths(paths)
    log(f"running tracks in {places.replace_park_tracks(park_track_rows(path_elements, areas))} parks")
    return n


if __name__ == "__main__":
    if sys.argv[1:2] == ["sync"]:
        print(f"stored {sync()} OSM places in {places.db_path()}")
    elif sys.argv[1:2] == ["load"]:
        # Import from already-downloaded Overpass JSON files (areas, rivers), for testing.
        a, r = (json.load(open(p, encoding="utf-8"))["elements"] for p in sys.argv[2:4])
        places.init_db()
        conn = places._connect()
        existing = [tuple(x) for x in conn.execute("SELECT name, lat, lon FROM places WHERE source = 'data.go.kr'")]
        conn.close()
        rows = area_rows(a, existing) + river_rows(r)
        print(f"stored {places.replace_source('osm', rows)} OSM places in {places.db_path()}")
        if len(sys.argv) > 4:
            paths = park_path_rows(json.load(open(sys.argv[4], encoding="utf-8"))["elements"], rows)
            print(f"stored paths for {places.replace_park_paths(paths)} parks")
    elif sys.argv[1:2] == ["tracks"]:
        # Running tracks from an already-downloaded park paths file, written to tracks.json
        # (small: copy it to the server and `store-tracks` it there, instead of all of places.db).
        found = park_track_rows(json.load(open(sys.argv[2], encoding="utf-8"))["elements"], _park_rows_from_db())
        json.dump(found, open(sys.argv[3], "w", encoding="utf-8"), separators=(",", ":"))
        print(f"running tracks in {places.replace_park_tracks(found)} parks -> {sys.argv[3]}")
    elif sys.argv[1:2] == ["track-places"]:
        # Running tracks anywhere as places to lap (one Overpass query, a few MB), written to
        # a small JSON too for `store-track-places` on the server.
        conn = places._connect()
        rivers = [dict(r) for r in conn.execute("SELECT * FROM places WHERE kind = 'river'")]
        conn.close()
        els = overpass(TRACKS_QUERY)
        found = track_rows(els, _park_rows_from_db(), rivers)
        json.dump(found, open(sys.argv[2], "w", encoding="utf-8"), separators=(",", ":"), ensure_ascii=False)
        print(f"{places.replace_source('osm-track', found)} running tracks -> {sys.argv[2]}")
    elif sys.argv[1:2] == ["store-track-places"]:
        print(f"{places.replace_source('osm-track', json.load(open(sys.argv[2], encoding='utf-8')))} running tracks")
    elif sys.argv[1:2] == ["store-tracks"]:
        print(f"running tracks in {places.replace_park_tracks(json.load(open(sys.argv[2], encoding='utf-8')))} parks")
    else:
        sys.exit("usage: osm.py sync   (BJ_DATA_DIR set)")
