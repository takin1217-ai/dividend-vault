"""Sell quickly when a dividend is cut or stops, and only if the sale is profitable."""

from __future__ import annotations

import sqlite3
from datetime import date, timedelta
from typing import Any

from dividend_vault.costs import net_sell_pnl


def _sum_between(conn: sqlite3.Connection, symbol: str, start: str, end: str) -> float:
    row = conn.execute(
        "SELECT COALESCE(SUM(amount), 0) AS total FROM dividends WHERE symbol=? AND ex_date>? AND ex_date<=?",
        (symbol, start, end),
    ).fetchone()
    return float(row["total"] or 0)


def event_from_lists(div_dates: list[str], div_amounts: list[float], asof: str, cut_ratio: float) -> str | None:
    day = date.fromisoformat(asof)
    recent_start = (day - timedelta(days=365)).isoformat()
    prior_start = (day - timedelta(days=730)).isoformat()
    recent = 0.0
    prior = 0.0
    for ex_date, amount in zip(div_dates, div_amounts):
        if prior_start < ex_date <= recent_start:
            prior += amount
        elif recent_start < ex_date <= asof:
            recent += amount
    if prior <= 0:
        return None
    if recent <= 0:
        return "무배당"
    if recent < prior * (1 - cut_ratio):
        return "배당축소"
    return None


def dividend_event(conn: sqlite3.Connection, symbol: str, asof: str, cut_ratio: float) -> str | None:
    """Recent 12 months versus the 12 months before that. None if the dividend did not break."""
    day = date.fromisoformat(asof)
    recent_start = (day - timedelta(days=365)).isoformat()
    prior_start = (day - timedelta(days=730)).isoformat()
    recent = _sum_between(conn, symbol, recent_start, asof)
    prior = _sum_between(conn, symbol, prior_start, recent_start)
    if prior <= 0:
        return None
    if recent <= 0:
        return "무배당"
    if recent < prior * (1 - cut_ratio):
        return "배당축소"
    return None


def review_positions(
    conn: sqlite3.Connection,
    market: str,
    positions: dict[str, dict[str, float]],
    prices: dict[str, float],
    asof: str,
    cut_ratio: float,
) -> list[dict[str, Any]]:
    reviews = []
    for symbol, position in positions.items():
        price = prices.get(symbol)
        if price is None or price <= 0:
            continue
        kind = dividend_event(conn, symbol, asof, cut_ratio)
        if kind is None:
            continue
        pnl, fee, shares, odd = net_sell_pnl(market, position["qty"], price, position["avg_price"])
        reviews.append(
            {
                "symbol": symbol,
                "kind": kind,
                "price": price,
                "avg_price": position["avg_price"],
                "shares": shares,
                "odd_lot": odd,
                "fee": fee,
                "pnl": pnl,
                "sell": pnl > 0 and shares > 0,
            }
        )
    return reviews
