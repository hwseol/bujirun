"""Bujirun accounts: email (unique), nickname (unique), password hash. SQLite, one file,
separate from everything else on the server.

Same design as EnglishBite's auth_db.py (PBKDF2-SHA256, random salt, opaque session tokens),
plus nickname uniqueness and the admin flag. Validation mirrors the app's AccountRules so the
two never disagree about what's acceptable.
"""

import hashlib
import os
import re
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

ADMIN_EMAIL = "admin@example.com"
SESSION_LIFETIME = timedelta(days=180)  # a phone stays signed in; logout drops the session
# ~260k rounds matches Django's default: slow enough that a stolen hash is impractical to
# brute-force, fast enough that a login doesn't feel slow.
PBKDF2_ITERATIONS = 260_000

EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")


def db_path() -> Path:
    return Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "accounts.db"


class AccountError(Exception):
    """A user-facing failure. The message is Korean and shown in the app as-is."""


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
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL UNIQUE,
                nickname TEXT NOT NULL UNIQUE COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id),
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL
            );
            """
        )
        conn.commit()
    finally:
        conn.close()


def normalize_email(email: str) -> str:
    return email.strip().lower()


def normalize_nickname(nickname: str) -> str:
    return " ".join(nickname.split())


def _hash(password: str, salt_hex: str | None = None) -> tuple[str, str]:
    salt_hex = salt_hex or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), PBKDF2_ITERATIONS)
    return digest.hex(), salt_hex


def public(row: sqlite3.Row) -> dict:
    """What the app may see about a user - never the hash or salt."""
    return {"email": row["email"], "nickname": row["nickname"], "is_admin": row["email"] == ADMIN_EMAIL}


def create_user(email: str, nickname: str, password: str) -> sqlite3.Row:
    email = normalize_email(email)
    nickname = normalize_nickname(nickname)
    if not EMAIL_RE.match(email):
        raise AccountError("이메일 형식으로 입력해 주세요.")
    if not 2 <= len(nickname) <= 12:
        raise AccountError("닉네임은 2~12자로 입력해 주세요.")
    if len(password) < 8:
        raise AccountError("비밀번호는 8자 이상이어야 해요.")

    password_hash, salt = _hash(password)
    conn = _connect()
    try:
        if conn.execute("SELECT 1 FROM users WHERE email = ?", (email,)).fetchone():
            raise AccountError("이미 가입된 이메일이에요. 로그인해 주세요.")
        if conn.execute("SELECT 1 FROM users WHERE nickname = ?", (nickname,)).fetchone():
            raise AccountError("이미 사용 중인 닉네임이에요.")
        try:
            cur = conn.execute(
                "INSERT INTO users (email, nickname, password_hash, password_salt, created_at) VALUES (?, ?, ?, ?, ?)",
                (email, nickname, password_hash, salt, datetime.now(timezone.utc).isoformat()),
            )
        except sqlite3.IntegrityError:
            # Lost a race with a simultaneous signup for the same email/nickname.
            raise AccountError("이미 가입된 이메일이거나 사용 중인 닉네임이에요.")
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    finally:
        conn.close()


def verify_login(email: str, password: str) -> sqlite3.Row:
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE email = ?", (normalize_email(email),)).fetchone()
    finally:
        conn.close()
    # Same message for "no such account" and "wrong password": don't reveal which emails exist.
    if row is None:
        raise AccountError("이메일 또는 비밀번호가 올바르지 않아요.")
    digest, _ = _hash(password, row["password_salt"])
    if not secrets.compare_digest(digest, row["password_hash"]):
        raise AccountError("이메일 또는 비밀번호가 올바르지 않아요.")
    return row


def create_session(user_id: int) -> str:
    token = secrets.token_urlsafe(32)
    now = datetime.now(timezone.utc)
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (token, user_id, now.isoformat(), (now + SESSION_LIFETIME).isoformat()),
        )
        conn.commit()
    finally:
        conn.close()
    return token


def user_by_token(token: str) -> sqlite3.Row | None:
    conn = _connect()
    try:
        s = conn.execute("SELECT * FROM sessions WHERE token = ?", (token,)).fetchone()
        if s is None:
            return None
        if datetime.fromisoformat(s["expires_at"]) < datetime.now(timezone.utc):
            conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
            conn.commit()
            return None
        return conn.execute("SELECT * FROM users WHERE id = ?", (s["user_id"],)).fetchone()
    finally:
        conn.close()


def end_session(token: str) -> None:
    conn = _connect()
    try:
        conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
        conn.commit()
    finally:
        conn.close()


def delete_user(email: str, password: str) -> None:
    """Removes the account and all its sessions. Asks for the password again rather than
    trusting a session, so a borrowed unlocked phone can't delete someone's account."""
    user = verify_login(email, password)
    conn = _connect()
    try:
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        conn.commit()
    finally:
        conn.close()


def admin_reset_password(email: str, new_password: str) -> bool:
    """Operator reset for a forgotten password (no email sender exists). Signs the account out
    everywhere. False if there's no such account."""
    conn = _connect()
    try:
        row = conn.execute("SELECT id FROM users WHERE email = ?", (normalize_email(email),)).fetchone()
        if row is None:
            return False
        h, salt = _hash(new_password)
        conn.execute("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?", (h, salt, row["id"]))
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (row["id"],))
        conn.commit()
        return True
    finally:
        conn.close()
