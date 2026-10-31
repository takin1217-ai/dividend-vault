"""Local research desk. It listens on localhost only."""

from __future__ import annotations

import json
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from dividend_vault.db import backup_vault, integrity, load_settings, open_vault, save_settings
from dividend_vault.jobs import JobRunner, maybe_auto_sync, run_sync
from dividend_vault.kis import add_account, delete_account, ping, place_order, public_accounts
from dividend_vault.paper import account_view, rebalance_market
from dividend_vault.paths import WEB_PATH
from dividend_vault.report import market_status, symbol_page, totals
from dividend_vault.settings import normalize_settings
from dividend_vault.strategy import run_backtest_pair

RUNNER = JobRunner()


def _store_backtest(conn, result: dict) -> int:
    cursor = conn.execute(
        """
        INSERT INTO backtests(created_at, market, strategy, params_json, metrics_json, baseline_json, equity_json, holdings_json)
        VALUES(datetime('now'),?,?,?,?,?,?,?)
        """,
        (
            result["market"],
            result["strategy"],
            "{}",
            json.dumps(result["metrics"], ensure_ascii=False),
            json.dumps(result["baseline"], ensure_ascii=False) if result["baseline"] else None,
            json.dumps({"strategy": result["equity"], "baseline": result["baseline_equity"]}, ensure_ascii=False),
            json.dumps({"count": result["holding_count"], "top": result["holdings"]}, ensure_ascii=False),
        ),
    )
    conn.commit()
    return int(cursor.lastrowid)


class AppHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def _send(self, code: int, payload: dict | list | None = None, html: str | None = None) -> None:
        if html is not None:
            body = html.encode("utf-8")
            content_type = "text/html; charset=utf-8"
        else:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json; charset=utf-8"
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length > 1_000_000:
            raise ValueError("요청이 너무 큽니다.")
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_GET(self) -> None:
        try:
            self._get()
        except Exception as exc:
            self._send(400, {"error": str(exc)})

    def do_POST(self) -> None:
        try:
            self._post()
        except Exception as exc:
            self._send(400, {"error": str(exc)})

    def do_DELETE(self) -> None:
        try:
            parsed = urlparse(self.path)
            parts = [part for part in parsed.path.split("/") if part]
            if len(parts) == 4 and parts[:3] == ["api", "kis", "accounts"]:
                delete_account(parts[3])
                self._send(200, {"accounts": public_accounts()})
                return
            self._send(404, {"error": "없는 주소"})
        except Exception as exc:
            self._send(400, {"error": str(exc)})

    def _get(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path == "/":
            self._send(200, html=WEB_PATH.read_text(encoding="utf-8"))
            return
        if path == "/api/jobs":
            self._send(200, RUNNER.snapshot())
            return
        conn = open_vault()
        try:
            if path == "/api/status":
                settings = load_settings(conn)
                self._send(
                    200,
                    {
                        "settings": settings,
                        "markets": market_status(conn, settings),
                        "totals": totals(conn),
                        "integrity": integrity(conn),
                        "kis": public_accounts(),
                        "job": RUNNER.snapshot(),
                    },
                )
                return
            if path == "/api/symbols":
                market = (query.get("market") or ["US"])[0]
                offset = int((query.get("offset") or ["0"])[0])
                limit = min(100, int((query.get("limit") or ["40"])[0]))
                text = (query.get("q") or [""])[0]
                self._send(200, symbol_page(conn, market, text, offset, limit))
                return
            if path == "/api/backtests":
                rows = conn.execute(
                    "SELECT id, created_at, market, strategy, metrics_json FROM backtests ORDER BY id DESC LIMIT 12"
                ).fetchall()
                self._send(
                    200,
                    {
                        "rows": [
                            {
                                "id": row["id"],
                                "created_at": row["created_at"],
                                "market": row["market"],
                                "strategy": row["strategy"],
                                "metrics": json.loads(row["metrics_json"]),
                            }
                            for row in rows
                        ]
                    },
                )
                return
            if path.startswith("/api/backtests/"):
                backtest_id = int(path.rsplit("/", 1)[-1])
                row = conn.execute("SELECT * FROM backtests WHERE id=?", (backtest_id,)).fetchone()
                if row is None:
                    self._send(404, {"error": "결과가 없습니다."})
                    return
                self._send(
                    200,
                    {
                        "id": row["id"],
                        "market": row["market"],
                        "strategy": row["strategy"],
                        "metrics": json.loads(row["metrics_json"]),
                        "baseline": json.loads(row["baseline_json"]) if row["baseline_json"] else None,
                        "equity": json.loads(row["equity_json"]),
                        "holdings": json.loads(row["holdings_json"]),
                    },
                )
                return
            if path == "/api/paper":
                settings = load_settings(conn)
                market = (query.get("market") or ["US"])[0]
                self._send(200, account_view(conn, market, settings))
                return
            self._send(404, {"error": "없는 주소"})
        finally:
            conn.close()

    def _post(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        data = self._body()
        if path == "/api/settings":
            conn = open_vault()
            try:
                saved = save_settings(conn, normalize_settings(data))
            finally:
                conn.close()
            self._send(200, {"settings": saved})
            return
        if path == "/api/jobs/sync":
            markets = data.get("markets")
            force = bool(data.get("force"))
            RUNNER.start("sync", lambda progress: run_sync(markets, force, progress))
            self._send(200, {"started": True})
            return
        if path == "/api/jobs/backup":
            def _backup(progress):
                progress("백업", 0, 1)
                conn = open_vault()
                try:
                    path_out = backup_vault(conn, "manual")
                finally:
                    conn.close()
                return {"path": str(path_out) if path_out else None}

            RUNNER.start("backup", _backup)
            self._send(200, {"started": True})
            return
        if path == "/api/backtest":
            market = str(data.get("market") or "US")
            strategy = data.get("strategy")

            def _run(progress):
                progress(f"{market} 과거 모의투자", 0, 1)
                conn = open_vault()
                try:
                    settings = load_settings(conn)
                    if data.get("min_yield") is not None:
                        settings["min_yield"] = data["min_yield"]
                    if data.get("max_yield") is not None:
                        settings["max_yield"] = data["max_yield"]
                    if data.get("model_weight") is not None:
                        settings["model_weight"] = data["model_weight"]
                    result = run_backtest_pair(conn, market, normalize_settings(settings), strategy)
                    result["id"] = _store_backtest(conn, result)
                finally:
                    conn.close()
                progress("과거 모의투자 저장", 1, 1)
                return {"id": result["id"], "metrics": result["metrics"], "baseline": result["baseline"]}

            RUNNER.start("backtest", _run)
            self._send(200, {"started": True})
            return
        if path == "/api/paper/rebalance":
            market = str(data.get("market") or "US")
            strategy = data.get("strategy")

            def _paper(progress):
                progress(f"{market} 가상 리밸런스", 0, 1)
                conn = open_vault()
                try:
                    settings = load_settings(conn)
                    result = rebalance_market(conn, market, settings, strategy)
                finally:
                    conn.close()
                return result

            RUNNER.start("paper", _paper)
            self._send(200, {"started": True})
            return
        if path == "/api/kis/accounts":
            self._send(200, {"account": add_account(data), "accounts": public_accounts()})
            return
        if path == "/api/kis/ping":
            self._send(200, ping(str(data.get("account_id"))))
            return
        if path == "/api/kis/order":
            conn = open_vault()
            try:
                self._send(200, place_order(conn, data))
            finally:
                conn.close()
            return
        self._send(404, {"error": "없는 주소"})


def serve(host: str = "127.0.0.1", port: int = 8765, open_browser: bool = False) -> None:
    def loop() -> None:
        while True:
            try:
                maybe_auto_sync(RUNNER)
            except Exception:
                pass
            time.sleep(30)

    threading.Thread(target=loop, daemon=True).start()
    server = ThreadingHTTPServer((host, port), AppHandler)
    url = f"http://{host}:{port}/"
    print(f"배당금고 {url}")
    if open_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()

