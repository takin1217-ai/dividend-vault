"""Yahoo chart and screener client. One crumb per thread, with backoff."""

from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any
from urllib.parse import quote

import httpx

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 DividendVault/1.0"
)

_thread = threading.local()
_rate_lock = threading.Lock()
_next_slot = 0.0


class YahooError(RuntimeError):
    pass


class RateLimiter:
    def __init__(self, per_second: float = 2.2) -> None:
        self.interval = 1.0 / per_second

    def wait(self) -> None:
        global _next_slot
        with _rate_lock:
            now = time.monotonic()
            slot = max(now, _next_slot)
            _next_slot = slot + self.interval
            delay = slot - now
        if delay > 0:
            time.sleep(delay)


LIMITER = RateLimiter()


def _client() -> httpx.Client:
    client = getattr(_thread, "client", None)
    if client is None:
        client = httpx.Client(
            timeout=httpx.Timeout(40.0, connect=15.0),
            follow_redirects=True,
            headers={"User-Agent": USER_AGENT},
        )
        _thread.client = client
        _thread.crumb = None
    return client


def _crumb(force: bool = False) -> str:
    client = _client()
    crumb = getattr(_thread, "crumb", None)
    if crumb and not force:
        return crumb
    client.get("https://fc.yahoo.com")
    response = client.get("https://query1.finance.yahoo.com/v1/test/getcrumb")
    text = response.text.strip()
    if response.status_code != 200 or not text or text.startswith("{"):
        raise YahooError(f"crumb 발급 실패 ({response.status_code})")
    _thread.crumb = text
    return text


def _get(url: str, params: dict[str, Any] | None = None, crumb: bool = False) -> httpx.Response:
    last_error: Exception | None = None
    for attempt in range(5):
        LIMITER.wait()
        params = dict(params or {})
        if crumb:
            params["crumb"] = _crumb(force=attempt > 0 and last_error is not None)
        host = "query2" if attempt % 2 else "query1"
        target = url.replace("query1", host)
        try:
            response = _client().get(target, params=params)
        except httpx.HTTPError as exc:
            last_error = exc
            time.sleep(min(8, 0.8 * (2**attempt)))
            continue
        if response.status_code in (401, 429, 500, 502, 503):
            last_error = YahooError(f"HTTP {response.status_code}")
            if response.status_code == 401:
                _crumb(force=True)
            time.sleep(min(12, 0.8 * (2**attempt)))
            continue
        return response
    raise YahooError(str(last_error or "요청 실패"))


def _post(payload: dict[str, Any]) -> dict[str, Any]:
    last_error: Exception | None = None
    for attempt in range(5):
        LIMITER.wait()
        crumb = _crumb(force=attempt > 0)
        try:
            response = _client().post(
                "https://query1.finance.yahoo.com/v1/finance/screener",
                params={"crumb": crumb, "lang": "en-US", "region": "US"},
                json=payload,
            )
        except httpx.HTTPError as exc:
            last_error = exc
            time.sleep(min(8, 0.8 * (2**attempt)))
            continue
        if response.status_code in (401, 429, 500, 502, 503):
            last_error = YahooError(response.text[:180])
            time.sleep(min(12, 0.8 * (2**attempt)))
            continue
        if response.status_code != 200:
            raise YahooError(f"screener {response.status_code}: {response.text[:180]}")
        body = response.json()
        result = (body.get("finance") or {}).get("result") or []
        if not result:
            error = (body.get("finance") or {}).get("error")
            raise YahooError(str(error or "screener 결과 없음"))
        return result[0]
    raise YahooError(str(last_error or "screener 실패"))


def screen_dividend_pages(region: str, quote_type: str, page_size: int = 250, hard_cap: int = 8000) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    offset = 0
    total = None
    while offset < hard_cap:
        payload = {
            "size": page_size,
            "offset": offset,
            "sortField": "intradaymarketcap",
            "sortType": "DESC",
            "quoteType": quote_type,
            "query": {
                "operator": "AND",
                "operands": [
                    {"operator": "EQ", "operands": ["region", region]},
                    {"operator": "GT", "operands": ["forward_dividend_yield", 0.001]},
                ],
            },
        }
        page = _post(payload)
        total = page.get("total") or 0
        quotes = page.get("quotes") or []
        if not quotes:
            break
        rows.extend(quotes)
        offset += len(quotes)
        if offset >= total or len(quotes) < page_size:
            break
    return rows


def _exchange_date(timestamp: int, offset_seconds: int) -> str:
    moment = datetime.fromtimestamp(int(timestamp) + int(offset_seconds), timezone.utc)
    return moment.strftime("%Y-%m-%d")


def fetch_chart(symbol: str, start: datetime, end: datetime) -> dict[str, Any]:
    url = "https://query1.finance.yahoo.com/v8/finance/chart/" + quote(symbol, safe="")
    response = _get(
        url,
        {
            "interval": "1d",
            "period1": str(int(start.timestamp())),
            "period2": str(int(end.timestamp())),
            "events": "div,split",
            "includeAdjustedClose": "true",
        },
    )
    if response.status_code != 200:
        raise YahooError(f"{symbol} HTTP {response.status_code}")
    body = response.json().get("chart") or {}
    if body.get("error") or not body.get("result"):
        description = (body.get("error") or {}).get("description") or "차트 없음"
        raise YahooError(f"{symbol}: {description}")
    result = body["result"][0]
    meta = result.get("meta") or {}
    offset = int(meta.get("gmtoffset") or 0)
    quote_block = ((result.get("indicators") or {}).get("quote") or [{}])[0]
    adj_block = ((result.get("indicators") or {}).get("adjclose") or [{}])[0].get("adjclose") or []
    stamps = result.get("timestamp") or []
    merged: dict[str, dict[str, Any]] = {}
    for index, stamp in enumerate(stamps):
        close = _at(quote_block.get("close"), index)
        if close is None or close <= 0:
            continue
        high = _at(quote_block.get("high"), index)
        low = _at(quote_block.get("low"), index)
        if high is not None and low is not None and high < low:
            continue
        day = _exchange_date(stamp, offset)
        adj = _at(adj_block, index)
        merged[day] = {
            "date": day,
            "open": _at(quote_block.get("open"), index),
            "high": high,
            "low": low,
            "close": float(close),
            "adj_close": float(close if adj is None or adj <= 0 else adj),
            "volume": _at(quote_block.get("volume"), index) or 0,
        }
    events = result.get("events") or {}
    dividends = []
    for item in (events.get("dividends") or {}).values():
        amount = item.get("amount")
        if not amount or amount <= 0:
            continue
        dividends.append({"date": _exchange_date(item["date"], offset), "amount": float(amount)})
    splits = []
    for item in (events.get("splits") or {}).values():
        numerator = item.get("numerator")
        denominator = item.get("denominator")
        if not numerator or not denominator:
            continue
        splits.append(
            {
                "date": _exchange_date(item["date"], offset),
                "numerator": float(numerator),
                "denominator": float(denominator),
            }
        )
    return {
        "currency": meta.get("currency"),
        "exchange": meta.get("exchangeName"),
        "instrument": meta.get("instrumentType") or "EQUITY",
        "name": meta.get("longName") or meta.get("shortName"),
        "bars": [merged[key] for key in sorted(merged)],
        "dividends": dividends,
        "splits": splits,
    }


def _at(values: list[Any] | None, index: int) -> float | None:
    if not values or index >= len(values):
        return None
    value = values[index]
    if value is None:
        return None
    return float(value)


def window_for(years: int, last_date: str | None) -> tuple[datetime, datetime]:
    end = datetime.now(timezone.utc) + timedelta(days=2)
    if last_date:
        start = datetime.fromisoformat(last_date).replace(tzinfo=timezone.utc) - timedelta(days=14)
    else:
        start = end - timedelta(days=int(years * 365.25) + 21)
    return start, end
