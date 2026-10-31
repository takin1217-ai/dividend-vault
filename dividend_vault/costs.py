"""Korea Investment & Securities online costs.

Rates follow the broker guide: overseas commissions and local taxes as posted for
online orders, Vietnam minimum commission VND 800,000, and domestic Bankis
commission plus the guide's 0.20% sell tax. Guides change without notice.
"""

from __future__ import annotations

import math
from typing import Any

# commission, buy_tax, sell_tax, min_commission, lot, min_buy_notional, waive odd-lot sell minimum
SCHEDULE: dict[str, dict[str, float | int | bool]] = {
    "US": {"commission": 0.0025, "buy_tax": 0.0, "sell_tax": 0.0000206, "min_commission": 0.0, "lot": 1, "min_buy_notional": 0.0, "waive_odd_sell": False},
    "CN": {"commission": 0.0030, "buy_tax": 0.0000841, "sell_tax": 0.0005841, "min_commission": 0.0, "lot": 100, "min_buy_notional": 0.0, "waive_odd_sell": False},
    "HK": {"commission": 0.0030, "buy_tax": 0.001085, "sell_tax": 0.001085, "min_commission": 0.0, "lot": 100, "min_buy_notional": 0.0, "waive_odd_sell": False},
    "JP": {"commission": 0.0023, "buy_tax": 0.0, "sell_tax": 0.0, "min_commission": 0.0, "lot": 100, "min_buy_notional": 0.0, "waive_odd_sell": False},
    "VN": {"commission": 0.0040, "buy_tax": 0.0, "sell_tax": 0.001, "min_commission": 800_000.0, "lot": 100, "min_buy_notional": 200_000_000.0, "waive_odd_sell": True},
    "KR": {"commission": 0.000140527, "buy_tax": 0.0, "sell_tax": 0.0020, "min_commission": 0.0, "lot": 1, "min_buy_notional": 0.0, "waive_odd_sell": False},
    "GB": {"commission": 0.0030, "buy_tax": 0.005, "sell_tax": 0.0, "min_commission": 0.0, "lot": 1, "min_buy_notional": 0.0, "waive_odd_sell": False},
}


def schedule(market: str) -> dict[str, Any]:
    return SCHEDULE.get(market, SCHEDULE["US"])


def charge(market: str, side: str, notional: float, odd_lot: bool = False) -> float:
    if notional <= 0:
        return 0.0
    item = schedule(market)
    fee = notional * float(item["commission"])
    minimum = float(item["min_commission"])
    if minimum > 0 and not (odd_lot and side == "sell" and item["waive_odd_sell"]):
        fee = max(fee, minimum)
    tax_key = "sell_tax" if side == "sell" else "buy_tax"
    return fee + notional * float(item[tax_key])


def lot_size(market: str) -> int:
    return max(1, int(schedule(market)["lot"]))


def sell_quantity(market: str, qty: float) -> tuple[int, bool]:
    """Whole board lots, or a Vietnam odd-lot remainder when that is all that is left."""
    lot = lot_size(market)
    if qty < 1:
        return 0, False
    whole = int(math.floor(qty / lot)) * lot
    if whole >= lot:
        return whole, False
    if market == "VN" and qty >= 1:
        return int(math.floor(qty)), True
    if lot == 1:
        return int(math.floor(qty)), False
    return 0, False


def buy_quantity(market: str, cash: float, price: float) -> int:
    """Largest lot that fits after fees, or nothing if the minimum notional does not fit."""
    if cash <= 0 or price <= 0:
        return 0
    lot = lot_size(market)
    floor_notional = float(schedule(market)["min_buy_notional"])
    minimum_shares = int(math.ceil(floor_notional / price / lot)) * lot if floor_notional > 0 else 0

    def affordable(shares: int) -> bool:
        if shares <= 0:
            return False
        notional = shares * price
        return notional + charge(market, "buy", notional) <= cash + 1e-6

    if minimum_shares and not affordable(minimum_shares):
        return 0
    low = minimum_shares
    high = max(minimum_shares, int(cash / price / lot) * lot)
    best = minimum_shares if minimum_shares else 0
    while low <= high:
        mid = ((low + high) // 2 // lot) * lot
        if mid <= 0 or mid < minimum_shares:
            low = max(mid + lot, minimum_shares or lot)
            continue
        if affordable(mid):
            best = mid
            low = mid + lot
        else:
            high = mid - lot
    return best


def net_sell_pnl(market: str, qty: float, price: float, avg_price: float) -> tuple[float, float, int, bool]:
    shares, odd = sell_quantity(market, qty)
    if shares <= 0 or price <= 0:
        return 0.0, 0.0, 0, odd
    notional = shares * price
    fee = charge(market, "sell", notional, odd)
    pnl = (price - avg_price) * shares - fee
    return pnl, fee, shares, odd
