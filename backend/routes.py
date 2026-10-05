"""Walking routes between two points, via TMAP's pedestrian route API.

The TMAP app key lives only on the server (env TMAP_APP_KEY, set in a root-only systemd
drop-in); the app asks us, we ask TMAP. That keeps the key out of the APK and lets us cap usage
per user - the free plan allows 1,000 route requests a day for everyone combined.
"""

import json
import os
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


def pedestrian(start: list[float], end: list[float]) -> dict:
    key = os.environ.get("TMAP_APP_KEY")
    if not key:
        raise RouteError("경로 계산을 지금 쓸 수 없어요.")
    body = {
        "startX": start[1], "startY": start[0], "endX": end[1], "endY": end[0],
        # TMAP requires names, URL-encoded; they never reach the user.
        "startName": urllib.parse.quote("출발"), "endName": urllib.parse.quote("도착"),
        "reqCoordType": "WGS84GEO", "resCoordType": "WGS84GEO", "searchOption": "0",
    }
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
