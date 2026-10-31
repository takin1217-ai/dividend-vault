"""Editable research settings. Targets stay between 2x and 5x the minimum holding."""

from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

EXPANSION_MAX = 5.0
EXPANSION_MIN = 2.0
HOLDING_FLOORS = {
    "US": (1000, 2000),
    "CN": (250, 500),
    "HK": (150, 300),
    "JP": (250, 500),
    "VN": (50, 100),
    "KR": (250, 500),
    "GB": (50, 100),
}
FLOOR_VERSION = "20261031-2x"

MARKET_DEFAULTS: list[dict[str, Any]] = [
    {
        "code": "US",
        "name": "미국",
        "enabled": True,
        "min_symbols": 1000,
        "target_symbols": 2000,
        "min_market_cap": 300_000_000,
        "currency": "USD",
        "paper_cash": 1_000_000,
        "yahoo_region": "us",
        "benchmark": "SPY",
        "source": "yahoo",
    },
    {
        "code": "CN",
        "name": "중국",
        "enabled": True,
        "min_symbols": 250,
        "target_symbols": 500,
        "min_market_cap": 5_000_000_000,
        "currency": "CNY",
        "paper_cash": 5_000_000,
        "yahoo_region": "cn",
        "benchmark": "000300.SS",
        "source": "yahoo",
    },
    {
        "code": "HK",
        "name": "홍콩",
        "enabled": True,
        "min_symbols": 150,
        "target_symbols": 300,
        "min_market_cap": 5_000_000_000,
        "currency": "HKD",
        "paper_cash": 5_000_000,
        "yahoo_region": "hk",
        "benchmark": "^HSI",
        "source": "yahoo",
    },
    {
        "code": "JP",
        "name": "일본",
        "enabled": True,
        "min_symbols": 250,
        "target_symbols": 500,
        "min_market_cap": 50_000_000_000,
        "currency": "JPY",
        "paper_cash": 100_000_000,
        "yahoo_region": "jp",
        "benchmark": "^N225",
        "source": "yahoo",
    },
    {
        "code": "VN",
        "name": "베트남",
        "enabled": True,
        "min_symbols": 50,
        "target_symbols": 100,
        "min_market_cap": 0,
        "currency": "VND",
        "paper_cash": 10_000_000_000,
        "yahoo_region": "vn",
        "benchmark": "E1VFVN30.VN",
        "source": "vndirect",
    },
    {
        "code": "KR",
        "name": "한국",
        "enabled": True,
        "min_symbols": 250,
        "target_symbols": 500,
        "min_market_cap": 200_000_000_000,
        "currency": "KRW",
        "paper_cash": 100_000_000,
        "yahoo_region": "kr",
        "benchmark": "069500.KS",
        "source": "yahoo",
    },
    {
        "code": "GB",
        "name": "영국",
        "enabled": True,
        "min_symbols": 50,
        "target_symbols": 100,
        "min_market_cap": 200_000_000,
        "currency": "GBP",
        "paper_cash": 500_000,
        "yahoo_region": "gb",
        "benchmark": "^FTSE",
        "source": "yahoo",
    },
]

STRATEGIES = (
    "equal",
    "yield_tilt",
    "momentum_tilt",
    "lowvol_tilt",
    "learned_tilt",
)


def default_settings() -> dict[str, Any]:
    return {
        "years": 10,
        "expansion_factor": 5,
        "min_yield": 0.02,
        "max_yield": 0.12,
        "min_avg_volume": 50_000,
        "exclude_preferred": True,
        "exclude_leveraged": True,
        "include_etp": False,
        "etp_limit": 0,
        "fee_bps": 10.0,
        "dividend_exit": True,
        "dividend_cut_ratio": 0.2,
        "model_weight": 0.5,
        "strategy": "learned_tilt",
        "weight_cap": 4.0,
        "auto_daily": True,
        "markets": deepcopy(MARKET_DEFAULTS),
    }


def _as_int(value: Any, fallback: int, lo: int, hi: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = fallback
    return max(lo, min(hi, number))


def _as_float(value: Any, fallback: float, lo: float, hi: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = fallback
    return max(lo, min(hi, number))


def normalize_settings(raw: dict[str, Any] | None) -> dict[str, Any]:
    base = default_settings()
    raw = raw or {}
    factor = _as_float(raw.get("expansion_factor", base["expansion_factor"]), 5, EXPANSION_MIN, EXPANSION_MAX)
    out = {
        "years": _as_int(raw.get("years", 10), 10, 1, 10),
        "expansion_factor": factor,
        "min_yield": _as_float(raw.get("min_yield", 0.02), 0.02, 0, 0.5),
        "max_yield": _as_float(raw.get("max_yield", 0.12), 0.12, 0, 0.8),
        "min_avg_volume": _as_float(raw.get("min_avg_volume", 50_000), 50_000, 0, 1e12),
        "exclude_preferred": bool(raw.get("exclude_preferred", True)),
        "exclude_leveraged": bool(raw.get("exclude_leveraged", True)),
        "include_etp": bool(raw.get("include_etp", False)),
        "etp_limit": _as_int(raw.get("etp_limit", 0), 0, 0, 500),
        "fee_bps": _as_float(raw.get("fee_bps", 10), 10, 0, 200),
        "dividend_exit": bool(raw.get("dividend_exit", True)),
        "dividend_cut_ratio": _as_float(raw.get("dividend_cut_ratio", 0.2), 0.2, 0, 1),
        "model_weight": _as_float(raw.get("model_weight", 0.5), 0.5, 0, 1),
        "strategy": raw.get("strategy") if raw.get("strategy") in STRATEGIES else "learned_tilt",
        "weight_cap": _as_float(raw.get("weight_cap", 4), 4, 1, 8),
        "auto_daily": bool(raw.get("auto_daily", True)),
        "markets": [],
    }
    if out["max_yield"] < out["min_yield"]:
        out["max_yield"] = out["min_yield"]

    incoming = {m.get("code"): m for m in raw.get("markets") or [] if isinstance(m, dict)}
    for preset in MARKET_DEFAULTS:
        src = incoming.get(preset["code"], {})
        minimum = _as_int(src.get("min_symbols", preset["min_symbols"]), preset["min_symbols"], 1, 20000)
        ceiling = max(minimum * 2, int(minimum * factor))
        target = _as_int(src.get("target_symbols", minimum * 2), minimum * 2, minimum * 2, ceiling)
        out["markets"].append(
            {
                "code": preset["code"],
                "name": preset["name"],
                "enabled": bool(src.get("enabled", preset["enabled"])),
                "min_symbols": minimum,
                "target_symbols": target,
                "max_symbols": ceiling,
                "min_market_cap": _as_float(
                    src.get("min_market_cap", preset["min_market_cap"]),
                    preset["min_market_cap"],
                    0,
                    1e18,
                ),
                "currency": preset["currency"],
                "paper_cash": _as_float(src.get("paper_cash", preset["paper_cash"]), preset["paper_cash"], 0, 1e18),
                "yahoo_region": preset["yahoo_region"],
                "benchmark": preset["benchmark"],
                "source": preset["source"],
            }
        )
    return out


def dump_settings(settings: dict[str, Any]) -> str:
    return json.dumps(normalize_settings(settings), ensure_ascii=False, separators=(",", ":"))


def market_by_code(settings: dict[str, Any], code: str) -> dict[str, Any]:
    for market in settings["markets"]:
        if market["code"] == code:
            return market
    raise KeyError(code)


def enabled_markets(settings: dict[str, Any], only: list[str] | None = None) -> list[dict[str, Any]]:
    wanted = set(only) if only else None
    rows = []
    for market in settings["markets"]:
        if wanted is not None and market["code"] not in wanted:
            continue
        if wanted is None and not market["enabled"]:
            continue
        rows.append(market)
    return rows
