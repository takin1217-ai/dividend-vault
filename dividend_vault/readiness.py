"""Live orders stay closed until the 2x daily-bar book, a backtest, and paper trades exist."""

from __future__ import annotations

import sqlite3

from dividend_vault.costs import charge, sell_quantity
from dividend_vault.db import load_settings
from dividend_vault.settings import enabled_markets


def _count(conn: sqlite3.Connection, sql: str, args: tuple) -> int:
    return int(conn.execute(sql, args).fetchone()[0])


def live_gaps(conn: sqlite3.Connection) -> list[str]:
    settings = load_settings(conn)
    gaps = []
    for market in enabled_markets(settings):
        code = market["code"]
        name = market["name"]
        minimum = int(market["min_symbols"])
        target = int(market["target_symbols"])
        selected = _count(
            conn,
            "SELECT COUNT(*) FROM symbols WHERE market=? AND selected=1 AND instrument IN ('EQUITY','ETF')",
            (code,),
        )
        pooled = _count(
            conn,
            "SELECT COUNT(*) FROM symbols WHERE market=? AND instrument IN ('EQUITY','ETF')",
            (code,),
        )
        with_bars = _count(
            conn,
            """
            SELECT COUNT(*) FROM symbols s
            WHERE s.market=? AND s.selected=1 AND EXISTS (SELECT 1 FROM bars b WHERE b.symbol=s.symbol)
            """,
            (code,),
        )
        if selected < minimum:
            gaps.append(f"{name} 선택 {selected}/{minimum}")
        elif selected < target and pooled > selected:
            gaps.append(f"{name} 목표 {selected}/{target}")
        if with_bars < min(selected, minimum):
            gaps.append(f"{name} 일봉 {with_bars}")
        if _count(conn, "SELECT COUNT(*) FROM backtests WHERE market=?", (code,)) < 1:
            gaps.append(f"{name} 과거 모의 없음")
        if _count(
            conn,
            """
            SELECT COUNT(*) FROM paper_orders o
            JOIN paper_accounts a ON a.id=o.account_id
            WHERE a.market=?
            """,
            (code,),
        ) < 1:
            gaps.append(f"{name} 가상 모의 없음")
    return gaps


def live_block_reason(conn: sqlite3.Connection) -> str | None:
    gaps = live_gaps(conn)
    if not gaps:
        return None
    shown = " · ".join(gaps[:8])
    return f"실전 주문은 7개국 2배 일봉과 과거 모의, 가상 모의가 끝난 뒤에만 열립니다. {shown}"


def loss_sell_reason(conn: sqlite3.Connection, account: dict, market: str, symbol: str, qty: float, price: float) -> str | None:
    avg = _average_cost(conn, str(account.get("alias") or ""), market, symbol)
    if avg is None:
        return "매수 기록이 없어 이익인지 확인할 수 없습니다. 손절 주문은 받지 않습니다."
    shares, odd = sell_quantity(market, qty)
    if shares <= 0:
        return "최소 주문 수량보다 적어 매도하지 않습니다."
    fee = charge(market, "sell", shares * price, odd)
    if (price - avg) * shares - fee <= 0:
        return "수수료와 세금 뒤 이익이 아닙니다. 손절 주문은 받지 않습니다."
    return None


def _average_cost(conn: sqlite3.Connection, alias: str, market: str, symbol: str) -> float | None:
    bought = conn.execute(
        """
        SELECT COALESCE(SUM(qty), 0) AS qty, COALESCE(SUM(qty * price), 0) AS cost
        FROM kis_orders
        WHERE account_alias=? AND symbol=? AND side='buy' AND status!='error'
        """,
        (alias, symbol),
    ).fetchone()
    if bought and float(bought["qty"]) > 0:
        return float(bought["cost"]) / float(bought["qty"])
    paper = conn.execute(
        """
        SELECT p.avg_price FROM paper_positions p
        JOIN paper_accounts a ON a.id=p.account_id
        WHERE a.market=? AND p.symbol=? AND p.qty>0
        """,
        (market, symbol),
    ).fetchone()
    if paper is None:
        return None
    return float(paper["avg_price"])
