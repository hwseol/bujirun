"""Daily copy of the Bujirun data that can't be rebuilt (run by bujirun-backup.timer).

accounts.db and notices.db go into ~/bujirun/backups/<date>/ through sqlite's online backup
(safe while the API runs), each checked with integrity_check; the last KEEP_DAYS days are kept.
The big built databases (places, trails, waterside) are copied gzipped only when they change,
latest only, into ~/bujirun/backups/built/. cache.db and landmarks.db refill themselves.
"""

import gzip
import os
import shutil
import sqlite3
import sys
from datetime import date
from pathlib import Path

DATA = Path(os.environ.get("BJ_DATA_DIR") or Path.home() / "bujirun" / "data")
OUT = Path(os.environ.get("BJ_BACKUP_DIR") or Path.home() / "bujirun" / "backups")
KEEP_DAYS = 14
USER_DBS = ("accounts", "notices")
BUILT_DBS = ("places", "trails", "waterside")


def snapshot(src: Path, dst: Path) -> None:
    s = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    d = sqlite3.connect(dst)
    try:
        s.backup(d)
        ok = d.execute("PRAGMA integrity_check").fetchone()[0]
    finally:
        d.close()
        s.close()
    if ok != "ok":
        raise RuntimeError(f"{dst}: integrity_check said {ok}")


def main() -> int:
    day = OUT / date.today().isoformat()
    day.mkdir(parents=True, exist_ok=True)
    for name in USER_DBS:
        src = DATA / f"{name}.db"
        if src.exists():
            snapshot(src, day / f"{name}.db")
            print(f"{name}.db -> {day}")

    built = OUT / "built"
    built.mkdir(exist_ok=True)
    for name in BUILT_DBS:
        src = DATA / f"{name}.db"
        dst = built / f"{name}.db.gz"
        if not src.exists() or (dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime):
            continue
        tmp = built / f"{name}.db.tmp"
        snapshot(src, tmp)
        with open(tmp, "rb") as f, gzip.open(dst.with_suffix(".gz.part"), "wb", compresslevel=6) as g:
            shutil.copyfileobj(f, g)
        tmp.unlink()
        dst.with_suffix(".gz.part").replace(dst)
        os.utime(dst, (src.stat().st_mtime, src.stat().st_mtime))
        print(f"{name}.db changed -> {dst}")

    days = sorted(p for p in OUT.iterdir() if p.is_dir() and p.name[:4].isdigit())
    for old in days[:-KEEP_DAYS]:
        shutil.rmtree(old)
        print(f"removed {old}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
