"""배당금고 명령.

python -m dividend_vault sync
"""

from __future__ import annotations

import argparse
import json

from dividend_vault.db import backup_vault, integrity, load_settings, open_vault
from dividend_vault.jobs import run_sync
from dividend_vault.paper import rebalance_market
from dividend_vault.report import market_status, totals
from dividend_vault.settings import enabled_markets
from dividend_vault.strategy import run_backtest_pair, store_backtest


def _print_progress(message: str, done: int, total: int) -> None:
    if total:
        print(f"[{done}/{total}] {message}", flush=True)
    else:
        print(message, flush=True)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="dividend_vault", description="배당금고")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init", help="금고 파일을 만듭니다")
    status = sub.add_parser("status", help="시장별 일봉 현황")
    status.add_argument("--json", action="store_true")

    sync = sub.add_parser("sync", help="유니버스를 고르고 일봉을 이어서 받습니다")
    sync.add_argument("--markets", default="", help="예: US,CN,HK,JP,VN")
    sync.add_argument("--force", action="store_true")

    backtest = sub.add_parser("backtest", help="과거 모의투자")
    backtest.add_argument("--market", default="US")
    backtest.add_argument("--strategy", default=None)

    prepare = sub.add_parser("prepare", help="2배 일봉을 받고 과거 모의와 가상 모의를 마칩니다")
    prepare.add_argument("--markets", default="", help="예: US,KR,GB")

    sub.add_parser("backup", help="오늘 백업이 없으면 하나 만듭니다")

    args = parser.parse_args(argv)
    if args.command == "init":
        conn = open_vault()
        load_settings(conn)
        conn.close()
        print("금고를 준비했습니다.")
        return
    if args.command == "status":
        conn = open_vault()
        try:
            settings = load_settings(conn)
            payload = {"markets": market_status(conn, settings), "totals": totals(conn), "integrity": integrity(conn)}
        finally:
            conn.close()
        if args.json:
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            return
        print(f"종목 {payload['totals']['symbols']}  일봉 {payload['totals']['bars']}  배당 {payload['totals']['dividends']}  개정 {payload['totals']['revisions']}")
        for market in payload["markets"]:
            flag = "사용" if market["enabled"] else "끔"
            print(
                f"{market['name']:4} {flag}  선택 {market['selected']}/{market['target_symbols']}  "
                f"일봉종목 {market['with_bars']}  봉 {market['bars']}  "
                f"평균 {market['avg_years']:.1f}년  9년이상 {market['decade']}  실패 {market['failed']}"
            )
        return
    if args.command == "sync":
        markets = [item.strip().upper() for item in args.markets.split(",") if item.strip()] or None
        result = run_sync(markets, args.force, _print_progress)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    if args.command == "backtest":
        conn = open_vault()
        try:
            settings = load_settings(conn)
            result = run_backtest_pair(conn, args.market.upper(), settings, args.strategy)
        finally:
            conn.close()
        print(json.dumps({"metrics": result["metrics"], "baseline": result["baseline"]}, ensure_ascii=False, indent=2))
        return
    if args.command == "prepare":
        markets = [item.strip().upper() for item in args.markets.split(",") if item.strip()] or None
        sync_result = run_sync(markets, False, _print_progress)
        print(json.dumps(sync_result, ensure_ascii=False, indent=2))
        conn = open_vault()
        try:
            settings = load_settings(conn)
            for market in enabled_markets(settings, markets):
                code = market["code"]
                try:
                    result = run_backtest_pair(conn, code, settings, settings["strategy"])
                    store_backtest(conn, result)
                    paper = rebalance_market(conn, code, settings, settings["strategy"])
                    print(f"{market['name']} 과거 모의와 가상 모의를 마쳤습니다. 주문 {len(paper.get('orders') or [])}건", flush=True)
                except Exception as exc:
                    print(f"{market['name']} 모의 실패: {exc}", flush=True)
        finally:
            conn.close()
        return
    if args.command == "backup":
        conn = open_vault()
        try:
            path = backup_vault(conn, "manual")
        finally:
            conn.close()
        print(path or "백업할 일봉이 아직 없습니다.")
        return


if __name__ == "__main__":
    main()
