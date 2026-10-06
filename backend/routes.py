"""Walking routes between two points, via TMAP's pedestrian route API.

The TMAP app key lives only on the server (env TMAP_APP_KEY, set in a root-only systemd
drop-in); the app asks us, we ask TMAP. That keeps the key out of the APK and lets us cap usage
per user - the free plan allows 1,000 route requests a day for everyone combined.
"""

import json
import math
import os
import random
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

TMAP_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1"
PER_USER_DAILY = 300


class RouteError(Exception):
    """User-facing (Korean) failure."""


_usage: dict[tuple[int, date], int] = {}


def charge(user_id: int) -> None:
    """Counts a request against the user's daily allowance (in memory: resets on restart, which
    is fine for a soft cap whose real job is stopping one account from burning the shared quota)."""
    key = (user_id, date.today())
    used = _usage.get(key, 0)
    if used >= PER_USER_DAILY:
        raise RouteError("오늘 경로 계산 횟수를 다 썼어요. 내일 다시 해 주세요.")
    _usage[key] = used + 1
    for k in [k for k in _usage if k[1] != key[1]]:
        del _usage[k]


def parse_tmap(data: dict) -> dict:
    """TMAP's GeoJSON -> {distance_m, points: [[lat, lon], ...]}. The route geometry is the
    LineString features in order; consecutive ones share an endpoint, which is dropped."""
    features = data.get("features") or []
    if not features:
        raise RouteError("경로를 찾지 못했어요.")
    distance = features[0].get("properties", {}).get("totalDistance")
    points: list[list[float]] = []
    for f in features:
        geom = f.get("geometry") or {}
        if geom.get("type") != "LineString":
            continue
        for lon, lat in geom.get("coordinates", []):
            p = [round(lat, 7), round(lon, 7)]
            if not points or points[-1] != p:
                points.append(p)
    if len(points) < 2:
        raise RouteError("경로를 찾지 못했어요.")
    return {"distance_m": distance, "points": points}


def pedestrian(start: list[float], end: list[float], via: list[list[float]] | None = None) -> dict:
    """A walking route start -> (via...) -> end. TMAP takes up to 5 via points in one request."""
    key = os.environ.get("TMAP_APP_KEY")
    if not key:
        raise RouteError("경로 계산을 지금 쓸 수 없어요.")
    body = {
        "startX": start[1], "startY": start[0], "endX": end[1], "endY": end[0],
        # TMAP requires names, URL-encoded; they never reach the user.
        "startName": urllib.parse.quote("출발"), "endName": urllib.parse.quote("도착"),
        "reqCoordType": "WGS84GEO", "resCoordType": "WGS84GEO", "searchOption": "0",
    }
    if via:
        body["passList"] = "_".join(f"{p[1]:.7f},{p[0]:.7f}" for p in via[:5])
    req = urllib.request.Request(
        TMAP_URL, data=json.dumps(body).encode(), method="POST",
        headers={"appKey": key, "Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return parse_tmap(json.load(r))
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise RouteError("경로 계산이 잠시 몰렸어요. 잠시 후 다시 해 주세요.")
        # 400s here are mostly "no walkable route" (e.g. a point in the river) or out of range.
        raise RouteError("이 두 지점 사이의 걷는 길을 찾지 못했어요.")
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise RouteError("경로를 계산하지 못했어요. 잠시 후 다시 해 주세요.")


# --- Loop suggestions -------------------------------------------------------------------------

M_PER_DEG = 111_195.0
# Walking networks are longer than the circle through their waypoints: streets aren't straight
# and the router detours to crossings. 1.25 is a typical city ratio; each try corrects for it.
DETOUR = 1.25


def circle_waypoints(start: list[float], radius_m: float, theta: float, n: int = 3) -> list[list[float]]:
    """[n] points on a circle of [radius_m] that passes through [start]: the centre sits
    [radius_m] from start in direction [theta] (radians, clockwise from north), and the points
    are spread evenly around the rest of the circle. Routing start -> points -> start then
    makes a loop."""
    kx = M_PER_DEG * math.cos(math.radians(start[0]))
    cx = radius_m * math.sin(theta)
    cy = radius_m * math.cos(theta)
    start_angle = math.atan2(-cx, -cy)  # angle of start as seen from the centre
    pts = []
    for k in range(1, n + 1):
        a = start_angle + 2 * math.pi * k / (n + 1)
        x = cx + radius_m * math.sin(a)
        y = cy + radius_m * math.cos(a)
        pts.append([start[0] + y / M_PER_DEG, start[1] + x / kx])
    return pts


def overlap_ratio(points: list[list[float]], cell_m: float = 20.0, gap_m: float = 80.0) -> float:
    """Share of the route that goes back over ground it already covered (out-and-back on the
    same street). Samples every 10 m into a grid; a sample revisits if its cell was first seen
    more than [gap_m] earlier along the route."""
    if len(points) < 2:
        return 0.0
    kx = M_PER_DEG * math.cos(math.radians(points[0][0]))
    xy = [((p[1] - points[0][1]) * kx, (p[0] - points[0][0]) * M_PER_DEG) for p in points]
    first_seen: dict[tuple[int, int], float] = {}
    along = 0.0
    total = revisits = 0
    for (x1, y1), (x2, y2) in zip(xy, xy[1:]):
        seg = math.hypot(x2 - x1, y2 - y1)
        steps = max(1, int(seg / 10))
        for s in range(steps):
            t = s / steps
            x, y = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
            d = along + seg * t
            cell = (int(x // cell_m), int(y // cell_m))
            seen = first_seen.setdefault(cell, d)
            total += 1
            if d - seen > gap_m:
                revisits += 1
        along += seg
    return revisits / total if total else 0.0


def remove_spurs(points: list[list[float]], tol_m: float = 12.0, max_spur_m: float = 600.0) -> list[list[float]]:
    """Cuts dead-end detours: when the route comes back within [tol_m] of a point it passed less
    than [max_spur_m] of path ago, everything in between was a there-and-back stub (a waypoint
    that landed at the end of a cul-de-sac) and is dropped."""
    if len(points) < 3:
        return points
    kx = M_PER_DEG * math.cos(math.radians(points[0][0]))

    def d(a, b):
        return math.hypot((b[1] - a[1]) * kx, (b[0] - a[0]) * M_PER_DEG)

    out: list[list[float]] = []
    along: list[float] = []  # path distance at each kept point
    for p in points:
        if out:
            here = along[-1] + d(out[-1], p)
            # Look back over the recent stretch for a point we're returning to (skip the
            # immediate neighbours: those are just the next step of the line).
            cut = None
            for i in range(len(out) - 3, -1, -1):
                if here - along[i] > max_spur_m:
                    break
                if d(out[i], p) <= tol_m and here - along[i] > 3 * tol_m:
                    cut = i
            if cut is not None:
                del out[cut + 1:]
                del along[cut + 1:]
                here = along[-1] + d(out[-1], p)
            out.append(p)
            along.append(here)
        else:
            out.append(p)
            along.append(0.0)
    return out


def route_length(points: list[list[float]]) -> float:
    kx = M_PER_DEG * math.cos(math.radians(points[0][0]))
    return sum(
        math.hypot((b[1] - a[1]) * kx, (b[0] - a[0]) * M_PER_DEG) for a, b in zip(points, points[1:])
    )


def loops(start: list[float], distance_m: float, user_id: int, count: int = 3, seed: int | None = None,
          route=None) -> list[dict]:
    """Up to [count] loop routes from [start] of about [distance_m], in different directions.
    Each direction gets up to two tries: the second rescales the circle by how far the first
    route's length missed. Loops that mostly double back on themselves are dropped.
    [route] is injectable for tests (defaults to TMAP)."""
    route = route or (lambda s, e, via: pedestrian(s, e, via))
    rng = random.Random(seed)
    base = rng.uniform(0, 2 * math.pi)
    results = []
    for k in range(count):
        theta = base + 2 * math.pi * k / count
        radius = distance_m / (2 * math.pi * DETOUR)
        best = None
        for _ in range(2):
            charge(user_id)
            try:
                r = route(start, start, circle_waypoints(start, radius, theta))
            except RouteError:
                break
            pts = remove_spurs(r["points"])
            length = route_length(pts)
            ratio = length / distance_m
            candidate = {"distance_m": round(length), "points": pts, "ratio": ratio}
            if best is None or abs(ratio - 1) < abs(best["ratio"] - 1):
                best = candidate
            if 0.88 <= ratio <= 1.12:
                break
            radius *= min(2.0, max(0.5, 1 / ratio))
        if best is None or not 0.75 <= best["ratio"] <= 1.3:
            continue
        if overlap_ratio(best["points"]) > 0.4:
            continue
        results.append({"distance_m": best["distance_m"], "points": best["points"]})
    if not results:
        raise RouteError("이 근처에서는 알맞은 순환 코스를 찾지 못했어요. 다른 거리로 해 보세요.")
    results.sort(key=lambda c: abs(c["distance_m"] - distance_m))
    return results
