"""Each account's records kept on the server, so a new phone or a reinstalled app gets them back.

The app owns the records; the server only holds a copy per account, item by item: a workout with
its track, a saved course with its points, an auto-recorded walk, the daily step counts. Each
item has a key the app picks (stable across installs, e.g. "w:<start ms>") and a hash of its
content, so a sync is one list call plus uploads of what changed and downloads of what's missing.
Contents are stored gzipped and are opaque here (JSON the app writes and reads back).
"""

import gzip
import json
import os
import re
import sqlite3
import time
from pathlib import Path

MAX_ITEM_BYTES = 4 * 1024 * 1024        # one workout of several hours with a dense track is ~1 MB
MAX_ITEMS_PER_USER = 20_000
MAX_BYTES_PER_USER = 300 * 1024 * 1024  # stored (gzipped) bytes
KEY_RE = re.compile(r"^[a-z]{1,8}:[A-Za-z0-9._-]{1,64}$|^steps$")


class RecordError(Exception):
    """A user-facing failure (Korean, shown in the app)."""


def _db() -> sqlite3.Connection:
    path = Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "records.db"
    db = sqlite3.connect(path, timeout=10)
    db.execute(
        "CREATE TABLE IF NOT EXISTS items (user_id INTEGER NOT NULL, key TEXT NOT NULL, hash TEXT NOT NULL,"
        " data BLOB NOT NULL, updated REAL NOT NULL, PRIMARY KEY (user_id, key))"
    )
    return db


def list_items(user_id: int) -> list[dict]:
    db = _db()
    try:
        return [{"key": k, "hash": h} for k, h in db.execute("SELECT key, hash FROM items WHERE user_id = ?", (user_id,))]
    finally:
        db.close()


def get_item(user_id: int, key: str):
    db = _db()
    try:
        row = db.execute("SELECT data FROM items WHERE user_id = ? AND key = ?", (user_id, key)).fetchone()
    finally:
        db.close()
    return None if row is None else json.loads(gzip.decompress(row[0]))


def put_item(user_id: int, key: str, content_hash: str, data) -> None:
    if not KEY_RE.match(key) or not re.match(r"^[0-9a-f]{8,64}$", content_hash or ""):
        raise RecordError("잘못된 기록이에요.")
    blob = gzip.compress(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode())
    if len(blob) > MAX_ITEM_BYTES:
        raise RecordError("기록 하나가 너무 커서 저장하지 못했어요.")
    db = _db()
    try:
        count, total = db.execute(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(data)), 0) FROM items WHERE user_id = ? AND key != ?", (user_id, key)
        ).fetchone()
        if count >= MAX_ITEMS_PER_USER or total + len(blob) > MAX_BYTES_PER_USER:
            raise RecordError("서버에 저장할 수 있는 기록 양을 넘었어요.")
        db.execute(
            "INSERT OR REPLACE INTO items (user_id, key, hash, data, updated) VALUES (?, ?, ?, ?, ?)",
            (user_id, key, content_hash, blob, time.time()),
        )
        db.commit()
    finally:
        db.close()


def delete_item(user_id: int, key: str) -> None:
    db = _db()
    try:
        db.execute("DELETE FROM items WHERE user_id = ? AND key = ?", (user_id, key))
        db.commit()
    finally:
        db.close()


def delete_user(user_id: int) -> None:
    """Everything of an account, when the account is deleted."""
    db = _db()
    try:
        db.execute("DELETE FROM items WHERE user_id = ?", (user_id,))
        db.commit()
    finally:
        db.close()
