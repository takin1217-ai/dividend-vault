"""One sync at a time, with a lock file so a second process does not overlap."""

from __future__ import annotations

import os
import threading
import time
from datetime import datetime
from typing import Any, Callable

from dividend_vault.db import backup_vault, load_settings, open_vault
from dividend_vault.paths import LOCK_PATH, ensure_dirs
from dividend_vault.settings import enabled_markets
from dividend_vault.sync import sync_markets

JobFn = Callable[[Callable[[str, int, int], None]], Any]


class JobRunner:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.state: dict[str, Any] = {
            "running": False,
            "kind": None,
            "message": "대기",
            "done": 0,
            "total": 0,
            "error": None,
            "result": None,
            "log": [],
        }

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return dict(self.state)

    def start(self, kind: str, fn: JobFn) -> None:
        with self._lock:
            if self.state["running"]:
                raise RuntimeError("이미 작업이 진행 중입니다.")
            self.state = {
                "running": True,
                "kind": kind,
                "message": "시작",
                "done": 0,
                "total": 0,
                "error": None,
                "result": None,
                "log": [],
            }

        def progress(message: str, done: int, total: int) -> None:
            with self._lock:
                self.state["message"] = message
                self.state["done"] = done
                self.state["total"] = total
                self.state["log"] = (self.state["log"] + [message])[-40:]

        def wrap() -> None:
            try:
                result = fn(progress)
                with self._lock:
                    self.state["result"] = result
                    self.state["message"] = "완료"
            except Exception as exc:
                with self._lock:
                    self.state["error"] = str(exc)
                    self.state["message"] = "실패"
            finally:
                with self._lock:
                    self.state["running"] = False

        threading.Thread(target=wrap, daemon=True).start()


class SyncLock:
    def __enter__(self) -> "SyncLock":
        ensure_dirs()
        try:
            self.fd = os.open(str(LOCK_PATH), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            age = time.time() - LOCK_PATH.stat().st_mtime
            if age < 8 * 3600:
                raise RuntimeError("다른 동기화가 이미 진행 중입니다.")
            LOCK_PATH.unlink(missing_ok=True)
            self.fd = os.open(str(LOCK_PATH), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(self.fd, str(os.getpid()).encode())
        return self

    def __exit__(self, *_args) -> None:
        os.close(self.fd)
        LOCK_PATH.unlink(missing_ok=True)


def run_sync(markets: list[str] | None, force: bool, progress) -> dict[str, Any]:
    with SyncLock():
        conn = open_vault()
        try:
            settings = load_settings(conn)
            chosen = enabled_markets(settings, markets)
            if not chosen:
                raise RuntimeError("선택된 시장이 없습니다.")
            backup_vault(conn, "auto")
            return sync_markets(conn, settings, chosen, progress, force=force, refresh=True)
        finally:
            conn.close()


def maybe_auto_sync(runner: JobRunner) -> None:
    now = datetime.now()
    slot = None
    if now.hour == 7 and now.minute >= 20:
        slot = now.strftime("%Y-%m-%d-morning")
    elif now.hour == 17 and now.minute >= 10:
        slot = now.strftime("%Y-%m-%d-evening")
    if slot is None or runner.state.get("running"):
        return
    conn = open_vault()
    try:
        settings = load_settings(conn)
        if not settings.get("auto_daily"):
            return
        if conn.execute("SELECT value FROM meta WHERE key='auto_slot'").fetchone():
            previous = conn.execute("SELECT value FROM meta WHERE key='auto_slot'").fetchone()["value"]
            if previous == slot:
                return
        conn.execute(
            "INSERT INTO meta(key, value) VALUES('auto_slot', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (slot,),
        )
        conn.commit()
    finally:
        conn.close()
    try:
        runner.start("sync", lambda progress: run_sync(None, False, progress))
    except RuntimeError:
        return
