"""Dashboard counts. History length is reported as it is, without filling gaps."""

from __future__ import annotations

import sqlite3
from typing import Any


def market_status(conn: sqlite3.Connection, settings: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for market in settings["markets"]:
        pool = conn.execute(
            """
            SELECT
              SUM(CASE WHEN selected=1 AND instrument!='BENCHMARK' THEN 1 ELSE 0 END) AS selected,
              SUM(CASE WHEN instrument='EQUITY' THEN 1 ELSE 0 END) AS equities,
              SUM(CASE WHEN instrument='ETP' THEN 1 ELSE 0 END) AS etps
            FROM symbols WHERE market=?
            """,
            (market["code"],),
        ).fetchone()
        coverage = conn.execute(
            """
            SELECT
              COUNT(*) AS names,
              COALESCE(SUM(st.bar_count),0) AS bars,
              AVG((julianday(st.last_date) - julianday(st.first_date)) / 365.25) AS years,
              SUM(CASE WHEN (julianday(st.last_date) - julianday(st.first_date)) / 365.25 >= 9 THEN 1 ELSE 0 END) AS decade,
              SUM(CASE WHEN st.status='error' THEN 1 ELSE 0 END) AS failed,
              MAX(st.last_success) AS last_success
            FROM sync_state st
            JOIN symbols s ON s.symbol = st.symbol
            WHERE s.market=? AND s.selected=1
            """,
            (market["code"],),
        ).fetchone()
        names = coverage["names"] or 0
        rows.append(
            {
                "code": market["code"],
                "name": market["name"],
                "enabled": market["enabled"],
                "min_symbols": market["min_symbols"],
                "target_symbols": market["target_symbols"],
                "max_symbols": market["max_symbols"],
                "selected": pool["selected"] or 0,
                "equities": pool["equities"] or 0,
                "etps": pool["etps"] or 0,
                "with_bars": names,
                "bars": coverage["bars"] or 0,
                "avg_years": coverage["years"] or 0,
                "decade": coverage["decade"] or 0,
                "failed": coverage["failed"] or 0,
                "last_success": coverage["last_success"],
                "currency": market["currency"],
            }
        )
    return rows


def totals(conn: sqlite3.Connection) -> dict[str, int]:
    return {
        "symbols": conn.execute("SELECT COUNT(*) AS n FROM symbols").fetchone()["n"],
        "bars": conn.execute("SELECT COUNT(*) AS n FROM bars").fetchone()["n"],
        "dividends": conn.execute("SELECT COUNT(*) AS n FROM dividends").fetchone()["n"],
        "revisions": conn.execute("SELECT COUNT(*) AS n FROM bar_revisions").fetchone()["n"],
    }


def symbol_page(conn: sqlite3.Connection, market: str, query: str, offset: int, limit: int) -> dict[str, Any]:
    like = f"%{query.strip()}%"
    total = conn.execute(
        """
        SELECT COUNT(*) AS n FROM symbols
        WHERE market=? AND instrument!='BENCHMARK' AND (symbol LIKE ? OR IFNULL(name,'') LIKE ?)
        """,
        (market, like, like),
    ).fetchone()["n"]
    rows = conn.execute(
        """
        SELECT s.symbol, s.name, s.instrument, s.yield_frac, s.market_cap, s.selected, s.pool_rank,
               st.bar_count, st.first_date, st.last_date, st.status, st.last_error
        FROM symbols s
        LEFT JOIN sync_state st ON st.symbol=s.symbol
        WHERE s.market=? AND s.instrument!='BENCHMARK' AND (s.symbol LIKE ? OR IFNULL(s.name,'') LIKE ?)
        ORDER BY s.selected DESC, s.pool_rank
        LIMIT ? OFFSET ?
        """,
        (market, like, like, limit, offset),
    ).fetchall()
    return {"total": total, "rows": [dict(row) for row in rows]}
