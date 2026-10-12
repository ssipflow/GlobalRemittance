# API 문서

## 공통 계약

- 기본 URL 예시: `http://localhost:8080`. 본문이 있는 요청은 `Content-Type: application/json`이다.
- 모든 성공·오류 응답은 JSON이다. 성공은 HTTP 200, `resultCode: 200`, `resultMsg: "OK"`다.
- 송금 API(`/transfer/*`)는 `Authorization: Bearer <token>`이 필요하다. 회원가입·로그인은 인증 없이 호출한다.
- 견적·이력 응답의 날짜 문자열은 KST `yyyy-MM-dd HH:mm:ss`다. 금액과 환율은 JSON number이며 quoteId는 문자열이다.
- JWT는 발급 후 30분 유효하고 id, userId, name, expireTime과 표준 인증 클레임을 포함한다. 토큰 내부 expireTime은 KST ISO offset 형식이다.

## 호출 순서 예시

```sh
# 1. 가입 (최초 1회)
curl -X POST http://localhost:8080/user/signup -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password","name":"테스트 사용자","idType":"REG_NO","idValue":"example-identity"}'

# 2. 로그인 → 응답의 token 사용
curl -X POST http://localhost:8080/user/login -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password"}'

# 3. 견적 → 응답의 quoteId 사용 (10분 안에 접수)
curl -X POST http://localhost:8080/transfer/quote -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"amount":400000,"targetCurrency":"USD"}'

# 4. 접수
curl -X POST http://localhost:8080/transfer/request -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"quoteId":"1"}'

# 5. 이력 (한 페이지)
curl "http://localhost:8080/transfer/list?size=20" -H "Authorization: Bearer $TOKEN"
```

## POST /user/signup

| 필드 | 형식·제약 |
| --- | --- |
| userId | 이메일, 공백 불가, 최대 255자, 유일함 |
| password | 공백 불가, 최대 72자 및 UTF-8 72바이트 |
| name | 공백 불가, 최대 128자 |
| idType | REG_NO(개인) 또는 BUSINESS_NO(법인) |
| idValue | 공백 불가, 최대 64자 및 UTF-8 64바이트 |

```json
{"userId":"demo@example.com","password":"example-password","name":"테스트 사용자","idType":"REG_NO","idValue":"example-identity"}
```

```json
{"resultCode":200,"resultMsg":"OK"}
```

동일 이메일은 HTTP 409 DUPLICATE_USER다. 비밀번호와 식별번호 원문은 응답에 포함하지 않는다.

## POST /user/login

userId와 password는 회원가입과 같은 형식 제약을 적용한다.

```json
{"userId":"demo@example.com","password":"example-password"}
```

```json
{"resultCode":200,"resultMsg":"OK","token":"<JWT>"}
```

계정 없음·비밀번호 불일치는 HTTP 401 UNAUTHORIZED다. 둘을 구분하지 않는다.

## POST /transfer/quote

| 필드 | 형식·제약 |
| --- | --- |
| amount | KRW 금액, 양수, 정수부 최대 10자리·소수부 최대 12자리 |
| targetCurrency | USD 또는 JPY |

```json
{"amount":400000,"targetCurrency":"USD"}
```

```json
{"resultCode":200,"resultMsg":"OK","quote":{"quoteId":"1","exchangeRate":1300.000000000000,"expireTime":"2026-10-11 15:10:00","targetAmount":306.31}}
```

예시 환율 1300에서 수수료는 1800원, 순송금액은 398200원이다. 만료시각은 생성시각 +10분이다. quoteId와 시각은 예시 값이다.

소수 입력은 HALF_UP으로 KRW 정수화한 뒤 정책을 적용한다. 입력이 양수가 아니거나 수수료 차감 후 음수이면 NEGATIVE_NUMBER, 저장 가능한 DECIMAL 범위를 넘으면 INVALID_REQUEST다.

## POST /transfer/request

quoteId는 양의 숫자 문자열이며 실제 INT 식별자 범위를 검증한다.

```json
{"quoteId":"1"}
```

```json
{"resultCode":200,"resultMsg":"OK"}
```

현재 사용자 소유이며 아직 접수하지 않은 유효 견적만 접수한다. 일일 순송금 USD 한도는 REG_NO 1000, BUSINESS_NO 5000이고 동일한 금액은 허용한다.

오류 검사 순서는 인증 → 존재·소유권 → 중복 → 만료 → 한도다. 같은 사용자의 다른 접수가 잠금을 오래 쥐면 503 LOCK_TIMEOUT이 올 수 있으며, 이때는 잠시 후 재시도할 수 있다. 접수에 멱등성 키가 없으므로 응답을 받지 못한 재시도는 이력으로 접수 여부를 먼저 확인한다.

## GET /transfer/list

본문 없이 호출한다. 접수가 끝난 항목만 접수시각·ID 내림차순(최신순)으로 한 페이지씩 반환한다. 오늘은 KST 날짜 기준이다.

### 쿼리 파라미터

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| size | 아니오 | 한 페이지 항목 수. 기본 50, 숫자만 허용(부호 표기 불가), 허용 1~100 |
| cursor | 아니오 | 이전 응답의 nextCursor. 없으면 첫 페이지 |

### 응답

```json
{
  "resultCode":200,
  "resultMsg":"OK",
  "userId":"demo@example.com",
  "name":"테스트 사용자",
  "todayTransferCount":1,
  "todayTransferUsdAmount":306.31,
  "history":[{
    "sourceAmount":400000,
    "fee":1800,
    "usdExchangeRate":1300.000000000000,
    "usdAmount":306.31,
    "targetCurrency":"USD",
    "exchangeRate":1300.000000000000,
    "targetAmount":306.31,
    "requestedDate":"2026-10-11 15:05:00"
  }],
  "nextCursor":null
}
```

| 필드 | 의미 |
| --- | --- |
| todayTransferCount / todayTransferUsdAmount | KST 오늘 접수한 전체 건수와 USD 합계. 요청한 페이지와 관계없이 같다. 오늘 거래가 없으면 0, 0.00 |
| history[].sourceAmount / fee | 입력 원화 금액 / 고정·비율 수수료의 합 |
| history[].exchangeRate / targetAmount | 수취 통화 환율 / 수취 금액 |
| history[].usdExchangeRate / usdAmount | 일일 한도 계산용 USD 환율 / 수수료 차감 후 USD 금액 |
| history[].requestedDate | 접수 시각(KST) |
| nextCursor | 다음 페이지가 있을 때만 값이 있고 마지막 페이지에서는 null |

미접수 견적은 history와 오늘 집계에서 제외한다.

### 다음 페이지 읽기

응답의 `nextCursor`를 그대로 `cursor`로 전달한다.

```sh
curl "http://localhost:8080/transfer/list?size=20&cursor=<nextCursor>" -H "Authorization: Bearer $TOKEN"
```

커서는 불투명 문자열이다. 클라이언트는 내용을 해석하거나 만들지 않는다. 조회는 항상 인증된 사용자의 거래로 제한되므로 다른 사용자의 커서를 써도 타인의 데이터는 보이지 않는다. 잘못된 cursor, 숫자가 아니거나 1~100을 벗어난 size는 400 INVALID_REQUEST다.

**호환 변경**: 파라미터가 없는 기존 요청은 전체 이력이 아니라 최신 50건(첫 페이지)을 받는다. 더 오래된 항목은 nextCursor로 이어서 읽는다.

## 오류 계약

모든 오류는 같은 형식이다. `resultCode`는 HTTP 상태와 같다.

```json
{"resultCode":400,"resultMsg":"QUOTE_EXPRIED"}
```

| HTTP | resultMsg | 발생하는 곳 | 의미 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | 모든 입력 | 입력 형식·통화·자릿수·식별자 범위 오류, 잘못된 cursor·size |
| 400 | NEGATIVE_NUMBER | 견적 | 양수가 아닌 입력 또는 수수료 차감 후 음수 |
| 400 | QUOTE_EXPRIED | 접수 | 만료시각 이상에서 접수 요청. 원 요구사항의 철자를 유지 |
| 400 | LIMIT_EXCESS | 접수 | KST 하루 순송금 USD 합계 초과 |
| 401 | UNAUTHORIZED | 로그인, 송금 API | 로그인 실패, 누락·잘못된·만료 토큰 |
| 403 | FORBIDDEN | 접수 | 타인 견적 접수 또는 접근 거부 |
| 404 | QUOTE_NOT_FOUND | 접수 | 견적 없음 |
| 404 | NOT_FOUND | 모든 경로 | 존재하지 않는 경로 |
| 405 | METHOD_NOT_ALLOWED | 모든 경로 | 허용하지 않은 HTTP 메서드 |
| 406 | NOT_ACCEPTABLE | 모든 경로 | 지원하지 않는 응답 형식 요청 |
| 409 | DUPLICATE_USER | 회원가입 | 중복 회원 |
| 409 | ALREADY_REQUESTED | 접수 | 이미 접수한 견적 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 본문 요청 | 지원하지 않는 요청 Content-Type |
| 500 | UNKNOWN_ERROR | 모든 곳 | 내부 오류. 원시 예외와 개인정보는 노출하지 않음 |
| 503 | LOCK_TIMEOUT | 접수 | 같은 사용자의 다른 접수가 잠금을 오래 쥐어 대기 한도(`remittance.lock-timeout-ms`) 초과. 재시도 가능 |
