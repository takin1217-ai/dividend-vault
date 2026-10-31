"""Walk-forward dividend research. Training rows end before the date being scored."""

from __future__ import annotations

import math
import sqlite3
from bisect import bisect_right
from dataclasses import dataclass
from datetime import date, timedelta
from statistics import mean, pstdev
from typing import Any

from dividend_vault.costs import charge
from dividend_vault.dividend_exit import event_from_lists
from dividend_vault.settings import market_by_code


@dataclass
class History:
    symbol: str
    name: str
    dates: list[str]
    close: list[float]
    adj: list[float]
    volume: list[float]
    div_dates: list[str]
    div_amounts: list[float]


def percentile_ranks(values: list[float]) -> list[float]:
    count = len(values)
    if count == 0:
        return []
    if count == 1:
        return [1.0]
    order = sorted(range(count), key=lambda index: values[index])
    ranks = [0.0] * count
    cursor = 0
    while cursor < count:
        end = cursor
        while end + 1 < count and values[order[end + 1]] == values[order[cursor]]:
            end += 1
        rank = ((cursor + end) / 2) / (count - 1)
        for position in range(cursor, end + 1):
            ranks[order[position]] = rank
        cursor = end + 1
    return ranks


def tilt_weights(scores: list[float], cap: float = 4.0) -> list[float]:
    if not scores:
        return []
    cap = max(1.0, cap)
    if cap == 1:
        share = 1 / len(scores)
        return [share] * len(scores)
    ranks = percentile_ranks(scores)
    floor = 1 / cap
    raw = [floor + (1 - floor) * rank for rank in ranks]
    total = sum(raw) or 1
    return [item / total for item in raw]


def _year_before(iso_day: str) -> str:
    stamp = date.fromisoformat(iso_day) - timedelta(days=365)
    return stamp.isoformat()


def trailing_yield(history: History, day: str, price: float) -> float:
    if price <= 0:
        return 0.0
    start = _year_before(day)
    left = bisect_right(history.div_dates, start)
    right = bisect_right(history.div_dates, day)
    total = sum(history.div_amounts[left:right])
    return max(0.0, total / price)


def _index_on(history: History, day: str) -> int | None:
    index = bisect_right(history.dates, day) - 1
    if index < 0:
        return None
    if (date.fromisoformat(day) - date.fromisoformat(history.dates[index])).days > 7:
        return None
    return index


def _features(history: History, day: str) -> dict[str, float] | None:
    index = _index_on(history, day)
    if index is None or index < 70:
        return None
    price = history.close[index]
    if price <= 0:
        return None

    def momentum(span: int) -> float | None:
        if index - span < 0:
            return None
        base = history.adj[index - span]
        if base <= 0:
            return None
        return history.adj[index] / base - 1

    returns = []
    for cursor in range(index - 62, index + 1):
        previous = history.adj[cursor - 1]
        if previous <= 0:
            continue
        returns.append(history.adj[cursor] / previous - 1)
    if len(returns) < 30:
        return None
    dollars = []
    for cursor in range(max(0, index - 20), index + 1):
        dollars.append(max(history.close[cursor], 0) * max(history.volume[cursor], 0))
    mom63 = momentum(63)
    if mom63 is None:
        return None
    return {
        "yield": trailing_yield(history, history.dates[index], price),
        "mom21": momentum(21) or 0.0,
        "mom63": mom63,
        "mom126": momentum(126) or mom63,
        "vol": pstdev(returns),
        "log_dollar": math.log(max(mean(dollars), 1.0)),
    }


FEATURE_KEYS = ("yield", "mom21", "mom63", "mom126", "vol", "log_dollar")


def load_histories(conn: sqlite3.Connection, market: str) -> list[History]:
    symbols = conn.execute(
        """
        SELECT symbol, name FROM symbols
        WHERE market=? AND selected=1 AND instrument IN ('EQUITY', 'ETP')
        ORDER BY pool_rank
        """,
        (market,),
    ).fetchall()
    histories = []
    for symbol in symbols:
        bars = conn.execute(
            """
            SELECT date, close, adj_close, volume FROM bars
            WHERE symbol=? AND close>0
            ORDER BY date
            """,
            (symbol["symbol"],),
        ).fetchall()
        if len(bars) < 80:
            continue
        dividends = conn.execute(
            "SELECT ex_date, amount FROM dividends WHERE symbol=? AND amount>0 ORDER BY ex_date",
            (symbol["symbol"],),
        ).fetchall()
        histories.append(
            History(
                symbol=symbol["symbol"],
                name=symbol["name"] or symbol["symbol"],
                dates=[row["date"] for row in bars],
                close=[float(row["close"]) for row in bars],
                adj=[float(row["adj_close"] or row["close"]) for row in bars],
                volume=[float(row["volume"] or 0) for row in bars],
                div_dates=[row["ex_date"] for row in dividends],
                div_amounts=[float(row["amount"]) for row in dividends],
            )
        )
    return histories


def _master_dates(histories: list[History]) -> list[str]:
    counts: dict[str, int] = {}
    for history in histories:
        for day in history.dates:
            counts[day] = counts.get(day, 0) + 1
    if len(histories) < 12:
        threshold = 1
    else:
        threshold = max(3, int(len(histories) * 0.25))
    return sorted(day for day, count in counts.items() if count >= threshold)


def _rebalance_indexes(day_count: int) -> list[int]:
    start = 252 if day_count > 400 else max(80, day_count // 5)
    return list(range(start, max(start, day_count - 1), 21))


def _aligned_prices(histories: list[History], days: list[str]) -> dict[str, list[float | None]]:
    aligned: dict[str, list[float | None]] = {}
    for history in histories:
        series: list[float | None] = []
        cursor = 0
        last_price = None
        last_day: date | None = None
        for day in days:
            while cursor < len(history.dates) and history.dates[cursor] < day:
                cursor += 1
            if cursor < len(history.dates) and history.dates[cursor] == day:
                last_price = history.adj[cursor]
                last_day = date.fromisoformat(day)
                series.append(last_price)
                continue
            if last_price is not None and last_day is not None:
                if (date.fromisoformat(day) - last_day).days <= 5:
                    series.append(last_price)
                    continue
            series.append(None)
            last_price = None
            last_day = None
        aligned[history.symbol] = series
    return aligned


def _ridge_predict(train_x: list[list[float]], train_y: list[float], pred_x: list[list[float]]) -> list[float] | None:
    if len(train_x) < 80 or len(pred_x) == 0:
        return None
    import numpy as np

    matrix = np.asarray(train_x, dtype=float)
    target = np.asarray(train_y, dtype=float)
    center = matrix.mean(axis=0)
    scale = matrix.std(axis=0)
    scale[scale < 1e-8] = 1
    scaled = (matrix - center) / scale
    design = np.column_stack([np.ones(len(scaled)), scaled])
    penalty = np.eye(design.shape[1]) * 5.0
    penalty[0, 0] = 0
    try:
        beta = np.linalg.solve(design.T @ design + penalty, design.T @ target)
    except np.linalg.LinAlgError:
        beta = np.linalg.lstsq(design.T @ design + penalty, design.T @ target, rcond=None)[0]
    incoming = np.asarray(pred_x, dtype=float)
    incoming = (incoming - center) / scale
    designed = np.column_stack([np.ones(len(incoming)), incoming])
    return [float(value) for value in designed @ beta]


def _scores(
    strategy: str,
    rows: list[dict[str, Any]],
    prediction: list[float] | None,
    model_weight: float,
) -> list[float]:
    if strategy == "equal":
        return [1.0] * len(rows)
    if strategy == "momentum_tilt":
        return [float(row["mom63"]) for row in rows]
    if strategy == "lowvol_tilt":
        return [float(row["yield"]) / max(float(row["vol"]), 1e-4) for row in rows]
    if strategy == "learned_tilt" and prediction is not None:
        yield_rank = percentile_ranks([float(row["yield"]) for row in rows])
        pred_rank = percentile_ranks(prediction)
        weight = min(1.0, max(0.0, model_weight))
        return [(1 - weight) * left + weight * right for left, right in zip(yield_rank, pred_rank)]
    return [float(row["yield"]) for row in rows]


def _pick(rows: list[dict[str, Any]], scores: list[float], min_yield: float, max_yield: float, minimum: int) -> list[int]:
    primary = [
        index
        for index, row in enumerate(rows)
        if min_yield <= row["yield"] <= max_yield
    ]
    if len(primary) >= minimum:
        return primary
    spare = [index for index in range(len(rows)) if index not in set(primary) and rows[index]["yield"] <= max_yield]
    spare.sort(key=lambda index: rows[index]["yield"], reverse=True)
    chosen = list(primary)
    for index in spare:
        if len(chosen) >= minimum:
            break
        chosen.append(index)
    return chosen



def _take_profit(market: str, shares: float, price: float | None, entry: float | None) -> bool:
    if price is None or price != price or entry is None or shares <= 0 or price <= entry:
        return False
    fee = charge(market, "sell", shares * price)
    return (price - entry) * shares - fee > 0


def _replay(
    histories: list[History],
    days: list[str],
    aligned: dict[str, list[float | None]],
    settings: dict[str, Any],
    market: dict[str, Any],
    strategy: str,
) -> dict[str, Any]:
    by_symbol = {history.symbol: history for history in histories}
    step_indexes = _rebalance_indexes(len(days))
    if len(step_indexes) < 2:
        raise RuntimeError(f"{market['name']} 일봉이 아직 짧아 모의투자를 시작할 수 없습니다.")
    feature_cache: dict[tuple[str, str], dict[str, float] | None] = {}
    train_x: list[list[float]] = []
    train_y: list[float] = []
    equity = 1.0
    cash = 0.0
    entries: dict[str, float] = {}
    blocked: set[str] = set()
    units: dict[str, float] = {}
    curve: list[dict[str, Any]] = []
    turnovers: list[float] = []
    holding_counts: list[int] = []
    anomalies = 0
    last_holdings: list[dict[str, Any]] = []
    note = ""
    fee_rate = float(settings["fee_bps"]) / 10000.0
    cap = float(settings["weight_cap"])
    next_rebalance = 0

    def price_at(symbol: str, index: int) -> float | None:
        value = aligned[symbol][index]
        return value

    cut_ratio = float(settings.get("dividend_cut_ratio", 0.2))
    for index in range(step_indexes[0], len(days)):
        if settings.get("dividend_exit", True):
            for symbol in list(units):
                history = by_symbol[symbol]
                kind = event_from_lists(history.div_dates, history.div_amounts, days[index], cut_ratio)
                price = aligned[symbol][index]
                entry = entries.get(symbol)
                if kind is None:
                    blocked.discard(symbol)
                    continue
                blocked.add(symbol)
                if price is None or price != price or entry is None:
                    continue
                shares = units[symbol]
                sell_fee = charge(market["code"], "sell", shares * price)
                if (price - entry) * shares - sell_fee <= 0:
                    continue
                cash += shares * price - sell_fee
                units.pop(symbol)
                entries.pop(symbol, None)
        if next_rebalance < len(step_indexes) and index == step_indexes[next_rebalance]:
            day = days[index]
            current = []
            for history in histories:
                key = (history.symbol, day)
                if key not in feature_cache:
                    feature_cache[key] = _features(history, day)
                row = feature_cache[key]
                if row is not None and history.symbol not in blocked:
                    current.append((history, row))
            prediction = None
            if strategy == "learned_tilt":
                if len(train_x) >= 80:
                    prediction = _ridge_predict(
                        train_x,
                        train_y,
                        [[row[key] for key in FEATURE_KEYS] for _, row in current],
                    )
                elif not note:
                    note = "학습 표본이 부족해 초기 구간은 배당수익률 기울기로 대체했습니다."
            if len(current) >= 2:
                score_rows = [row for _, row in current]
                scores = _scores(strategy, score_rows, prediction, float(settings["model_weight"]))
                chosen = _pick(
                    score_rows,
                    scores,
                    float(settings["min_yield"]),
                    float(settings["max_yield"]),
                    min(int(market["min_symbols"]), len(score_rows)),
                )
                if chosen:
                    weights = tilt_weights(
                        [scores[item] for item in chosen],
                        1 if strategy == "equal" else cap,
                    )
                    marked = _mark(units, aligned, index, [0])
                    if marked + cash > 0:
                        equity = marked + cash
                    parked = {
                        symbol: shares
                        for symbol, shares in units.items()
                        if symbol in blocked or not _take_profit(market["code"], shares, price_at(symbol, index), entries.get(symbol))
                    }
                    parked_value = sum((price_at(symbol, index) or 0) * shares for symbol, shares in parked.items())
                    tradable = max(0.0, equity - parked_value)
                    old = {}
                    if tradable > 0:
                        for symbol, shares in units.items():
                            if symbol in parked:
                                continue
                            price = price_at(symbol, index) or 0
                            old[symbol] = shares * price / tradable
                    new = {
                        current[item][0].symbol: weight
                        for item, weight in zip(chosen, weights)
                        if weight > 0 and price_at(current[item][0].symbol, index)
                    }
                    names = set(old) | set(new)
                    if not old:
                        turnover = sum(new.values())
                    else:
                        turnover = sum(abs(new.get(name, 0) - old.get(name, 0)) for name in names) / 2
                    tradable *= max(0.0, 1 - turnover * fee_rate)
                    prior_units = dict(units)
                    units = dict(parked)
                    holdings = []
                    for symbol, weight in new.items():
                        price = price_at(symbol, index)
                        if not price:
                            continue
                        if symbol not in parked:
                            units[symbol] = tradable * weight / price
                        holdings.append(
                            {
                                "symbol": symbol,
                                "name": by_symbol[symbol].name,
                                "weight": weight,
                                "yield": next(row["yield"] for hist, row in current if hist.symbol == symbol),
                            }
                        )
                    turnovers.append(turnover)
                    holding_counts.append(len(holdings))
                    last_holdings = holdings
                    for symbol, shares in units.items():
                        price = price_at(symbol, index)
                        previous_shares = prior_units.get(symbol, 0.0)
                        previous = entries.get(symbol)
                        if price and (previous is None or previous_shares <= 0):
                            entries[symbol] = price
                        elif price and previous is not None and shares > previous_shares:
                            entries[symbol] = (previous_shares * previous + (shares - previous_shares) * price) / shares
                    for symbol in list(entries):
                        if symbol not in units:
                            entries.pop(symbol, None)
                    cash = 0.0
            if next_rebalance + 1 < len(step_indexes):
                future = days[step_indexes[next_rebalance + 1]]
                for history, row in current:
                    left = _index_on(history, day)
                    right = _index_on(history, future)
                    if left is None or right is None or history.adj[left] <= 0:
                        continue
                    forward = history.adj[right] / history.adj[left] - 1
                    if -0.9 <= forward <= 5:
                        train_x.append([row[key] for key in FEATURE_KEYS])
                        train_y.append(forward)
            next_rebalance += 1

        marked = _mark(units, aligned, index, anomalies_box := [anomalies])
        anomalies = anomalies_box[0]
        if marked + cash > 0:
            equity = marked + cash
            curve.append({"date": days[index], "equity": equity})

    metrics = _metrics(curve, turnovers, holding_counts, anomalies, len(train_x))
    metrics["note"] = note
    holdings = sorted(last_holdings, key=lambda item: item["weight"], reverse=True)
    return {"metrics": metrics, "equity": curve, "holdings": holdings[:80], "holding_count": len(last_holdings)}


def _mark(
    units: dict[str, float],
    aligned: dict[str, list[float | None]],
    index: int,
    anomalies_box: list[int],
) -> float:
    total = 0.0
    for symbol, shares in units.items():
        price = aligned[symbol][index]
        previous = aligned[symbol][index - 1] if index else price
        if price is None:
            price = previous
        if price is None or previous is None or previous <= 0:
            continue
        if price / previous - 1 > 5 or price / previous - 1 < -0.9:
            anomalies_box[0] += 1
            price = previous
        total += shares * price
    return total


def _metrics(
    curve: list[dict[str, Any]],
    turnovers: list[float],
    holding_counts: list[int],
    anomalies: int,
    train_rows: int,
) -> dict[str, Any]:
    if len(curve) < 2:
        raise RuntimeError("성과를 계산할 만큼 곡선이 길지 않습니다.")
    start_value = curve[0]["equity"]
    end_value = curve[-1]["equity"]
    start = date.fromisoformat(curve[0]["date"])
    end = date.fromisoformat(curve[-1]["date"])
    span = max(1, (end - start).days)
    total_return = end_value / start_value - 1 if start_value else 0
    cagr = (end_value / start_value) ** (365.25 / span) - 1 if start_value > 0 and end_value > 0 else None
    peak = start_value
    max_drawdown = 0.0
    returns = []
    for point in curve:
        value = point["equity"]
        peak = max(peak, value)
        if peak:
            max_drawdown = min(max_drawdown, value / peak - 1)
    for previous, current in zip(curve, curve[1:]):
        if previous["equity"]:
            returns.append(current["equity"] / previous["equity"] - 1)
    volatility = None
    sharpe = None
    if len(returns) > 5:
        deviation = pstdev(returns)
        volatility = deviation * math.sqrt(252)
        if deviation > 0:
            sharpe = mean(returns) / deviation * math.sqrt(252)
    return {
        "start": curve[0]["date"],
        "end": curve[-1]["date"],
        "days": span,
        "total_return": total_return,
        "cagr": cagr,
        "mdd": max_drawdown,
        "volatility": volatility,
        "sharpe": sharpe,
        "avg_holdings": mean(holding_counts) if holding_counts else 0,
        "avg_turnover": mean(turnovers) if turnovers else 0,
        "rebalances": len(turnovers),
        "anomalies": anomalies,
        "train_rows": train_rows,
        "note": "",
    }


def run_backtest(conn: sqlite3.Connection, market_code: str, settings: dict[str, Any], strategy: str | None = None) -> dict[str, Any]:
    market = market_by_code(settings, market_code)
    chosen = strategy or settings["strategy"]
    histories = load_histories(conn, market_code)
    if len(histories) < 2:
        raise RuntimeError(f"{market['name']}에서 일봉이 충분한 선택 종목이 2개 미만입니다.")
    primary = _replay(histories, _master_dates(histories), _aligned_prices(histories, _master_dates(histories)), settings, market, chosen)
    # master dates computed twice — compute once
    return primary


def run_backtest_pair(conn: sqlite3.Connection, market_code: str, settings: dict[str, Any], strategy: str | None = None) -> dict[str, Any]:
    market = market_by_code(settings, market_code)
    chosen = strategy or settings["strategy"]
    histories = load_histories(conn, market_code)
    if len(histories) < 2:
        raise RuntimeError(f"{market['name']}에서 일봉이 충분한 선택 종목이 2개 미만입니다.")
    days = _master_dates(histories)
    aligned = _aligned_prices(histories, days)
    primary = _replay(histories, days, aligned, settings, market, chosen)
    baseline = None
    if chosen != "equal":
        baseline = _replay(histories, days, aligned, settings, market, "equal")
    return {
        "market": market_code,
        "strategy": chosen,
        "metrics": primary["metrics"],
        "baseline": None if baseline is None else baseline["metrics"],
        "equity": _downsample(primary["equity"]),
        "baseline_equity": None if baseline is None else _downsample(baseline["equity"]),
        "holdings": primary["holdings"],
        "holding_count": primary["holding_count"],
    }


def latest_book(conn: sqlite3.Connection, market_code: str, settings: dict[str, Any], strategy: str | None = None) -> dict[str, Any]:
    market = market_by_code(settings, market_code)
    chosen = strategy or settings["strategy"]
    histories = load_histories(conn, market_code)
    if len(histories) < 2:
        raise RuntimeError(f"{market['name']} 일봉이 부족합니다.")
    days = _master_dates(histories)
    if len(_rebalance_indexes(len(days))) < 2:
        raise RuntimeError("최근 비중을 계산할 만큼 달력이 길지 않습니다.")
    # Fit on every rebalance except the last, then score the last day.
    probe = _replay(histories, days, _aligned_prices(histories, days), settings, market, chosen)
    return {
        "market": market_code,
        "strategy": chosen,
        "asof": probe["equity"][-1]["date"] if probe["equity"] else days[-1],
        "holdings": probe["holdings"],
        "holding_count": probe["holding_count"],
        "metrics_preview": probe["metrics"],
    }


def _downsample(points: list[dict[str, Any]], limit: int = 520) -> list[dict[str, Any]]:
    if len(points) <= limit:
        return points
    stride = math.ceil(len(points) / limit)
    sampled = points[::stride]
    if sampled[-1]["date"] != points[-1]["date"]:
        sampled.append(points[-1])
    return sampled
