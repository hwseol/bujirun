"""Bujirun API. Run: uvicorn server:app --host 127.0.0.1 --port 8100

Only accounts for now. Tokens are opaque strings sent as the raw `Authorization` header.
Compatibility rule (as for EnglishBite): once released, only add endpoints/optional fields -
older app builds stay installed on phones.
"""

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

import accounts
import routes

app = FastAPI(title="Bujirun API")
accounts.init_db()


class RouteRequest(BaseModel):
    start: list[float]  # [lat, lon]
    end: list[float]


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


@app.get("/health")
def health():
    return {"ok": True}
