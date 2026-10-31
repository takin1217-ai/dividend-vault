"""SQLite vault. Bars are updated in place and the previous value is kept."""

from __future__ import annotations

import json
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from dividend_vault.paths import BACKUPS, DB_PATH, SCHEMA_PATH, ensure_dirs
from dividend_vault.settings import FLOOR_VERSION, HOLDING_FLOORS, dump_settings, normalize_settings

BAR_FIELDS = ("open", "high", "low", "close", "adj_close", "volume")


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def connect(path: Path | None = None, readonly: bool = False) -> sqlite3.Connection:
    ensure_dirs()
    target = path or DB_PATH
    if readonly and not target.exists():
        raise FileNotFoundError(target)
    uri = target.resolve().as_uri()
    if readonly:
        conn = sqlite3.connect(f"{uri}?mode=ro", uri=True, timeout=60)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(target, timeout=60)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=60000")
    conn.execute("PRAGMA foreign_keys=ON")
    if not readonly:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def migrate(conn: sqlite3.Connection) -> None:
    script = SCHEMA_PATH.read_text(encoding="utf-8")
    conn.executescript(script)
    if conn.execute("SELECT value FROM meta WHERE key='settings'").fetchone() is None:
        conn.execute(
            "INSERT INTO meta(key, value) VALUES('settings', ?)",
            (dump_settings(normalize_settings(None)),),
        )
        conn.commit()


def open_vault(path: Path | None = None) -> sqlite3.Connection:
    conn = connect(path)
    migrate(conn)
    return conn


def load_settings(conn: sqlite3.Connection) -> dict[str, Any]:
    row = conn.execute("SELECT value FROM meta WHERE key='settings'").fetchone()
    raw = json.loads(row["value"]) if row else None
    settings = normalize_settings(raw)
    marker = conn.execute("SELECT value FROM meta WHERE key='holding_floor'").fetchone()
    if marker is None or marker["value"] != FLOOR_VERSION:
        for market in settings["markets"]:
            minimum, target = HOLDING_FLOORS[market["code"]]
            market["min_symbols"] = minimum
            market["target_symbols"] = target
            market["enabled"] = True
        save_settings(conn, settings)
        conn.execute(
            "INSERT INTO meta(key, value) VALUES('holding_floor', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (FLOOR_VERSION,),
        )
        conn.commit()
        return settings
    if row is None or dump_settings(raw or {}) != dump_settings(settings):
        save_settings(conn, settings)
    return settings


def save_settings(conn: sqlite3.Connection, settings: dict[str, Any]) -> dict[str, Any]:
    clean = normalize_settings(settings)
    conn.execute(
        "INSERT INTO meta(key, value) VALUES('settings', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (dump_settings(clean),),
    )
    conn.commit()
    return clean


def _changed(old: float | None, new: float | None) -> bool:
    if old is None and new is None:
        return False
    if old is None or new is None:
        return True
    scale = max(abs(old), abs(new), 1e-9)
    return abs(old - new) / scale > 1e-8


def upsert_symbol(conn: sqlite3.Connection, row: dict[str, Any]) -> None:
    now = utc_now()
    conn.execute(
        """
        INSERT INTO symbols(
          symbol, market, name, instrument, exchange, currency, yield_frac, market_cap,
          avg_volume, selected, pool_rank, listed_date, select_note, updated_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(symbol) DO UPDATE SET
          market=excluded.market,
          name=COALESCE(excluded.name, symbols.name),
          instrument=excluded.instrument,
          exchange=COALESCE(excluded.exchange, symbols.exchange),
          currency=COALESCE(excluded.currency, symbols.currency),
          yield_frac=COALESCE(excluded.yield_frac, symbols.yield_frac),
          market_cap=COALESCE(excluded.market_cap, symbols.market_cap),
          avg_volume=COALESCE(excluded.avg_volume, symbols.avg_volume),
          selected=excluded.selected,
          pool_rank=excluded.pool_rank,
          listed_date=COALESCE(excluded.listed_date, symbols.listed_date),
          select_note=excluded.select_note,
          updated_at=excluded.updated_at
        """,
        (
            row["symbol"],
            row["market"],
            row.get("name"),
            row.get("instrument") or "EQUITY",
            row.get("exchange"),
            row.get("currency"),
            row.get("yield_frac"),
            row.get("market_cap"),
            row.get("avg_volume"),
            1 if row.get("selected") else 0,
            row.get("pool_rank"),
            row.get("listed_date"),
            row.get("select_note"),
            now,
        ),
    )


def upsert_bars(conn: sqlite3.Connection, symbol: str, bars: Iterable[dict[str, Any]], source: str) -> int:
    now = utc_now()
    written = 0
    for bar in bars:
        payload = {
            "open": bar.get("open"),
            "high": bar.get("high"),
            "low": bar.get("low"),
            "close": bar["close"],
            "adj_close": bar["close"] if bar.get("adj_close") is None else bar.get("adj_close"),
            "volume": 0 if bar.get("volume") is None else bar.get("volume"),
        }
        current = conn.execute(
            "SELECT open, high, low, close, adj_close, volume FROM bars WHERE symbol=? AND date=?",
            (symbol, bar["date"]),
        ).fetchone()
        if current is None:
            conn.execute(
                """
                INSERT INTO bars(symbol, date, open, high, low, close, adj_close, volume, source, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    symbol,
                    bar["date"],
                    payload["open"],
                    payload["high"],
                    payload["low"],
                    payload["close"],
                    payload["adj_close"],
                    payload["volume"],
                    source,
                    now,
                ),
            )
            written += 1
            continue
        updates = []
        for field in BAR_FIELDS:
            new_value = payload[field]
            old_value = current[field]
            if _changed(old_value, new_value):
                conn.execute(
                    """
                    INSERT INTO bar_revisions(symbol, date, field, old_value, new_value, revised_at)
                    VALUES(?,?,?,?,?,?)
                    """,
                    (symbol, bar["date"], field, old_value, new_value, now),
                )
                updates.append((field, new_value))
        if updates:
            assignments = ", ".join(f"{field}=?" for field, _ in updates)
            values = [value for _, value in updates]
            conn.execute(
                f"UPDATE bars SET {assignments}, source=?, updated_at=? WHERE symbol=? AND date=?",
                (*values, source, now, symbol, bar["date"]),
            )
            written += 1
    return written


def upsert_dividends(conn: sqlite3.Connection, symbol: str, dividends: Iterable[dict[str, Any]], source: str) -> int:
    now = utc_now()
    written = 0
    for item in dividends:
        current = conn.execute(
            "SELECT amount FROM dividends WHERE symbol=? AND ex_date=?",
            (symbol, item["date"]),
        ).fetchone()
        amount = float(item["amount"])
        if current is None:
            conn.execute(
                "INSERT INTO dividends(symbol, ex_date, amount, source, updated_at) VALUES(?,?,?,?,?)",
                (symbol, item["date"], amount, source, now),
            )
            written += 1
        elif _changed(current["amount"], amount):
            conn.execute(
                """
                INSERT INTO dividend_revisions(symbol, ex_date, old_amount, new_amount, revised_at)
                VALUES(?,?,?,?,?)
                """,
                (symbol, item["date"], current["amount"], amount, now),
            )
            conn.execute(
                "UPDATE dividends SET amount=?, source=?, updated_at=? WHERE symbol=? AND ex_date=?",
                (amount, source, now, symbol, item["date"]),
            )
            written += 1
    return written


def upsert_splits(conn: sqlite3.Connection, symbol: str, splits: Iterable[dict[str, Any]], source: str) -> None:
    for item in splits:
        conn.execute(
            """
            INSERT INTO splits(symbol, ex_date, numerator, denominator, source)
            VALUES(?,?,?,?,?)
            ON CONFLICT(symbol, ex_date) DO UPDATE SET
              numerator=excluded.numerator,
              denominator=excluded.denominator,
              source=excluded.source
            """,
            (symbol, item["date"], item["numerator"], item["denominator"], source),
        )


def refresh_sync_state(conn: sqlite3.Connection, symbol: str, status: str, error: str | None) -> None:
    now = utc_now()
    stats = conn.execute(
        """
        SELECT MIN(date) AS first_date, MAX(date) AS last_date, COUNT(*) AS bar_count
        FROM bars WHERE symbol=?
        """,
        (symbol,),
    ).fetchone()
    divs = conn.execute("SELECT COUNT(*) AS n FROM dividends WHERE symbol=?", (symbol,)).fetchone()["n"]
    conn.execute(
        """
        INSERT INTO sync_state(
          symbol, first_date, last_date, bar_count, div_count, last_attempt, last_success, last_error, status
        ) VALUES(?,?,?,?,?,?,?,?,?)
        ON CONFLICT(symbol) DO UPDATE SET
          first_date=excluded.first_date,
          last_date=excluded.last_date,
          bar_count=excluded.bar_count,
          div_count=excluded.div_count,
          last_attempt=excluded.last_attempt,
          last_success=CASE WHEN excluded.status='ok' THEN excluded.last_success ELSE sync_state.last_success END,
          last_error=excluded.last_error,
          status=excluded.status
        """,
        (
            symbol,
            stats["first_date"],
            stats["last_date"],
            stats["bar_count"] or 0,
            divs,
            now,
            now if status == "ok" else None,
            error,
            status,
        ),
    )


def backup_vault(conn: sqlite3.Connection, reason: str = "manual") -> Path | None:
    count = conn.execute("SELECT COUNT(*) AS n FROM bars").fetchone()["n"]
    if count <= 0:
        return None
    day = datetime.now().strftime("%Y%m%d")
    dest = BACKUPS / f"vault-{day}.sqlite"
    if dest.exists() and reason == "auto":
        return dest
    tmp = dest.with_suffix(".sqlite.tmp")
    if tmp.exists():
        tmp.unlink()
    snapshot = sqlite3.connect(tmp)
    try:
        conn.backup(snapshot)
    finally:
        snapshot.close()
    tmp.replace(dest)
    backups = sorted(BACKUPS.glob("vault-*.sqlite"))
    for old in backups[:-14]:
        old.unlink(missing_ok=True)
    return dest


def export_snapshot(dest: Path) -> Path:
    conn = open_vault()
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            dest.unlink()
        snapshot = sqlite3.connect(dest)
        try:
            conn.backup(snapshot)
        finally:
            snapshot.close()
    finally:
        conn.close()
    return dest


def integrity(conn: sqlite3.Connection) -> dict[str, Any]:
    broken = conn.execute(
        """
        SELECT COUNT(*) AS n FROM bars
        WHERE close IS NULL OR close<=0
           OR (high IS NOT NULL AND low IS NOT NULL AND high<low)
        """
    ).fetchone()["n"]
    revisions = conn.execute("SELECT COUNT(*) AS n FROM bar_revisions").fetchone()["n"]
    div_revisions = conn.execute("SELECT COUNT(*) AS n FROM dividend_revisions").fetchone()["n"]
    backups = sorted(p.name for p in BACKUPS.glob("vault-*.sqlite"))
    return {
        "broken_bars": broken,
        "bar_revisions": revisions,
        "dividend_revisions": div_revisions,
        "backups": backups[-7:],
        "backup_count": len(backups),
        "wal": True,
        "note": "기존 일봉은 삭제하지 않고, 값이 바뀌면 bar_revisions에 이전 값을 남깁니다.",
    }


def copy_aside(path: Path) -> None:
    if path.exists():
        stamp = datetime.now().strftime("%Y%m%d%H%M%S")
        shutil.copy2(path, path.with_name(f"{path.stem}-aside-{stamp}{path.suffix}"))
