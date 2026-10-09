"""Bujirun API. Run: uvicorn server:app --host 127.0.0.1 --port 8100

Only accounts for now. Tokens are opaque strings sent as the raw `Authorization` header.
Compatibility rule (as for EnglishBite): once released, only add endpoints/optional fields -
older app builds stay installed on phones.
"""

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

import accounts
import places
import routes
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
    """Suggested loop courses of about [distance_m] starting and ending at [start]."""
    user = _user(authorization)
    if len(req.start) != 2 or not 1_000 <= req.distance_m <= 21_100:
        raise HTTPException(status_code=400, detail="1~21km 사이로 골라 주세요.")
    try:
        return {"loops": routes.loops(req.start, req.distance_m, user["id"])}
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


@app.get("/places/search")
def places_search(q: str, lat: float, lon: float, authorization: str | None = Header(None)):
    """Places with this name (or alias), the likeliest one first."""
    _user(authorization)
    found = places.find(q[:40], lat, lon)
    return {"places": found, "sources": sorted({p["source"] for p in found})}


@app.get("/health")
def health():
    return {"ok": True}
