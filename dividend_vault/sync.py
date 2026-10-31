"""Incremental daily bars. A failed run keeps every bar already stored."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Callable

from dividend_vault import yahoo
from dividend_vault.db import refresh_sync_state, upsert_bars, upsert_dividends, upsert_splits, upsert_symbol, utc_now
from dividend_vault.paths import LOGS, ensure_dirs
from dividend_vault.universe import refresh_market, rerank_vietnam

Progress = Callable[[str, int, int], None]
LOGGER = logging.getLogger("dividend_vault.sync")


def _log() -> logging.Logger:
    ensure_dirs()
    if not LOGGER.handlers:
        handler = logging.FileHandler(LOGS / "sync.log", encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
        LOGGER.addHandler(handler)
        LOGGER.setLevel(logging.INFO)
    return LOGGER


def ensure_benchmark(conn, market: dict[str, Any]) -> None:
    symbol = market["benchmark"]
    exists = conn.execute("SELECT symbol FROM symbols WHERE symbol=?", (symbol,)).fetchone()
    if exists:
        return
    upsert_symbol(
        conn,
        {
            "symbol": symbol,
            "market": market["code"],
            "name": f"{market['name']} 기준",
            "instrument": "BENCHMARK",
            "exchange": None,
            "currency": market["currency"],
            "selected": 0,
            "pool_rank": 0,
            "select_note": "benchmark",
        },
    )
    conn.commit()


def _targets(conn, market: dict[str, Any]) -> list[str]:
    rows = conn.execute(
        """
        SELECT symbol FROM symbols
        WHERE market=? AND (
          (selected=1 AND instrument IN ('EQUITY','ETP'))
          OR instrument='BENCHMARK'
          OR (?='VN' AND instrument='EQUITY' AND pool_rank<=?)
        )
        ORDER BY CASE WHEN instrument='BENCHMARK' THEN 1 ELSE 0 END, pool_rank
        """,
        (market["code"], market["code"], int(market["max_symbols"])),
    ).fetchall()
    return [row["symbol"] for row in rows]


def _sync_symbol(conn, symbol: str, years: int, force: bool) -> tuple[str, int]:
    state = conn.execute("SELECT status, last_success, bar_count FROM sync_state WHERE symbol=?", (symbol,)).fetchone()
    today = datetime.now().strftime("%Y-%m-%d")
    if (
        not force
        and state
        and state["status"] == "ok"
        and (state["last_success"] or "").startswith(today)
        and (state["bar_count"] or 0) > 0
    ):
        return "skipped", 0
    last_date = None
    if state and (state["bar_count"] or 0) > 0:
        last_date = conn.execute("SELECT MAX(date) AS d FROM bars WHERE symbol=?", (symbol,)).fetchone()["d"]
    start, end = yahoo.window_for(years, last_date)
    try:
        chart = yahoo.fetch_chart(symbol, start, end)
    except Exception as exc:
        conn.execute("BEGIN")
        try:
            refresh_sync_state(conn, symbol, "error", str(exc)[:500])
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        _log().warning("fail %s %s", symbol, exc)
        return "error", 0
    if not chart["bars"]:
        refresh_sync_state(conn, symbol, "error", "일봉이 비어 있습니다.")
        conn.commit()
        return "error", 0
    conn.execute("BEGIN")
    try:
        written = upsert_bars(conn, symbol, chart["bars"], "yahoo")
        upsert_dividends(conn, symbol, chart["dividends"], "yahoo")
        upsert_splits(conn, symbol, chart["splits"], "yahoo")
        conn.execute(
            """
            UPDATE symbols
            SET currency=COALESCE(?, currency),
                exchange=COALESCE(?, exchange),
                name=COALESCE(name, ?),
                updated_at=?
            WHERE symbol=?
            """,
            (chart.get("currency"), chart.get("exchange"), chart.get("name"), utc_now(), symbol),
        )
        refresh_sync_state(conn, symbol, "ok", None)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    _log().info("ok %s bars+%s", symbol, written)
    return "ok", written


def _repair(conn, market: dict[str, Any]) -> list[str]:
    """If a name has no bars, try the next pool name without deleting stored history."""
    minimum = int(market["min_symbols"])
    ok = conn.execute(
        """
        SELECT COUNT(*) AS n
        FROM symbols s JOIN sync_state st ON st.symbol=s.symbol
        WHERE s.market=? AND s.selected=1 AND s.instrument='EQUITY' AND st.status='ok' AND st.bar_count>0
        """,
        (market["code"],),
    ).fetchone()["n"]
    if ok >= minimum:
        return []
    need = minimum - ok
    spare = conn.execute(
        """
        SELECT s.symbol FROM symbols s
        LEFT JOIN sync_state st ON st.symbol=s.symbol
        WHERE s.market=? AND s.selected=0 AND s.instrument='EQUITY'
          AND COALESCE(st.status,'')!='error'
        ORDER BY s.pool_rank
        LIMIT ?
        """,
        (market["code"], need),
    ).fetchall()
    promoted = []
    for row in spare:
        conn.execute(
            "UPDATE symbols SET selected=1, select_note='promoted', updated_at=? WHERE symbol=?",
            (utc_now(), row["symbol"]),
        )
        promoted.append(row["symbol"])
    conn.commit()
    return promoted


def sync_markets(
    conn,
    settings: dict[str, Any],
    markets: list[dict[str, Any]],
    progress: Progress,
    force: bool = False,
    refresh: bool = True,
) -> dict[str, Any]:
    logger = _log()
    summary = {"requested": 0, "ok": 0, "skipped": 0, "error": 0, "bars_written": 0, "notes": []}
    cursor = conn.execute(
        "INSERT INTO sync_runs(started_at, markets, requested) VALUES(?,?,0)",
        (utc_now(), ",".join(market["code"] for market in markets)),
    )
    run_id = cursor.lastrowid
    conn.commit()
    for market in markets:
        if refresh:
            try:
                info = refresh_market(conn, market, settings, progress)
                summary["notes"].extend(f"{market['code']}: {note}" for note in info["notes"])
            except Exception as exc:
                summary["notes"].append(f"{market['code']} 유니버스 실패: {exc}")
                logger.exception("universe %s", market["code"])
                continue
        ensure_benchmark(conn, market)
        symbols = _targets(conn, market)
        total = len(symbols)
        for index, symbol in enumerate(symbols, start=1):
            progress(f"{market['name']} {symbol} ({index}/{total})", index, total)
            status, written = _sync_symbol(conn, symbol, int(settings["years"]), force)
            summary["requested"] += 1
            summary["bars_written"] += written
            if status == "ok":
                summary["ok"] += 1
            elif status == "skipped":
                summary["skipped"] += 1
            else:
                summary["error"] += 1
        extra = _repair(conn, market)
        for symbol in extra:
            progress(f"{market['name']} 보충 {symbol}", 0, 1)
            status, written = _sync_symbol(conn, symbol, int(settings["years"]), True)
            summary["requested"] += 1
            summary["bars_written"] += written
            summary["ok" if status == "ok" else "error"] += 1
        if market["code"] == "VN":
            try:
                rerank_vietnam(conn, market)
                summary["notes"].append("베트남 종목을 최근 12개월 배당수익률로 다시 골랐습니다. 받은 일봉은 그대로 둡니다.")
            except Exception as exc:
                summary["notes"].append(f"베트남 재정렬 실패: {exc}")
    conn.execute(
        """
        UPDATE sync_runs
        SET finished_at=?, requested=?, succeeded=?, failed=?, bars_written=?, note=?
        WHERE id=?
        """,
        (
            utc_now(),
            summary["requested"],
            summary["ok"] + summary["skipped"],
            summary["error"],
            summary["bars_written"],
            " | ".join(summary["notes"])[:2000],
            run_id,
        ),
    )
    conn.commit()
    logger.info("run %s", summary)
    return summary
