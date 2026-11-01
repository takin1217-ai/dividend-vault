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
MARKET_CODES = ("US", "CN", "HK", "JP", "VN", "KR", "GB")
MARKET_NAMES = {"US": "미국", "CN": "중국", "HK": "홍콩", "JP": "일본", "VN": "베트남", "KR": "한국", "GB": "영국"}


class KisError(RuntimeError):
    pass


def _read(path: Path, fallback: Any) -> Any:
    if not path.exists():
        return fallback
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, payload: Any) -> None:
    ensure_dirs()
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _load() -> dict[str, Any]:
    data = _read(ACCOUNTS_PATH, {"accounts": [], "selected": {}})
    if not isinstance(data, dict):
        data = {"accounts": [], "selected": {}}
    data.setdefault("accounts", [])
    data.setdefault("selected", {})
    return data


def _save(data: dict[str, Any]) -> None:
    _write(ACCOUNTS_PATH, data)


def list_accounts() -> list[dict[str, Any]]:
    return list(_load().get("accounts") or [])


def _markets_of(account: dict[str, Any]) -> list[str]:
    raw = account.get("markets")
    if not raw:
        return list(MARKET_CODES)
    return [code for code in raw if code in MARKET_CODES]


def _clean_markets(value: Any) -> list[str]:
    if isinstance(value, str):
        value = [part.strip().upper() for part in value.split(",")]
    codes = []
    for item in value or []:
        code = str(item).strip().upper()
        if code in MARKET_CODES and code not in codes:
            codes.append(code)
    return codes


def public_accounts() -> list[dict[str, Any]]:
    data = _load()
    selected = data.get("selected") or {}
    view = []
    for account in data.get("accounts") or []:
        number = str(account.get("account_no") or "")
        markets = _markets_of(account)
        view.append(
            {
                "id": account["id"],
                "alias": account.get("alias"),
                "mode": account.get("mode"),
                "account_masked": ("*" * max(0, len(number) - 4)) + number[-4:],
                "product_code": account.get("product_code") or "01",
                "markets": markets,
                "market_names": [MARKET_NAMES[code] for code in markets],
                "us_exchange": account.get("us_exchange") or "NASD",
                "vn_exchange": account.get("vn_exchange") or "VNSE",
                "selected_markets": [code for code, account_id in selected.items() if account_id == account["id"]],
                "created_at": account.get("created_at"),
            }
        )
    return view


def selection_view() -> dict[str, Any]:
    data = _load()
    by_id = {account["id"]: account for account in data.get("accounts") or []}
    view = {}
    for code in MARKET_CODES:
        account = by_id.get((data.get("selected") or {}).get(code))
        view[code] = None if account is None else {
            "id": account["id"],
            "alias": account.get("alias"),
            "mode": account.get("mode"),
            "name": MARKET_NAMES[code],
        }
    return view


def add_account(payload: dict[str, Any]) -> dict[str, Any]:
    mode = payload.get("mode")
    if mode not in ("paper", "live"):
        raise KisError("mode는 paper 또는 live 입니다.")
    app_key = str(payload.get("app_key") or "").strip()
    app_secret = str(payload.get("app_secret") or "").strip()
    account_no = str(payload.get("account_no") or "").strip()
    markets = _clean_markets(payload.get("markets"))
    if not app_key or not app_secret or len(account_no) < 8:
        raise KisError("앱키, 앱시크릿, 계좌번호 8자리를 입력하세요.")
    if not markets:
        raise KisError("나라는 최소 1개를 고르세요. 한 계좌에 미국 중국 홍콩 일본 베트남 한국 영국을 모두 넣을 수 있습니다.")
    us_exchange = str(payload.get("us_exchange") or "NASD").upper()
    if us_exchange not in {"NASD", "NYSE", "AMEX"}:
        raise KisError("미국 거래소는 NASD, NYSE, AMEX 중 하나입니다.")
    vn_exchange = str(payload.get("vn_exchange") or "VNSE").upper()
    if vn_exchange not in {"VNSE", "HASE"}:
        raise KisError("베트남 거래소는 HOSE(VNSE) 또는 HNX(HASE) 입니다.")
    data = _load()
    row = {
        "id": uuid.uuid4().hex,
        "alias": str(payload.get("alias") or ("모의" if mode == "paper" else "실전")),
        "mode": mode,
        "app_key": app_key,
        "app_secret": app_secret,
        "account_no": account_no[:8],
        "product_code": str(payload.get("product_code") or "01")[:2] or "01",
        "markets": markets,
        "us_exchange": us_exchange,
        "vn_exchange": vn_exchange,
        "created_at": utc_now(),
    }
    data["accounts"].append(row)
    selected = data["selected"]
    for code in markets:
        if payload.get("select") or not selected.get(code):
            selected[code] = row["id"]
    _save(data)
    return public_accounts()[-1]


def select_account(account_id: str, market: str) -> dict[str, Any]:
    code = str(market or "").upper()
    if code not in MARKET_CODES:
        raise KisError("나라는 US CN HK JP VN KR GB 중 하나입니다.")
    account = _account(account_id)
    if code not in _markets_of(account):
        raise KisError(f"이 계좌에는 {MARKET_NAMES[code]}이 연결되어 있지 않습니다.")
    data = _load()
    data["selected"][code] = account_id
    _save(data)
    return selection_view()


def update_account(payload: dict[str, Any]) -> dict[str, Any]:
    account_id = str(payload.get("id") or payload.get("account_id") or "")
    data = _load()
    row = next((item for item in data["accounts"] if item["id"] == account_id), None)
    if row is None:
        raise KisError("계좌를 찾지 못했습니다.")
    alias = str(payload.get("alias") or "").strip()
    if alias:
        row["alias"] = alias
    mode = payload.get("mode")
    if mode in ("paper", "live"):
        row["mode"] = mode
    app_key = str(payload.get("app_key") or "").strip()
    app_secret = str(payload.get("app_secret") or "").strip()
    if app_key and app_key != row.get("app_key") and not app_secret:
        raise KisError("앱키를 바꾸면 앱시크릿도 입력하세요.")
    if app_key:
        row["app_key"] = app_key
    if app_secret:
        row["app_secret"] = app_secret
    account_no = str(payload.get("account_no") or "").strip()
    if account_no:
        if len(account_no) < 8:
            raise KisError("계좌번호 8자리를 입력하세요.")
        row["account_no"] = account_no[:8]
    if payload.get("product_code"):
        row["product_code"] = str(payload.get("product_code") or "01")[:2] or "01"
    if payload.get("markets") is not None:
        markets = _clean_markets(payload.get("markets"))
        if not markets:
            raise KisError("나라는 최소 1개를 고르세요. 한 계좌에 7개국을 모두 넣을 수 있습니다.")
        row["markets"] = markets
        data["selected"] = {
            code: chosen
            for code, chosen in (data.get("selected") or {}).items()
            if chosen != account_id or code in markets
        }
    if payload.get("us_exchange"):
        us_exchange = str(payload.get("us_exchange")).upper()
        if us_exchange not in {"NASD", "NYSE", "AMEX"}:
            raise KisError("미국 거래소는 NASD, NYSE, AMEX 중 하나입니다.")
        row["us_exchange"] = us_exchange
    if payload.get("vn_exchange"):
        vn_exchange = str(payload.get("vn_exchange")).upper()
        if vn_exchange not in {"VNSE", "HASE"}:
            raise KisError("베트남 거래소는 HOSE(VNSE) 또는 HNX(HASE) 입니다.")
        row["vn_exchange"] = vn_exchange
    if payload.get("select"):
        for code in _markets_of(row):
            data["selected"][code] = account_id
    _save(data)
    return next(item for item in public_accounts() if item["id"] == account_id)


def clear_selection(account_id: str, market: str) -> dict[str, Any]:
    code = str(market or "").upper()
    if code not in MARKET_CODES:
        raise KisError("나라는 US CN HK JP VN KR GB 중 하나입니다.")
    data = _load()
    if (data.get("selected") or {}).get(code) == account_id:
        data["selected"].pop(code, None)
        _save(data)
    return selection_view()


def delete_account(account_id: str) -> None:
    data = _load()
    data["accounts"] = [row for row in data["accounts"] if row["id"] != account_id]
    data["selected"] = {code: chosen for code, chosen in data["selected"].items() if chosen != account_id}
    _save(data)


def _account(account_id: str) -> dict[str, Any]:
    for row in list_accounts():
        if row["id"] == account_id:
            return row
    raise KisError("계좌를 찾지 못했습니다.")


def _base(mode: str) -> str:
    return PAPER if mode == "paper" else REAL


def _token_key(account: dict[str, Any]) -> str:
    return f"{account['mode']}|{account['app_key']}"


def fresh_token(slot: dict[str, Any] | None, now: float) -> str | None:
    if not slot:
        return None
    token = slot.get("access_token")
    if token and float(slot.get("expires_at") or 0) > now + 60:
        return str(token)
    return None


def _token(account: dict[str, Any]) -> str:
    cache = _read(TOKEN_PATH, {})
    now = time.time()
    key = _token_key(account)
    slot = cache.get(key) if isinstance(cache.get(key), dict) else None
    token = fresh_token(slot, now)
    if token is None:
        legacy = cache.get(account["id"]) if isinstance(cache.get(account["id"]), dict) else None
        token = fresh_token(legacy, now)
        slot = legacy
    if token is None:
        for item in cache.values():
            if not isinstance(item, dict):
                continue
            if item.get("app_key") == account["app_key"] and item.get("mode", account["mode"]) == account["mode"]:
                token = fresh_token(item, now)
                if token:
                    slot = item
                    break
    if token:
        cache[key] = {
            "access_token": token,
            "issued_at": (slot or {}).get("issued_at") or now,
            "expires_at": (slot or {}).get("expires_at"),
            "app_key": account["app_key"],
            "mode": account["mode"],
        }
        _write(TOKEN_PATH, cache)
        return token
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
    cache[key] = {
        "access_token": token,
        "issued_at": now,
        "expires_at": now + int(body.get("expires_in") or 86400),
        "app_key": account["app_key"],
        "mode": account["mode"],
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
        ovrs = "HASE" if (exchange or "").upper() in {"HNX", "HASE"} else "VNSE"
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
    from dividend_vault.readiness import live_block_reason, loss_sell_reason

    account = _account(str(payload.get("account_id")))
    mode = account["mode"]
    confirm = str(payload.get("confirm") or "")
    if confirm != CONFIRM[mode]:
        raise KisError(f"{'실전' if mode == 'live' else '모의'} 주문은 확인 문구 '{CONFIRM[mode]}'가 있어야 전송됩니다.")
    if mode == "live":
        blocked = live_block_reason(conn)
        if blocked:
            raise KisError(blocked)
    market = str(payload.get("market") or "").upper()
    if market not in _markets_of(account):
        raise KisError("이 계좌에는 그 나라가 연결되어 있지 않습니다.")
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
    if side == "sell":
        blocked = loss_sell_reason(conn, account, market, symbol, qty, price)
        if blocked:
            raise KisError(blocked)
    row = conn.execute("SELECT exchange FROM symbols WHERE symbol=?", (symbol,)).fetchone()
    exchange = (row["exchange"] if row else None) or payload.get("exchange")
    if market == "US" and not exchange:
        exchange = account.get("us_exchange") or "NASD"
    if market == "VN" and not exchange:
        exchange = account.get("vn_exchange") or "VNSE"
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
