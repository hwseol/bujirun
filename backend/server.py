"""Bujirun API. Run: uvicorn server:app --host 127.0.0.1 --port 8100

Only accounts for now. Tokens are opaque strings sent as the raw `Authorization` header.
Compatibility rule (as for EnglishBite): once released, only add endpoints/optional fields -
older app builds stay installed on phones.
"""

import time

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

import accounts
import places
import planner
import routes
import shade
import trails

app = FastAPI(title="Bujirun API")
accounts.init_db()


class RouteRequest(BaseModel):
    start: list[float]  # [lat, lon]
    end: list[float]


class LoopRequest(BaseModel):
    start: list[float]  # [lat, lon]
    distance_m: float


def _user(authorization: str | None):
    user = accounts.user_by_token(authorization) if authorization else None
    if user is None:
        raise HTTPException(status_code=401, detail="다시 로그인해 주세요.")
    return user


class SignupRequest(BaseModel):
    email: str
    nickname: str
    password: str


class LoginRequest(BaseModel):
    email: str
    password: str


def _fail(e: accounts.AccountError):
    # 400 with a Korean message the app shows directly.
    raise HTTPException(status_code=400, detail=str(e))


@app.post("/auth/signup")
def signup(req: SignupRequest):
    try:
        user = accounts.create_user(req.email, req.nickname, req.password)
    except accounts.AccountError as e:
        _fail(e)
    return {"token": accounts.create_session(user["id"]), **accounts.public(user)}


@app.post("/auth/login")
def login(req: LoginRequest):
    try:
        user = accounts.verify_login(req.email, req.password)
    except accounts.AccountError as e:
        _fail(e)
    return {"token": accounts.create_session(user["id"]), **accounts.public(user)}


@app.get("/auth/me")
def me(authorization: str | None = Header(None)):
    user = accounts.user_by_token(authorization) if authorization else None
    if user is None:
        raise HTTPException(status_code=401, detail="다시 로그인해 주세요.")
    return accounts.public(user)


@app.post("/auth/logout")
def logout(authorization: str | None = Header(None)):
    if authorization:
        accounts.end_session(authorization)
    return {"ok": True}


@app.post("/auth/delete")
def delete_account(req: LoginRequest):
    try:
        accounts.delete_user(req.email, req.password)
    except accounts.AccountError as e:
        _fail(e)
    return {"ok": True}


@app.post("/routes/pedestrian")
def pedestrian_route(req: RouteRequest, authorization: str | None = Header(None)):
    """A walking route between two points, for drawing courses. Signed-in users only (it spends
    a shared daily TMAP quota)."""
    user = _user(authorization)
    if len(req.start) != 2 or len(req.end) != 2:
        raise HTTPException(status_code=400, detail="잘못된 위치예요.")
    try:
        routes.charge(user["id"])
        return routes.pedestrian(req.start, req.end)
    except routes.RouteError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.post("/routes/loops")
def loop_routes(req: LoopRequest, authorization: str | None = Header(None)):
    """Suggested loop courses of about [distance_m] starting and ending at [start]. The app asks
    again whenever the distance chip changes, so answers are kept a week per ~100 m spot and
    distance: flipping between chips costs no further TMAP requests."""
    user = _user(authorization)
    if len(req.start) != 2 or not 1_000 <= req.distance_m <= 21_100:
        raise HTTPException(status_code=400, detail="1~21km 사이로 골라 주세요.")
    key = (round(req.start[0], 3), round(req.start[1], 3), round(req.distance_m))
    hit = _loop_cache.get(key)
    if hit and time.time() - hit[0] < planner.CACHE_S:
        return {"loops": hit[1]}
    try:
        loops = routes.loops(req.start, req.distance_m, user["id"])
    except routes.RouteError as e:
        # Circles drawn around the start can all fail where half of them lands in a river or
        # on a highway; a loop through the parks nearby usually still exists.
        try:
            plan = planner.Planner(user["id"]).plan(req.start, {"distance_m": req.distance_m, "themes": ["PARK"]})
            loops = [{"distance_m": c["distance_m"], "points": c["points"]} for c in plan.courses]
        except routes.RouteError:
            raise HTTPException(status_code=400, detail=str(e))
    if shade.has_map():
        for c in loops:
            c["shade"] = round(shade.ratio(c["points"]) * 100)
        if shade.strong_sun(*req.start):
            loops.sort(key=lambda c: -c["shade"])
    _loop_cache[key] = (time.time(), loops)
    if len(_loop_cache) > 2000:
        for k in sorted(_loop_cache, key=lambda k: _loop_cache[k][0])[:500]:
            del _loop_cache[k]
    return {"loops": loops}


_loop_cache: dict = {}


class PlanPlace(BaseModel):
    name: str
    role: str = "AROUND"  # START / DEST / AROUND
    id: str | None = None  # a place picked from /places/parks: no lookup by name


class PlanIntent(BaseModel):
    distance_m: float | None = None
    shape: str = "LOOP"  # LOOP / OUT_AND_BACK / ONE_WAY
    themes: list[str] = []
    places: list[PlanPlace] = []
    flat: bool = False
    laps: int | None = None   # "세 바퀴"
    repeat: bool = False      # 뺑뺑이, 바퀴, 트랙: laps of one place rather than a tour
    shade: bool | None = None  # prefer tree shade; None = automatic (while the sun is high)


class PlanRequest(BaseModel):
    start: list[float]  # [lat, lon]
    intent: PlanIntent


@app.post("/routes/plan")
def plan_route(req: PlanRequest, authorization: str | None = Header(None)):
    """A course for a request the app has already read ("한강 따라 5km" -> distance, shape,
    themes, places). See planner.py."""
    user = _user(authorization)
    i = req.intent
    if len(req.start) != 2 or (i.distance_m is not None and not 500 <= i.distance_m <= 42_200) or len(i.places) > 3:
        raise HTTPException(status_code=400, detail="요청을 이해하지 못했어요.")
    intent = {
        "distance_m": i.distance_m, "shape": i.shape, "themes": i.themes[:5], "flat": i.flat,
        "laps": i.laps if i.laps and 1 <= i.laps <= 30 else None, "repeat": i.repeat, "shade": i.shade,
        "places": [{"name": p.name[:30], "role": p.role, **({"id": p.id[:40]} if p.id else {})} for p in i.places],
    }
    try:
        return planner.cached_plan(req.start, intent, planner.Planner(user["id"]))
    except routes.RouteError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/trails/nearby")
def trails_nearby(lat: float, lon: float, authorization: str | None = Header(None)):
    """Official walking trails closest to (lat, lon), with a light preview line each."""
    _user(authorization)
    return {"trails": trails.nearby(lat, lon), "source": trails.SOURCE}


@app.get("/trails/{trail_id}")
def trail(trail_id: str, authorization: str | None = Header(None)):
    _user(authorization)
    t = trails.get(trail_id)
    if t is None:
        raise HTTPException(status_code=404, detail="코스를 찾을 수 없어요.")
    return t


@app.get("/places/nearby")
def places_nearby(lat: float, lon: float, radius_m: float = 3000, authorization: str | None = Header(None)):
    """Parks, lakes, beaches and rivers worth running at near (lat, lon), nearest first."""
    _user(authorization)
    found = places.nearby(lat, lon, min(radius_m, 10_000))
    return {"places": found, "sources": sorted({p["source"] for p in found})}


@app.get("/places/parks")
def places_parks(lat: float, lon: float, authorization: str | None = Header(None)):
    """Parks and lakes nearby that can be lapped, nearest first (공원 코스)."""
    _user(authorization)
    found = places.lap_parks(lat, lon)
    if shade.has_map():
        for p in found:
            p["shade"] = round(shade.canopy(places.shape(p["id"]) or []) * 100)
    return {"places": found, "sources": sorted({p["source"] for p in found} | ({shade.SOURCE} if shade.has_map() else set()))}


@app.get("/places/search")
def places_search(q: str, lat: float, lon: float, authorization: str | None = Header(None)):
    """Places with this name (or alias), the likeliest one first."""
    _user(authorization)
    found = places.find(q[:40], lat, lon)
    return {"places": found, "sources": sorted({p["source"] for p in found})}


@app.get("/health")
def health():
    return {"ok": True}
