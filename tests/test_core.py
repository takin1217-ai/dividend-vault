import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from dividend_vault.db import open_vault, upsert_bars, upsert_symbol
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
        self.assertEqual(us["target_symbols"], 1000)

    def test_core_markets_start_enabled(self):
        settings = normalize_settings(None)
        enabled = {item["code"] for item in settings["markets"] if item["enabled"]}
        self.assertEqual(enabled, {"US", "CN", "HK", "JP", "VN"})


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


if __name__ == "__main__":
    unittest.main()
