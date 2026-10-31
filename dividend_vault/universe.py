"""Build a dividend universe. Equities fill the minimum; ETPs are optional."""

from __future__ import annotations

import re
import time
from typing import Any, Callable

import httpx

from dividend_vault import yahoo
from dividend_vault.db import utc_now, upsert_symbol

Progress = Callable[[str, int, int], None]

PREFERRED = re.compile(r"preferred|\bpfd\b|\bpref\b|\(1p\)|depositary shares", re.I)
PREFERRED_SYMBOL = re.compile(r"-P[A-Z]?$")
LEVERAGED = re.compile(r"\b(2x|3x|ultra|inverse|bear)\b", re.I)
WARRANT = re.compile(r"warrant|\bright\b", re.I)


def quote_yield_fraction(quote: dict[str, Any]) -> float | None:
    """Yahoo dividendYield is a percent. Trailing yield is a fraction when it is sane."""
    trailing = quote.get("trailingAnnualDividendYield")
    if isinstance(trailing, (int, float)) and 0 < float(trailing) <= 0.25:
        return float(trailing)
    dividend_yield = quote.get("dividendYield")
    if isinstance(dividend_yield, (int, float)) and 0 < float(dividend_yield) <= 30:
        return float(dividend_yield) / 100.0
    if isinstance(trailing, (int, float)) and 0 < float(trailing) <= 0.25:
        return float(trailing)
    return None


def accept_name(symbol: str, name: str | None, instrument: str, settings: dict[str, Any]) -> bool:
    label = name or ""
    if settings.get("exclude_preferred", True):
        if PREFERRED.search(label) or PREFERRED_SYMBOL.search(symbol):
            return False
    if WARRANT.search(label):
        return False
    if instrument == "ETP" and settings.get("exclude_leveraged", True) and LEVERAGED.search(label):
        return False
    return True


def _sort_key(row: dict[str, Any], mode: str) -> tuple:
    yld = row.get("yield_frac") or 0
    cap = row.get("market_cap") or 0
    if mode == "yield":
        return (yld, cap)
    if mode == "balanced":
        return (yld * (cap**0.15), yld)
    return (cap, yld)


def choose_symbols(
    candidates: list[dict[str, Any]],
    market: dict[str, Any],
    settings: dict[str, Any],
    sort_mode: str = "market_cap",
) -> tuple[list[dict[str, Any]], list[str]]:
    notes: list[str] = []
    target = int(market["target_symbols"])
    minimum = int(market["min_symbols"])
    ceiling = int(market["max_symbols"])
    bands = [
        (settings["min_yield"], settings["max_yield"], market["min_market_cap"], settings["min_avg_volume"]),
        (settings["min_yield"], max(settings["max_yield"], 0.15), market["min_market_cap"], settings["min_avg_volume"]),
        (max(0.005, settings["min_yield"] * 0.5), 0.18, market["min_market_cap"], 0),
        (0.0, 0.25, 0, 0),
    ]
    pool: list[dict[str, Any]] = []
    for index, (lo, hi, cap, volume) in enumerate(bands):
        pool = []
        for row in candidates:
            yld = row.get("yield_frac")
            if yld is None or not (lo <= yld <= hi):
                continue
            if cap and (row.get("market_cap") or 0) < cap:
                continue
            if volume and (row.get("avg_volume") or 0) < volume:
                continue
            pool.append(row)
        pool.sort(key=lambda item: _sort_key(item, sort_mode), reverse=True)
        if len(pool) >= target or index == len(bands) - 1:
            if index:
                notes.append(f"조건을 완화했습니다. 배당수익률 {lo:.2%}~{hi:.2%}, 시가총액 하한 {cap:.0f}.")
            break
    if len(pool) < minimum:
        notes.append(f"조건에 맞는 종목이 최소 보유 {minimum}보다 적습니다. 확보 {len(pool)}.")
    pool = pool[:ceiling]
    for rank, row in enumerate(pool, start=1):
        row["pool_rank"] = rank
        row["selected"] = rank <= target
        row["select_note"] = "selected" if row["selected"] else "pool"
    return pool, notes


def _from_quote(quote: dict[str, Any], market: str, instrument: str) -> dict[str, Any] | None:
    symbol = quote.get("symbol")
    if not symbol:
        return None
    return {
        "symbol": symbol,
        "market": market,
        "name": quote.get("longName") or quote.get("shortName"),
        "instrument": instrument,
        "exchange": quote.get("exchange"),
        "currency": quote.get("currency"),
        "yield_frac": quote_yield_fraction(quote),
        "market_cap": quote.get("marketCap"),
        "avg_volume": quote.get("averageDailyVolume3Month"),
        "listed_date": None,
    }


def fetch_yahoo_candidates(market: dict[str, Any], settings: dict[str, Any], progress: Progress) -> list[dict[str, Any]]:
    progress(f"{market['name']} 배당 후보를 받는 중", 0, 1)
    quotes = yahoo.screen_dividend_pages(market["yahoo_region"], "EQUITY")
    rows = []
    for quote in quotes:
        row = _from_quote(quote, market["code"], "EQUITY")
        if row and accept_name(row["symbol"], row["name"], "EQUITY", settings):
            rows.append(row)
    if settings.get("include_etp") and settings.get("etp_limit", 0) > 0:
        etp_quotes = yahoo.screen_dividend_pages(market["yahoo_region"], "ETF", hard_cap=2000)
        etps = []
        for quote in etp_quotes:
            row = _from_quote(quote, market["code"], "ETP")
            if row and accept_name(row["symbol"], row["name"], "ETP", settings):
                etps.append(row)
        etps.sort(key=lambda item: item.get("yield_frac") or 0, reverse=True)
        rows.extend(etps[: int(settings["etp_limit"])])
    return rows


def fetch_vietnam(market: dict[str, Any], progress: Progress) -> list[dict[str, Any]]:
    progress("베트남 상장 목록을 받는 중", 0, 1)
    client = httpx.Client(timeout=40, headers={"User-Agent": yahoo.USER_AGENT})
    page = 1
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    while page <= 40:
        response = client.get(
            "https://api-finfo.vndirect.com.vn/v4/stocks",
            params={"q": "type:stock~status:listed", "size": 100, "page": page},
        )
        response.raise_for_status()
        body = response.json()
        data = body.get("data") or []
        if not data:
            break
        for item in data:
            code = str(item.get("code") or "").upper()
            floor = str(item.get("floor") or "").upper()
            if floor not in {"HOSE", "HNX"} or not re.fullmatch(r"[A-Z]{2,5}", code):
                continue
            symbol = f"{code}.VN"
            if symbol in seen:
                continue
            seen.add(symbol)
            rows.append(
                {
                    "symbol": symbol,
                    "market": "VN",
                    "name": item.get("companyNameEng") or item.get("shortNameEng") or code,
                    "instrument": "EQUITY",
                    "exchange": floor,
                    "currency": "VND",
                    "yield_frac": None,
                    "market_cap": None,
                    "avg_volume": None,
                    "listed_date": (item.get("listedDate") or "")[:10] or None,
                    "_floor_rank": 0 if floor == "HOSE" else 1,
                }
            )
        total_pages = int(body.get("totalPages") or page)
        if page >= total_pages:
            break
        page += 1
        time.sleep(0.15)
    rows.sort(key=lambda item: (item["_floor_rank"], item.get("listed_date") or "9999", item["symbol"]))
    ceiling = int(market["max_symbols"])
    # Older HOSE names are more likely to have a dividend record. Keep the cap, download later.
    chosen = rows[:ceiling]
    for rank, row in enumerate(chosen, start=1):
        row.pop("_floor_rank", None)
        row["pool_rank"] = rank
        row["selected"] = rank <= int(market["target_symbols"])
        row["select_note"] = "vn-provisional"
    for row in chosen:
        row.pop("_floor_rank", None)
    return chosen


def store_candidates(conn, rows: list[dict[str, Any]], market_code: str) -> None:
    now_symbols = {row["symbol"] for row in rows}
    conn.execute("BEGIN")
    try:
        existing = conn.execute(
            "SELECT symbol FROM symbols WHERE market=? AND instrument!='BENCHMARK'",
            (market_code,),
        ).fetchall()
        for item in existing:
            if item["symbol"] not in now_symbols:
                conn.execute(
                    "UPDATE symbols SET selected=0, select_note='kept-history', updated_at=? WHERE symbol=?",
                    (utc_now(), item["symbol"]),
                )
        for row in rows:
            upsert_symbol(conn, row)
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def refresh_market(conn, market: dict[str, Any], settings: dict[str, Any], progress: Progress) -> dict[str, Any]:
    if market["source"] == "vndirect":
        rows = fetch_vietnam(market, progress)
        notes = ["베트남은 거래소 상장 목록으로 풀을 만들고, 일봉의 배당으로 다시 순위를 매깁니다."]
    else:
        candidates = fetch_yahoo_candidates(market, settings, progress)
        equities = [row for row in candidates if row["instrument"] == "EQUITY"]
        etps = [row for row in candidates if row["instrument"] == "ETP"]
        rows, notes = choose_symbols(equities, market, settings)
        for index, etp in enumerate(etps, start=1):
            etp["pool_rank"] = 100000 + index
            etp["selected"] = True
            etp["select_note"] = "etp"
            rows.append(etp)
        if settings.get("include_etp"):
            notes.append(f"ETP {len(etps)}개를 주식 최소 보유와 별도로 넣었습니다.")
    store_candidates(conn, rows, market["code"])
    selected = sum(1 for row in rows if row.get("selected"))
    return {"market": market["code"], "pool": len(rows), "selected": selected, "notes": notes}


def rerank_vietnam(conn, market: dict[str, Any]) -> None:
    """After bars exist, keep history and point selection at the highest trailing yields."""
    symbols = conn.execute(
        """
        SELECT s.symbol, s.name, s.instrument, s.exchange, s.currency, s.market_cap, s.avg_volume, s.listed_date
        FROM symbols s
        WHERE s.market='VN' AND s.instrument='EQUITY'
        """
    ).fetchall()
    ranked = []
    for row in symbols:
        last = conn.execute(
            "SELECT close, date FROM bars WHERE symbol=? ORDER BY date DESC LIMIT 1",
            (row["symbol"],),
        ).fetchone()
        if last is None or not last["close"]:
            continue
        start = _year_before(last["date"])
        total = conn.execute(
            "SELECT COALESCE(SUM(amount),0) AS amount FROM dividends WHERE symbol=? AND ex_date>? AND ex_date<=?",
            (row["symbol"], start, last["date"]),
        ).fetchone()["amount"]
        ranked.append((total / last["close"], dict(row)))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked:
        return
    target = int(market["target_symbols"])
    conn.execute("BEGIN")
    try:
        for rank, (yld, row) in enumerate(ranked, start=1):
            row.update(
                {
                    "market": "VN",
                    "instrument": "EQUITY",
                    "yield_frac": yld,
                    "pool_rank": rank,
                    "selected": rank <= target,
                    "select_note": "vn-yield" if rank <= target else "vn-pool",
                }
            )
            upsert_symbol(conn, row)
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def _year_before(iso_day: str) -> str:
    year, month, day = (int(part) for part in iso_day.split("-"))
    try:
        return f"{year-1:04d}-{month:02d}-{day:02d}"
    except ValueError:
        return f"{year-1:04d}-{month:02d}-28"
