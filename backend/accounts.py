"""Bujirun accounts: email (unique), nickname (unique), password hash. SQLite, one file,
separate from everything else on the server.

Same design as EnglishBite's auth_db.py (PBKDF2-SHA256, random salt, opaque session tokens),
plus nickname uniqueness and the admin flag. Validation mirrors the app's AccountRules so the
two never disagree about what's acceptable.
"""

import hashlib
import json
import os
import urllib.parse
import urllib.request
import re
import secrets
import smtplib
import sqlite3
from email.message import EmailMessage
from datetime import datetime, timedelta, timezone
from pathlib import Path

# The admin account's email, set on the server (BUJIRUN_ADMIN_EMAIL in the root-only systemd
# secret file) rather than in this public repo. Unset means no admin.
ADMIN_EMAIL = os.environ.get("BUJIRUN_ADMIN_EMAIL", "").strip().lower()
SESSION_LIFETIME = timedelta(days=180)  # a phone stays signed in; logout drops the session
# ~260k rounds matches Django's default: slow enough that a stolen hash is impractical to
# brute-force, fast enough that a login doesn't feel slow.
PBKDF2_ITERATIONS = 260_000

# Google sign-in: the OAuth web client ID the app asks Google for an ID token with (public, but
# set on the server like the rest of its configuration). Unset means Google sign-in is off.
GOOGLE_CLIENT_ID = os.environ.get("BUJIRUN_GOOGLE_CLIENT_ID", "").strip()

# Password reset codes go out by email through an SMTP account set on the server (e.g. Gmail
# with an app password): BUJIRUN_SMTP_HOST / _PORT / _USER / _PASSWORD, and _FROM for the
# sender shown. Unset means no email: the app then offers Google sign-in or the operator.
SMTP = {k: os.environ.get(f"BUJIRUN_SMTP_{k.upper()}", "").strip() for k in ("host", "port", "user", "password", "from")}
RESET_CODE_LIFETIME = timedelta(minutes=15)
RESET_MAX_TRIES = 5          # wrong codes before the code is void
RESET_RESEND_AFTER = timedelta(seconds=60)

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
            CREATE TABLE IF NOT EXISTS password_resets (
                user_id INTEGER PRIMARY KEY REFERENCES users(id),
                code_hash TEXT NOT NULL,
                code_salt TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                sent_at TEXT NOT NULL,
                tries INTEGER NOT NULL DEFAULT 0
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
    return {"email": row["email"], "nickname": row["nickname"], "is_admin": bool(ADMIN_EMAIL) and row["email"] == ADMIN_EMAIL,
            "has_password": bool(row["password_hash"]), "created_at": row["created_at"]}


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
    if not row["password_hash"]:
        # A Google account: it signs in with Google, not a password.
        raise AccountError("구글로 가입한 계정이에요. 구글로 로그인해 주세요.")
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
        conn.execute("DELETE FROM password_resets WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        conn.commit()
    finally:
        conn.close()


def change_nickname(user_id: int, nickname: str) -> sqlite3.Row:
    nickname = normalize_nickname(nickname)
    if not 2 <= len(nickname) <= 12:
        raise AccountError("닉네임은 2~12자로 입력해 주세요.")
    conn = _connect()
    try:
        taken = conn.execute("SELECT id FROM users WHERE nickname = ? AND id != ?", (nickname, user_id)).fetchone()
        if taken:
            raise AccountError("이미 사용 중인 닉네임이에요.")
        try:
            conn.execute("UPDATE users SET nickname = ? WHERE id = ?", (nickname, user_id))
        except sqlite3.IntegrityError:
            raise AccountError("이미 사용 중인 닉네임이에요.")
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    finally:
        conn.close()


def has_password(row: sqlite3.Row) -> bool:
    """Accounts made through Google sign-in have no password of their own."""
    return bool(row["password_hash"])


def delete_by_session(user: sqlite3.Row, password: str | None) -> None:
    """Deletes the signed-in account: with its password when it has one (a borrowed unlocked
    phone can't delete it), or just the session for a Google account (Google already vouched)."""
    if has_password(user):
        verify_login(user["email"], password or "")
    conn = _connect()
    try:
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        conn.commit()
    finally:
        conn.close()


def verify_google_token(id_token: str, fetch=None) -> dict:
    """Google's own check of an ID token (one small request per sign-in): it must be for our
    client and carry a verified email. Returns the token's claims."""
    if not GOOGLE_CLIENT_ID:
        raise AccountError("구글 로그인은 아직 준비 중이에요.")
    url = "https://oauth2.googleapis.com/tokeninfo?" + urllib.parse.urlencode({"id_token": id_token})
    try:
        if fetch:
            claims = fetch(url)
        else:
            with urllib.request.urlopen(url, timeout=10) as r:
                claims = json.loads(r.read())
    except Exception:
        raise AccountError("구글 로그인을 확인하지 못했어요. 다시 시도해 주세요.")
    if claims.get("aud") != GOOGLE_CLIENT_ID or claims.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise AccountError("구글 로그인을 확인하지 못했어요. 다시 시도해 주세요.")
    if str(claims.get("email_verified")).lower() != "true" or not claims.get("email"):
        raise AccountError("구글 계정의 이메일이 확인되지 않았어요.")
    return claims


def google_user(claims: dict) -> sqlite3.Row:
    """The account for a Google sign-in: the one with that email (it's verified by Google), or
    a new one named after the Google profile, made unique with a number if needed."""
    email = normalize_email(claims["email"])
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
        if row:
            return row
        base = normalize_nickname(claims.get("name") or email.split("@")[0])[:10] or "러너"
        if len(base) < 2:
            base = (base + "러너")[:10]
        nickname = base
        n = 1
        while conn.execute("SELECT 1 FROM users WHERE nickname = ?", (nickname,)).fetchone():
            n += 1
            nickname = f"{base[:12 - len(str(n))]}{n}"
        cur = conn.execute(
            "INSERT INTO users (email, nickname, password_hash, password_salt, created_at) VALUES (?, ?, '', '', ?)",
            (email, nickname, datetime.now(timezone.utc).isoformat()),
        )
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    finally:
        conn.close()


def can_email() -> bool:
    return bool(SMTP["host"] and SMTP["user"] and SMTP["password"])


def _send_mail(to: str, subject: str, body: str) -> None:
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = SMTP["from"] or SMTP["user"]
    msg["To"] = to
    msg.set_content(body)
    with smtplib.SMTP(SMTP["host"], int(SMTP["port"] or 587), timeout=15) as s:
        s.starttls()
        s.login(SMTP["user"], SMTP["password"])
        s.send_message(msg)


def request_reset(email: str, send=None) -> None:
    """Emails a 6-digit code for a password reset. Says nothing about whether the account
    exists (the app shows the same message either way); a Google-made account gets a code too,
    which lets it add a password."""
    if send is None and not can_email():
        raise AccountError("지금은 이메일로 재설정할 수 없어요. 운영자에게 문의해 주세요.")
    email = normalize_email(email)
    now = datetime.now(timezone.utc)
    conn = _connect()
    try:
        row = conn.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()
        if row is None:
            return
        prev = conn.execute("SELECT sent_at FROM password_resets WHERE user_id = ?", (row["id"],)).fetchone()
        if prev and now - datetime.fromisoformat(prev["sent_at"]) < RESET_RESEND_AFTER:
            raise AccountError("방금 코드를 보냈어요. 1분 뒤에 다시 시도해 주세요.")
        code = f"{secrets.randbelow(1_000_000):06d}"
        h, salt = _hash(code)
        conn.execute(
            "INSERT OR REPLACE INTO password_resets (user_id, code_hash, code_salt, expires_at, sent_at, tries) VALUES (?, ?, ?, ?, ?, 0)",
            (row["id"], h, salt, (now + RESET_CODE_LIFETIME).isoformat(), now.isoformat()),
        )
        conn.commit()
    finally:
        conn.close()
    body = (f"부지런 비밀번호 재설정 코드: {code}\n\n"
            "앱의 '비밀번호 찾기' 화면에 입력해 주세요. 15분 동안 쓸 수 있어요.\n"
            "직접 요청하지 않았다면 이 메일은 무시하셔도 돼요.")
    try:
        (send or _send_mail)(email, f"[부지런] 비밀번호 재설정 코드 {code}", body)
    except Exception:
        raise AccountError("메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.")


def confirm_reset(email: str, code: str, new_password: str) -> sqlite3.Row:
    """Sets a new password with the emailed code; other sessions are signed out."""
    if len(new_password) < 8:
        raise AccountError("비밀번호는 8자 이상이어야 해요.")
    wrong = AccountError("코드가 맞지 않거나 만료됐어요. 다시 받아 주세요.")
    conn = _connect()
    try:
        user = conn.execute("SELECT * FROM users WHERE email = ?", (normalize_email(email),)).fetchone()
        r = user and conn.execute("SELECT * FROM password_resets WHERE user_id = ?", (user["id"],)).fetchone()
        if not r or datetime.now(timezone.utc) > datetime.fromisoformat(r["expires_at"]) or r["tries"] >= RESET_MAX_TRIES:
            raise wrong
        if not secrets.compare_digest(_hash(code.strip(), r["code_salt"])[0], r["code_hash"]):
            conn.execute("UPDATE password_resets SET tries = tries + 1 WHERE user_id = ?", (user["id"],))
            conn.commit()
            raise wrong
        h, salt = _hash(new_password)
        conn.execute("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?", (h, salt, user["id"]))
        conn.execute("DELETE FROM password_resets WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
    finally:
        conn.close()


def change_password(user: sqlite3.Row, current: str | None, new_password: str) -> sqlite3.Row:
    """From the account page: needs the current password if there is one (a Google-made
    account can add its first password without)."""
    if len(new_password) < 8:
        raise AccountError("비밀번호는 8자 이상이어야 해요.")
    if has_password(user):
        try:
            verify_login(user["email"], current or "")
        except AccountError:
            raise AccountError("지금 비밀번호가 맞지 않아요.")
    h, salt = _hash(new_password)
    conn = _connect()
    try:
        conn.execute("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?", (h, salt, user["id"]))
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
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
