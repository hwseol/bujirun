"""Notices to everyone using the app (a new version, server maintenance, a tip). Added from the
server's shell; the app asks for new ones when it opens and every few hours, and shows each
once (as a dialog when the app is open, a phone notification otherwise).

    cd ~/bujirun/backend
    .venv/bin/python notices.py add "제목" "내용" [days]    # shown for [days] days (default 7)
    .venv/bin/python notices.py list
    .venv/bin/python notices.py remove <id>
"""

import os
import sqlite3
import sys
import time
from pathlib import Path


def _db():
    path = Path(os.environ.get("BJ_DATA_DIR") or Path(__file__).parent) / "notices.db"
    db = sqlite3.connect(path, timeout=10)
    db.execute("CREATE TABLE IF NOT EXISTS notices (id INTEGER PRIMARY KEY AUTOINCREMENT,"
               " title TEXT NOT NULL, body TEXT NOT NULL, created REAL NOT NULL, expires REAL NOT NULL)")
    return db


def add(title: str, body: str, days: float = 7) -> int:
    db = _db()
    try:
        now = time.time()
        cur = db.execute("INSERT INTO notices (title, body, created, expires) VALUES (?, ?, ?, ?)",
                         (title.strip(), body.strip(), now, now + days * 86400))
        db.commit()
        return cur.lastrowid
    finally:
        db.close()


def remove(notice_id: int) -> bool:
    db = _db()
    try:
        n = db.execute("DELETE FROM notices WHERE id = ?", (notice_id,)).rowcount
        db.commit()
        return n > 0
    finally:
        db.close()


def active(after: int = 0) -> list[dict]:
    """Unexpired notices newer than id [after], oldest first."""
    db = _db()
    try:
        rows = db.execute("SELECT id, title, body, created FROM notices WHERE id > ? AND expires > ? ORDER BY id",
                          (after, time.time())).fetchall()
    finally:
        db.close()
    return [{"id": r[0], "title": r[1], "body": r[2], "created": int(r[3])} for r in rows]


def _all() -> list[tuple]:
    db = _db()
    try:
        return db.execute("SELECT id, title, body, created, expires FROM notices ORDER BY id").fetchall()
    finally:
        db.close()


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) in (3, 4) and args[0] == "add":
        print("added", add(args[1], args[2], float(args[3]) if len(args) == 4 else 7))
    elif args == ["list"]:
        now = time.time()
        for i, title, body, created, expires in _all():
            state = "active" if expires > now else "expired"
            print(f"{i}\t{time.strftime('%Y-%m-%d %H:%M', time.localtime(created))}\t{state}\t{title}: {body}")
    elif len(args) == 2 and args[0] == "remove":
        print("removed" if remove(int(args[1])) else "no such notice")
    else:
        sys.exit(__doc__)
