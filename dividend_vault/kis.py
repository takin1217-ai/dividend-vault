"""Korea Investment & Securities Open API. Live orders need an explicit phrase."""

from __future__ import annotations

import json
import time
import uuid
from pathlib import Path
from typing import Any

import httpx

from dividend_vault.db import utc_now
from dividend_vault.paths import SECRETS, ensure_dirs

REAL = "https://openapi.koreainvestment.com:9443"
PAPER = "https://openapivts.koreainvestment.com:29443"
ACCOUNTS_PATH = SECRETS / "kis.json"
TOKEN_PATH = SECRETS / "kis-tokens.json"

# Official sample order exchanges and the 2026 TR map.
# US paper sell is VTTT1001U. Vietnam order exchanges are VNSE (HOSE) and HASE (HNX).
ORDER_TR = {
    ("US", "buy"): ("TTTT1002U", "VTTT1002U"),
    ("US", "sell"): ("TTTT1006U", "VTTT1001U"),
    ("HK", "buy"): ("TTTS1002U", "VTTS1002U"),
    ("HK", "sell"): ("TTTS1001U", "VTTS1001U"),
    ("CN_SS", "buy"): ("TTTS0202U", "VTTS0202U"),
    ("CN_SS", "sell"): ("TTTS1005U", "VTTS1005U"),
    ("CN_SZ", "buy"): ("TTTS0305U", "VTTS0305U"),
    ("CN_SZ", "sell"): ("TTTS0304U", "VTTS0304U"),
    ("JP", "buy"): ("TTTS0308U", "VTTS0308U"),
    ("JP", "sell"): ("TTTS0307U", "VTTS0307U"),
    ("VN", "buy"): ("TTTS0311U", "VTTS0311U"),
    ("VN", "sell"): ("TTTS0310U", "VTTS0310U"),
    ("KR", "buy"): ("TTTC0802U", "VTTC0802U"),
    ("KR", "sell"): ("TTTC0801U", "VTTC0801U"),
}

CONFIRM = {"paper": "모의주문확인", "live": "실전주문확인"}


class KisError(RuntimeError):
    pass


def _read(path: Path, fallback: Any) -> Any:
    if not path.exists():
        return fallback
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, payload: Any) -> None:
    ensure_dirs()
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def list_accounts() -> list[dict[str, Any]]:
    return list(_read(ACCOUNTS_PATH, {"accounts": []}).get("accounts") or [])


def public_accounts() -> list[dict[str, Any]]:
    view = []
    for account in list_accounts():
        number = str(account.get("account_no") or "")
        view.append(
            {
                "id": account["id"],
                "alias": account.get("alias"),
                "mode": account.get("mode"),
                "account_masked": ("*" * max(0, len(number) - 4)) + number[-4:],
                "product_code": account.get("product_code") or "01",
                "created_at": account.get("created_at"),
            }
        )
    return view


def add_account(payload: dict[str, Any]) -> dict[str, Any]:
    mode = payload.get("mode")
    if mode not in ("paper", "live"):
        raise KisError("mode는 paper 또는 live 입니다.")
    app_key = str(payload.get("app_key") or "").strip()
    app_secret = str(payload.get("app_secret") or "").strip()
    account_no = str(payload.get("account_no") or "").strip()
    if not app_key or not app_secret or len(account_no) < 8:
        raise KisError("앱키, 앱시크릿, 계좌번호 8자리를 입력하세요.")
    accounts = list_accounts()
    row = {
        "id": uuid.uuid4().hex,
        "alias": str(payload.get("alias") or ("모의" if mode == "paper" else "실전")),
        "mode": mode,
        "app_key": app_key,
        "app_secret": app_secret,
        "account_no": account_no[:8],
        "product_code": str(payload.get("product_code") or "01")[:2],
        "created_at": utc_now(),
    }
    accounts.append(row)
    _write(ACCOUNTS_PATH, {"accounts": accounts})
    return public_accounts()[-1]


def delete_account(account_id: str) -> None:
    accounts = [row for row in list_accounts() if row["id"] != account_id]
    _write(ACCOUNTS_PATH, {"accounts": accounts})


def _account(account_id: str) -> dict[str, Any]:
    for row in list_accounts():
        if row["id"] == account_id:
            return row
    raise KisError("계좌를 찾지 못했습니다.")


def _base(mode: str) -> str:
    return PAPER if mode == "paper" else REAL


def _token(account: dict[str, Any]) -> str:
    cache = _read(TOKEN_PATH, {})
    slot = cache.get(account["id"]) or {}
    if slot.get("access_token") and float(slot.get("expires_at") or 0) > time.time() + 60:
        return slot["access_token"]
    response = httpx.post(
        _base(account["mode"]) + "/oauth2/tokenP",
        json={
            "grant_type": "client_credentials",
            "appkey": account["app_key"],
            "appsecret": account["app_secret"],
        },
        timeout=30,
    )
    body = response.json()
    token = body.get("access_token")
    if response.status_code != 200 or not token:
        raise KisError(body.get("error_description") or body.get("msg1") or "토큰 발급 실패")
    cache[account["id"]] = {
        "access_token": token,
        "expires_at": time.time() + int(body.get("expires_in") or 3600),
    }
    _write(TOKEN_PATH, cache)
    return token


def _headers(account: dict[str, Any], tr_id: str, hashkey: str | None = None) -> dict[str, str]:
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {_token(account)}",
        "appkey": account["app_key"],
        "appsecret": account["app_secret"],
        "tr_id": tr_id,
        "custtype": "P",
    }
    if hashkey:
        headers["hashkey"] = hashkey
    return headers


def _hashkey(account: dict[str, Any], body: dict[str, Any]) -> str:
    response = httpx.post(
        _base(account["mode"]) + "/uapi/hashkey",
        headers={"appkey": account["app_key"], "appsecret": account["app_secret"], "content-type": "application/json"},
        json=body,
        timeout=30,
    )
    payload = response.json()
    value = payload.get("HASH")
    if not value:
        raise KisError(payload.get("msg1") or "해시키 발급 실패")
    return value


def _request(account: dict[str, Any], method: str, path: str, tr_id: str, params: dict | None = None, body: dict | None = None) -> dict[str, Any]:
    hashkey = _hashkey(account, body) if body is not None else None
    response = httpx.request(
        method,
        _base(account["mode"]) + path,
        headers=_headers(account, tr_id, hashkey),
        params=params,
        json=body,
        timeout=30,
    )
    try:
        payload = response.json()
    except json.JSONDecodeError as exc:
        raise KisError(f"응답 해석 실패 HTTP {response.status_code}") from exc
    if str(payload.get("rt_cd", "0")) not in {"0", "None"}:
        raise KisError(payload.get("msg1") or payload.get("msg_cd") or "한국투자증권 오류")
    return payload


def ping(account_id: str) -> dict[str, Any]:
    account = _account(account_id)
    _token(account)
    if account["mode"] == "paper":
        host = "모의투자"
    else:
        host = "실전투자"
    return {"ok": True, "alias": account["alias"], "host": host, "message": "접근 토큰을 발급했습니다. 주문은 아직 내지 않았습니다."}


def _board(market: str, symbol: str, exchange: str | None) -> tuple[str, str, str]:
    code = symbol.split(".")[0]
    if market == "GB":
        raise KisError("한국투자증권 해외주식 주문에 영국 거래소 코드가 없습니다. 영국은 데이터와 가상모의만 사용하세요.")
    if market == "KR":
        return "KR", code, ""
    if market == "HK":
        return "HK", code.zfill(4) if len(code) <= 4 else code, "SEHK"
    if market == "JP":
        return "JP", code, "TKSE"
    if market == "CN":
        group = "CN_SS" if symbol.endswith(".SS") else "CN_SZ"
        ovrs = "SHAA" if group == "CN_SS" else "SZAA"
        return group, code, ovrs
    if market == "VN":
        ovrs = "HASE" if (exchange or "").upper() == "HNX" else "VNSE"
        return "VN", code, ovrs
    board = (exchange or "").upper()
    if board in {"NYQ", "NYS", "NYSE"}:
        return "US", code, "NYSE"
    if board in {"ASE", "AMX", "PCX", "AMS"}:
        return "US", code, "AMEX"
    return "US", code, "NASD"


def _price_text(market: str, price: float) -> str:
    if market in {"KR", "JP", "VN"}:
        return str(int(round(price)))
    digits = 3 if market == "HK" else 2
    return f"{price:.{digits}f}"


def place_order(conn, payload: dict[str, Any]) -> dict[str, Any]:
    account = _account(str(payload.get("account_id")))
    mode = account["mode"]
    confirm = str(payload.get("confirm") or "")
    if confirm != CONFIRM[mode]:
        raise KisError(f"{'실전' if mode == 'live' else '모의'} 주문은 확인 문구 '{CONFIRM[mode]}'가 있어야 전송됩니다.")
    market = str(payload.get("market") or "")
    symbol = str(payload.get("symbol") or "")
    side = str(payload.get("side") or "")
    if side not in {"buy", "sell"}:
        raise KisError("side는 buy 또는 sell 입니다.")
    qty = float(payload.get("qty") or 0)
    price = float(payload.get("price") or 0)
    if qty < 1 or abs(qty - round(qty)) > 1e-6:
        raise KisError("한국투자증권 주문 수량은 1주 이상의 정수입니다. 소수점 비중은 가상모의에서만 사용합니다.")
    if price <= 0:
        raise KisError("지정가 가격이 필요합니다. 모의투자는 지정가만 허용되는 시장이 있습니다.")
    row = conn.execute("SELECT exchange FROM symbols WHERE symbol=?", (symbol,)).fetchone()
    exchange = row["exchange"] if row else payload.get("exchange")
    group, product, ovrs = _board(market, symbol, exchange)
    real_tr, paper_tr = ORDER_TR[(group, side)]
    tr_id = paper_tr if mode == "paper" else real_tr
    if market == "KR":
        path = "/uapi/domestic-stock/v1/trading/order-cash"
        body = {
            "CANO": account["account_no"],
            "ACNT_PRDT_CD": account["product_code"],
            "PDNO": product,
            "ORD_DVSN": "00",
            "ORD_QTY": str(int(round(qty))),
            "ORD_UNPR": _price_text(market, price),
        }
    else:
        path = "/uapi/overseas-stock/v1/trading/order"
        body = {
            "CANO": account["account_no"],
            "ACNT_PRDT_CD": account["product_code"],
            "OVRS_EXCG_CD": ovrs,
            "PDNO": product,
            "ORD_QTY": str(int(round(qty))),
            "OVRS_ORD_UNPR": _price_text(market, price),
            "CTAC_TLNO": "",
            "MGCO_APTM_ODNO": "",
            "SLL_TYPE": "",
            "ORD_SVR_DVSN_CD": "0",
            "ORD_DVSN": "00",
        }
    response = _request(account, "POST", path, tr_id, body=body)
    conn.execute(
        """
        INSERT INTO kis_orders(
          created_at, account_alias, mode, market, symbol, side, qty, price, status, request_json, response_json
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """,
        (
            utc_now(),
            account["alias"],
            mode,
            market,
            symbol,
            side,
            qty,
            price,
            "submitted",
            json.dumps({"tr_id": tr_id, "path": path}, ensure_ascii=False),
            json.dumps(response, ensure_ascii=False)[:4000],
        ),
    )
    conn.commit()
    return {"ok": True, "tr_id": tr_id, "message": response.get("msg1") or "주문을 전송했습니다.", "output": response.get("output")}
