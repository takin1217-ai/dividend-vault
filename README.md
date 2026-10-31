# 배당금고

미국·중국·홍콩·일본·베트남 고배당 일봉을 먼저 모으고, 한국과 영국을 같은 금고에서 켜서 학습·과거 모의·가상 모의·한국투자증권 주문까지 잇는 연구 도구입니다. 저장소는 [takin1217-ai/dividend-vault](https://github.com/takin1217-ai/dividend-vault)입니다. 안드로이드 앱 소스는 `android/`에 있고, 구글 플레이에 올릴 때 필요한 문구와 개인정보 처리방침은 `playstore/`와 `docs/`에 있습니다.

수익을 보장하지 않습니다. 과거 성과는 미래 수익이 아닙니다.

## 데이터

- 기본 최소 보유는 미국 1000, 중국 250, 홍콩 150, 일본 250, 베트남 50입니다. 수는 바꿀 수 있고, 목표 보유는 그 최소의 1배에서 5배 사이로만 저장됩니다.
- 한국 150, 영국 100은 예시 기본값이며 기본적으로 꺼져 있습니다.
- ETP는 주식 수에 넣지 않습니다. 포함할 때만 따로 붙고, 레버리지 상품은 기본으로 빼니다.
- 일봉은 `data/vault.sqlite`에만 있습니다. 같은 날짜의 값이 바뀌면 이전 값은 `bar_revisions`에 남고, 봉 자체는 지우지 않습니다. 날짜별 백업은 `data/backups/`에 최대 14개입니다.
- 시세는 이용 시점에 Yahoo Finance에서 받고, 이 저장소에 가격 데이터베이스를 올리지 않습니다.

## 연구 단말

```powershell
pip install -r requirements.txt
python -m dividend_vault sync --markets US,CN,HK,JP,VN
python -m dividend_vault serve --open
```

연구 화면은 `http://127.0.0.1:8765/` 입니다. 조건을 저장한 뒤 일봉을 이어서 받고, 학습 기울기·배당 기울기·모멘텀·저변동·동일비중을 비교합니다. 학습은 리밸런스 이전 데이터로만 릿지 회귀를 맞춥니다.

매일 07:20과 17:10에 갱신하려면:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install_daily_task.ps1
```

연구 화면을 켜 둔 경우에도 같은 시각에 한 번만 갱신합니다. 동기화는 잠금 파일로 겹치지 않습니다.

## 한국투자증권

`config/kis.example.json`을 `secrets/kis.json`으로 복사하지 않아도 됩니다. 연구 화면에서 계좌를 추가하면 `secrets/`에만 저장됩니다. 모의 키와 실전 키는 서로 다릅니다.

- 모의 주문 확인 문구: `모의주문확인`
- 실전 주문 확인 문구: `실전주문확인`
- 영국 거래소는 주문 API 코드가 없어 데이터와 가상 모의만 됩니다.
- 실전·모의 모두 지정가이며, 수량은 1주 이상 정수입니다. 소수점 비중은 가상 계좌에서만 씁니다.

## 플레이 앱

`android/`는 `com.takin1217.dividendvault`, targetSdk 35입니다. 폰에서는 가져온 금고의 일봉을 매일 이어 받고, 배당 기울기 과거 모의와 가상 비중, 확인 문구가 있는 한국투자증권 주문을 실행합니다. 대량 10년 수집과 학습 기울기 릿지 회귀의 기준 구현은 이 연구 단말입니다.

서명 없는 디버그 APK를 Actions에서 만들려면 `scripts/github-ci.yml`을 `.github/workflows/ci.yml`로 옮깁니다. 이 계정 토큰에는 `workflow` 범위가 없어 그 경로로는 올리지 못했습니다. 플레이에 올릴 AAB는 본인 키스토어로 서명해야 합니다. 절차는 `playstore/listing-ko.md`에 있습니다.

```powershell
keytool -genkeypair -v -keystore dividend-vault.jks -alias dividend -keyalg RSA -keysize 2048 -validity 10000
```

키스토어는 저장소에 넣지 마세요.

## 테스트

```powershell
python -m unittest tests.test_core -v
```
