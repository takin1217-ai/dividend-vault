import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from dividend_vault.costs import buy_quantity, charge
from dividend_vault.db import open_vault, upsert_bars, upsert_symbol
from dividend_vault.dividend_exit import review_positions
from dividend_vault.kis import fresh_token
from dividend_vault.readiness import loss_sell_reason
from dividend_vault.settings import normalize_settings
from dividend_vault.strategy import run_backtest_pair, tilt_weights
from dividend_vault.universe import quote_yield_fraction


class SettingsTests(unittest.TestCase):
    def test_target_stays_between_minimum_and_five_times(self):
        wide = normalize_settings({"markets": [{"code": "US", "min_symbols": 1000, "target_symbols": 9000}]})
        us = next(item for item in wide["markets"] if item["code"] == "US")
        self.assertEqual(us["min_symbols"], 1000)
        self.assertEqual(us["max_symbols"], 5000)
        self.assertEqual(us["target_symbols"], 5000)

        narrow = normalize_settings({"markets": [{"code": "US", "min_symbols": 1000, "target_symbols": 10}]})
        us = next(item for item in narrow["markets"] if item["code"] == "US")
        self.assertEqual(us["target_symbols"], 2000)

    def test_core_markets_start_enabled(self):
        settings = normalize_settings(None)
        enabled = {item["code"]: item for item in settings["markets"] if item["enabled"]}
        self.assertEqual(set(enabled), {"US", "CN", "HK", "JP", "VN", "KR", "GB"})
        self.assertEqual(enabled["US"]["min_symbols"], 1000)
        self.assertEqual(enabled["US"]["target_symbols"], 2000)
        self.assertEqual(enabled["KR"]["min_symbols"], 250)
        self.assertEqual(enabled["KR"]["target_symbols"], 500)
        self.assertEqual(enabled["GB"]["min_symbols"], 50)
        self.assertEqual(enabled["HK"]["target_symbols"], 300)
        self.assertEqual(enabled["VN"]["target_symbols"], 100)


class YieldTests(unittest.TestCase):
    def test_quote_yield_fraction(self):
        self.assertAlmostEqual(quote_yield_fraction({"trailingAnnualDividendYield": 0.02, "dividendYield": 2.1}), 0.02)
        self.assertAlmostEqual(quote_yield_fraction({"dividendYield": 3.25}), 0.0325)
        self.assertIsNone(quote_yield_fraction({"dividendYield": 83070, "trailingAnnualDividendYield": 836}))

    def test_tilt_keeps_every_name_and_caps_the_ratio(self):
        weights = tilt_weights([1, 5], cap=4)
        self.assertAlmostEqual(sum(weights), 1)
        self.assertGreater(weights[1], weights[0])
        self.assertLessEqual(weights[1] / weights[0], 4.01)
        self.assertTrue(all(weight > 0 for weight in weights))


class VaultTests(unittest.TestCase):
    def test_price_revision_keeps_the_previous_close(self):
        with tempfile.TemporaryDirectory() as folder:
            conn = open_vault(Path(folder) / "vault.sqlite")
            upsert_symbol(conn, {"symbol": "AAA", "market": "US", "instrument": "EQUITY", "selected": 1, "pool_rank": 1})
            upsert_bars(conn, "AAA", [{"date": "2020-01-02", "close": 10, "adj_close": 10, "volume": 1}], "test")
            conn.commit()
            upsert_bars(conn, "AAA", [{"date": "2020-01-02", "close": 10, "adj_close": 10, "volume": 1}], "test")
            self.assertEqual(conn.execute("SELECT COUNT(*) AS n FROM bar_revisions").fetchone()["n"], 0)
            upsert_bars(conn, "AAA", [{"date": "2020-01-02", "close": 11, "adj_close": 11, "volume": 1}], "test")
            conn.commit()
            self.assertEqual(conn.execute("SELECT close FROM bars").fetchone()["close"], 11)
            self.assertEqual(conn.execute("SELECT COUNT(*) AS n FROM bars").fetchone()["n"], 1)
            revision = conn.execute("SELECT old_value, new_value FROM bar_revisions").fetchone()
            self.assertEqual(revision["old_value"], 10)
            self.assertEqual(revision["new_value"], 11)
            conn.close()

    def test_yield_tilt_overweights_the_higher_dividend(self):
        with tempfile.TemporaryDirectory() as folder:
            conn = open_vault(Path(folder) / "vault.sqlite")
            days = []
            cursor = date(2019, 1, 1)
            while len(days) < 180:
                if cursor.weekday() < 5:
                    days.append(cursor.isoformat())
                cursor += timedelta(days=1)
            for symbol, dividend in (("HIGH", 8.0), ("LOW", 1.0)):
                upsert_symbol(
                    conn,
                    {
                        "symbol": symbol,
                        "market": "US",
                        "name": symbol,
                        "instrument": "EQUITY",
                        "selected": 1,
                        "pool_rank": 1 if symbol == "HIGH" else 2,
                    },
                )
                bars = [
                    {"date": day, "open": 100, "high": 101, "low": 99, "close": 100, "adj_close": 100, "volume": 100000}
                    for day in days
                ]
                upsert_bars(conn, symbol, bars, "test")
                conn.execute(
                    "INSERT INTO dividends(symbol, ex_date, amount, source, updated_at) VALUES(?,?,?,?,?)",
                    (symbol, days[40], dividend, "test", "2020-01-01T00:00:00+00:00"),
                )
            conn.commit()
            settings = normalize_settings(
                {
                    "min_yield": 0,
                    "max_yield": 0.2,
                    "fee_bps": 0,
                    "strategy": "yield_tilt",
                    "markets": [{"code": "US", "enabled": True, "min_symbols": 1, "target_symbols": 2, "min_market_cap": 0}],
                }
            )
            result = run_backtest_pair(conn, "US", settings, "yield_tilt")
            weights = {item["symbol"]: item["weight"] for item in result["holdings"]}
            self.assertIn("HIGH", weights)
            self.assertIn("LOW", weights)
            self.assertGreater(weights["HIGH"], weights["LOW"])
            self.assertGreater(result["metrics"]["rebalances"], 1)
            self.assertIsNotNone(result["baseline"])
            conn.close()


class CostTests(unittest.TestCase):
    def test_vietnam_buy_covers_the_minimum_commission(self):
        self.assertAlmostEqual(charge("VN", "buy", 200_000_000), 800_000)
        self.assertEqual(buy_quantity("VN", 50_000_000, 10_000), 0)
        shares = buy_quantity("VN", 250_000_000, 10_000)
        self.assertGreaterEqual(shares * 10_000, 200_000_000)
        self.assertEqual(shares % 100, 0)

    def test_dividend_cut_sells_only_when_profitable(self):
        with tempfile.TemporaryDirectory() as folder:
            conn = open_vault(Path(folder) / "vault.sqlite")
            conn.execute(
                "INSERT INTO dividends(symbol, ex_date, amount, source, updated_at) VALUES(?,?,?,?,?)",
                ("CUT", "2024-06-01", 5, "test", "2024-06-01"),
            )
            conn.execute(
                "INSERT INTO dividends(symbol, ex_date, amount, source, updated_at) VALUES(?,?,?,?,?)",
                ("LOSE", "2024-06-01", 5, "test", "2024-06-01"),
            )
            conn.commit()
            reviews = review_positions(
                conn,
                "US",
                {"CUT": {"qty": 10, "avg_price": 80}, "LOSE": {"qty": 10, "avg_price": 120}},
                {"CUT": 100.0, "LOSE": 90.0},
                "2026-01-15",
                0.2,
            )
            by_symbol = {item["symbol"]: item for item in reviews}
            self.assertEqual(by_symbol["CUT"]["kind"], "무배당")
            self.assertTrue(by_symbol["CUT"]["sell"])
            self.assertFalse(by_symbol["LOSE"]["sell"])
            conn.execute(
                "INSERT INTO kis_orders(created_at, account_alias, mode, market, symbol, side, qty, price, status, request_json, response_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                ("2026-01-01", "desk", "paper", "US", "AAA", "buy", 10, 80, "filled", "{}", "{}"),
            )
            conn.commit()
            self.assertIsNotNone(loss_sell_reason(conn, {"alias": "desk"}, "US", "AAA", 10, 70))
            self.assertIsNone(loss_sell_reason(conn, {"alias": "desk"}, "US", "AAA", 10, 120))
            conn.close()

    def test_same_api_token_is_reused_until_expiry(self):
        now = 1_000_000.0
        slot = {"access_token": "once", "expires_at": now + 3600}
        self.assertEqual(fresh_token(slot, now), "once")
        self.assertIsNone(fresh_token(slot, now + 3600))
        self.assertIsNone(fresh_token(None, now))


if __name__ == "__main__":
    unittest.main()
