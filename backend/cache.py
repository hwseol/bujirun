"""A small lasting cache (SQLite in the data dir) for answers that cost a TMAP request or a
planning run: walking routes, place searches, planned courses, loop suggestions. They change
rarely and are asked for again and again (the same chip tapped twice, DIY undo and redo, the
same park from the same flat) - and each TMAP request counts against a shared daily quota.

Kept across restarts, unlike the in-memory dicts these replace (a deploy used to empty them).
"""

import json
import os
import random
import sqlite3
import time
from pathlib import Path

_MAX_AGE_S = 60 * 86400  # anything older is swept out now and then, whatever its own expiry


def _db():
    path = Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "cache.db"
    db = sqlite3.connect(path, timeout=10)
    db.execute("CREATE TABLE IF NOT EXISTS kv (ns TEXT, key TEXT, at REAL, value TEXT, PRIMARY KEY (ns, key))")
    return db


def get(ns: str, key: str, max_age_s: float):
    """The value stored under (ns, key) if it's younger than [max_age_s], else None."""
    db = _db()
    try:
        row = db.execute("SELECT at, value FROM kv WHERE ns = ? AND key = ?", (ns, key)).fetchone()
    finally:
        db.close()
    if row and time.time() - row[0] < max_age_s:
        return json.loads(row[1])
    return None


def put(ns: str, key: str, value) -> None:
    db = _db()
    try:
        db.execute("INSERT OR REPLACE INTO kv VALUES (?, ?, ?, ?)",
                   (ns, key, time.time(), json.dumps(value, separators=(",", ":"), ensure_ascii=False)))
        if random.random() < 0.01:
            db.execute("DELETE FROM kv WHERE at < ?", (time.time() - _MAX_AGE_S,))
        db.commit()
    finally:
        db.close()


def clear(ns: str) -> None:
    """Forgets everything under [ns] (tests; or after changing how answers are made)."""
    db = _db()
    try:
        db.execute("DELETE FROM kv WHERE ns = ?", (ns,))
        db.commit()
    finally:
        db.close()


def key_of(*parts) -> str:
    return json.dumps(parts, separators=(",", ":"), ensure_ascii=False, sort_keys=True)
