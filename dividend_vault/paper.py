"""Virtual broker. Fills assume the latest close and are labeled as such."""

from __future__ import annotations

import math
import sqlite3
from typing import Any

from dividend_vault.costs import buy_quantity, charge, lot_size, schedule, sell_quantity
from dividend_vault.db import utc_now
from dividend_vault.dividend_exit import review_positions
from dividend_vault.settings import market_by_code
from dividend_vault.strategy import latest_book


def ensure_account(conn: sqlite3.Connection, market: dict[str, Any]) -> dict[str, Any]:
    row = conn.execute("SELECT * FROM paper_accounts WHERE market=?", (market["code"],)).fetchone()
    if row is None:
        conn.execute(
            """
            INSERT INTO paper_accounts(market, name, currency, cash, created_at)
            VALUES(?,?,?,?,?)
            """,
            (market["code"], f"{market['name']} 가상계좌", market["currency"], float(market["paper_cash"]), utc_now()),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM paper_accounts WHERE market=?", (market["code"],)).fetchone()
    return dict(row)


def _positions(conn: sqlite3.Connection, account_id: int) -> dict[str, dict[str, float]]:
    rows = conn.execute(
        "SELECT symbol, qty, avg_price FROM paper_positions WHERE account_id=?",
        (account_id,),
    ).fetchall()
    return {row["symbol"]: {"qty": row["qty"], "avg_price": row["avg_price"]} for row in rows}


def _last_price(conn: sqlite3.Connection, symbol: str) -> tuple[str, float] | None:
    row = conn.execute(
        "SELECT date, close FROM bars WHERE symbol=? AND close>0 ORDER BY date DESC LIMIT 1",
        (symbol,),
    ).fetchone()
    if row is None:
        return None
    return row["date"], float(row["close"])


def mark_account(conn: sqlite3.Connection, account: dict[str, Any]) -> dict[str, Any]:
    positions = _positions(conn, account["id"])
    equity = float(account["cash"])
    lines = []
    asof = None
    for symbol, position in positions.items():
        quote = _last_price(conn, symbol)
        if quote is None:
            continue
        asof = quote[0]
        value = position["qty"] * quote[1]
        equity += value
        lines.append(
            {
                "symbol": symbol,
                "qty": position["qty"],
                "avg_price": position["avg_price"],
                "price": quote[1],
                "value": value,
            }
        )
    return {"cash": account["cash"], "equity": equity, "asof": asof, "positions": lines}


def rebalance_market(conn: sqlite3.Connection, market_code: str, settings: dict[str, Any], strategy: str | None = None) -> dict[str, Any]:
    market = market_by_code(settings, market_code)
    account = ensure_account(conn, market)
    book = latest_book(conn, market_code, settings, strategy)
    holdings = book["holdings"]
    if not holdings:
        raise RuntimeError("산출된 보유 종목이 없습니다.")
    positions = _positions(conn, account["id"])
    prices: dict[str, float] = {}
    asof = book["asof"]
    symbols = set(positions) | {item["symbol"] for item in holdings}
    for symbol in symbols:
        quote = _last_price(conn, symbol)
        if quote:
            prices[symbol] = quote[1]
            asof = quote[0]
    cash = float(account["cash"])
    equity = cash + sum(positions[symbol]["qty"] * prices[symbol] for symbol in positions if symbol in prices)
    targets = {item["symbol"]: equity * float(item["weight"]) for item in holdings if item["symbol"] in prices}
    orders = []

    def trade(symbol: str, side: str, qty: float, price: float, reason: str, odd_lot: bool = False) -> None:
        nonlocal cash
        if qty <= 0 or price <= 0:
            return
        if side == "sell":
            held = positions.get(symbol, {"qty": 0, "avg_price": price})
            qty = min(qty, held["qty"])
            shares, odd = sell_quantity(market_code, qty)
            if odd_lot:
                odd = True
            if shares <= 0:
                return
            notional = shares * price
            fee = charge(market_code, "sell", notional, odd)
            if (price - held["avg_price"]) * shares - fee <= 0:
                return
            cash += notional - fee
            left = held["qty"] - shares
            if left <= 1e-6:
                positions.pop(symbol, None)
            else:
                positions[symbol] = {"qty": left, "avg_price": held["avg_price"]}
            qty = shares
        else:
            lot = lot_size(market_code)
            wanted = (int(qty) // lot) * lot
            minimum = float(schedule(market_code)["min_buy_notional"])
            if minimum > 0:
                floor_shares = int(math.ceil(minimum / price / lot)) * lot
                wanted = max(wanted, floor_shares)
            shares = min(buy_quantity(market_code, cash, price), wanted)
            if shares <= 0:
                return
            notional = shares * price
            fee = charge(market_code, "buy", notional)
            if notional + fee > cash + 1e-6:
                return
            cash -= notional + fee
            held = positions.get(symbol, {"qty": 0, "avg_price": 0})
            new_qty = held["qty"] + shares
            avg = (held["qty"] * held["avg_price"] + shares * price) / new_qty
            positions[symbol] = {"qty": new_qty, "avg_price": avg}
            qty = shares
        orders.append(
            {
                "symbol": symbol,
                "side": side,
                "qty": qty,
                "price": price,
                "fee": fee,
                "reason": reason,
            }
        )

    held_notes = []
    blocked: set[str] = set()
    cut_ratio = float(settings.get("dividend_cut_ratio", 0.2))
    if settings.get("dividend_exit", True) and asof:
        for review in review_positions(conn, market_code, positions, prices, asof, cut_ratio):
            blocked.add(review["symbol"])
            if review["sell"]:
                trade(
                    review["symbol"],
                    "sell",
                    review["shares"],
                    review["price"],
                    f"{review['kind']} · 수수료·세금 후 이익 매도 · 손절 아님 · {asof}",
                    review["odd_lot"],
                )
            else:
                held_notes.append(f"{review['symbol']} {review['kind']}이지만 수수료·세금 후 손실이라 보유")
    reason = f"{book['strategy']} · 한투 온라인 수수료·세금 · 최신 종가 가정 · {asof}"
    for symbol, position in list(positions.items()):
        price = prices.get(symbol)
        if price is None:
            continue
        if symbol in blocked:
            continue
        target_qty = targets.get(symbol, 0) / price
        if position["qty"] > target_qty:
            trade(symbol, "sell", position["qty"] - target_qty, price, reason)
    for symbol, target_value in targets.items():
        price = prices[symbol]
        target_qty = target_value / price
        if symbol in blocked:
            continue
        held = positions.get(symbol, {"qty": 0})["qty"]
        if target_qty > held:
            trade(symbol, "buy", target_qty - held, price, reason)

    conn.execute("BEGIN")
    try:
        conn.execute("UPDATE paper_accounts SET cash=? WHERE id=?", (cash, account["id"]))
        conn.execute("DELETE FROM paper_positions WHERE account_id=?", (account["id"],))
        for symbol, position in positions.items():
            if position["qty"] > 1e-8:
                conn.execute(
                    "INSERT INTO paper_positions(account_id, symbol, qty, avg_price) VALUES(?,?,?,?)",
                    (account["id"], symbol, position["qty"], position["avg_price"]),
                )
        for order in orders:
            conn.execute(
                """
                INSERT INTO paper_orders(
                  account_id, symbol, side, qty, status, reason, created_at, filled_at, fill_price, fee
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    account["id"],
                    order["symbol"],
                    order["side"],
                    order["qty"],
                    "filled",
                    order["reason"],
                    utc_now(),
                    asof,
                    order["price"],
                    order["fee"],
                ),
            )
        account["cash"] = cash
        marked = mark_account(conn, account)
        conn.execute(
            """
            INSERT INTO paper_equity(account_id, date, equity, cash) VALUES(?,?,?,?)
            ON CONFLICT(account_id, date) DO UPDATE SET equity=excluded.equity, cash=excluded.cash
            """,
            (account["id"], asof or utc_now()[:10], marked["equity"], cash),
        )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    return {
        "account": {"id": account["id"], "market": market_code, "currency": market["currency"], "cash": cash},
        "orders": orders,
        "mark": marked,
        "book": {"strategy": book["strategy"], "asof": book["asof"], "holding_count": book["holding_count"]},
        "held_despite_cut": held_notes,
        "disclaimer": "배당이 줄거나 끊겨도 수수료와 세금 후 이익일 때만 매도합니다. 가격 하락만으로 손절하지 않습니다. 가상 체결은 최신 종가 가정입니다.",
    }


def account_view(conn: sqlite3.Connection, market_code: str, settings: dict[str, Any]) -> dict[str, Any]:
    market = market_by_code(settings, market_code)
    account = ensure_account(conn, market)
    orders = conn.execute(
        """
        SELECT symbol, side, qty, fill_price, fee, filled_at, reason
        FROM paper_orders WHERE account_id=? ORDER BY id DESC LIMIT 30
        """,
        (account["id"],),
    ).fetchall()
    curve = conn.execute(
        "SELECT date, equity, cash FROM paper_equity WHERE account_id=? ORDER BY date",
        (account["id"],),
    ).fetchall()
    return {
        "account": dict(account),
        "mark": mark_account(conn, account),
        "orders": [dict(row) for row in orders],
        "equity": [dict(row) for row in curve],
    }
