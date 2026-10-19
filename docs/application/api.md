# API 문서

## 공통 계약

기본 URL 예시는 `http://localhost:8080`이다. 본문을 보내는 요청은 `Content-Type: application/json`을 지정한다. 회원가입과 로그인은 인증 없이 호출하고 송금 API는 `Authorization: Bearer <token>`을 사용한다.

성공·오류 응답은 모두 JSON이다. 성공은 HTTP 200과 `resultCode: 200`, `resultMsg: "OK"`를 반환한다. 오류에서는 resultCode가 HTTP 상태와 같고 resultMsg에는 고정 오류 코드를 넣는다.

견적·이력의 시각은 KST `yyyy-MM-dd HH:mm:ss`로 표시한다. 금액·환율은 JSON number, quoteId는 문자열이다. 아래 ID·시각·토큰은 응답 형태를 설명하기 위한 예시다.

JWT는 발급 후 30분 유효하다. id, userId, name, expireTime과 표준 인증 클레임을 포함하며, 토큰 내부 expireTime은 KST ISO offset 형식이다.

## Swagger UI와 OpenAPI 명세

서버를 실행하면 `/swagger-ui.html`에서 API를 읽고 직접 호출할 수 있고, 기계가 읽는 OpenAPI 3 명세는 `/v3/api-docs`에서 JSON으로 받는다. 명세는 컨트롤러와 요청 DTO의 어노테이션에서 생성되며 아래 계약(필드 제약, 주요 오류 코드, `Idempotency-Key`·`size`·`cursor`)을 요약해서 담는다. 두 경로는 토큰 없이 열린다.

1. `POST /user/signup`, `POST /user/login`을 Swagger UI에서 호출해 token을 얻는다.
2. 우측 상단 Authorize에 token 값만 입력한다(`Bearer ` 접두사는 UI가 붙인다).
3. `/transfer/*`를 호출한다.

공통 오류(404, 405, 406, 415, 500)는 오퍼레이션마다 반복하지 않고 아래 오류 계약에서 설명한다. `REMITTANCE_API_DOCS_ENABLED=false`이면 UI와 명세 모두 일반 경로처럼 404 NOT_FOUND JSON을 반환한다. 이 명세는 코드에서 생성한 설명이며 계약의 기준은 이 문서와 테스트다.

## 호출 순서

가입 후 로그인 응답의 token을 TOKEN에 넣고, 견적 응답의 quoteId를 접수 본문에 사용한다. 아래 접수 예시의 `"1"`은 실제 받은 ID로 바꾼다.

```sh
# 가입 (최초 1회)
curl -X POST http://localhost:8080/user/signup -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password","name":"테스트 사용자","idType":"REG_NO","idValue":"example-identity"}'

# 로그인
curl -X POST http://localhost:8080/user/login -H 'Content-Type: application/json' \
  -d '{"userId":"demo@example.com","password":"example-password"}'

# 로그인 응답의 token을 복사한 뒤 견적 요청
TOKEN='<로그인 응답의 token>'
curl -X POST http://localhost:8080/transfer/quote -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"amount":400000,"targetCurrency":"USD"}'

# 견적 응답의 quoteId로 접수
curl -X POST http://localhost:8080/transfer/request -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"quoteId":"1"}'

# 접수 이력 조회
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
{
  "userId": "demo@example.com",
  "password": "example-password",
  "name": "테스트 사용자",
  "idType": "REG_NO",
  "idValue": "example-identity"
}
```

```json
{
  "resultCode": 200,
  "resultMsg": "OK"
}
```

동일 이메일은 HTTP 409 DUPLICATE_USER다. 비밀번호와 식별번호 원문은 응답에 포함하지 않는다.

## POST /user/login

userId는 이메일 형식·최대 255자, password는 공백 불가·최대 72자를 검증한다. 로그인 시에도 UTF-8 72바이트 제한을 적용한다.

```json
{
  "userId": "demo@example.com",
  "password": "example-password"
}
```

```json
{
  "resultCode": 200,
  "resultMsg": "OK",
  "token": "<JWT>"
}
```

계정 없음·비밀번호 불일치는 HTTP 401 UNAUTHORIZED다. 둘을 구분하지 않는다.

## POST /transfer/quote

| 필드 | 형식·제약 |
| --- | --- |
| amount | KRW 금액, 양수, 정수부 최대 10자리·소수부 최대 12자리 |
| targetCurrency | USD 또는 JPY |

```json
{
  "amount": 400000,
  "targetCurrency": "USD"
}
```

```json
{
  "resultCode": 200,
  "resultMsg": "OK",
  "quote": {
    "quoteId": "1",
    "exchangeRate": 1300.0,
    "expireTime": "2026-10-11 15:10:00",
    "targetAmount": 306.31
  }
}
```

예시 환율 1300에서 수수료는 1800원, 순송금액은 398200원이다. 만료시각은 생성시각 +10분이다. quoteId와 시각은 예시 값이다.

환율의 기준 시각이 현재와 허용 지연(기본 60초, `REMITTANCE_RATES_MAX_AGE_SECONDS`로 1~3600)을 초과해 벗어나면(같으면 신선) 견적을 만들지 않고 503 `RATE_STALE`을, 환율 조회가 실패하면 503 `RATE_UNAVAILABLE`을 반환한다. 오래된 환율을 몰래 쓰지 않고 `Retry-After`도 붙이지 않는다. 클라이언트는 잠시 뒤 다시 요청한다. 이미 발급된 견적은 발급 시점 환율로 10분간 고정이므로 환율 장애와 무관하게 접수할 수 있다. 기본 설정(설정 환율)에서는 기준 시각이 항상 현재라 이 오류가 나지 않는다.

소수 입력은 HALF_UP으로 KRW 정수화한 뒤 정책을 적용한다. 입력이 양수가 아니거나 수수료 차감 후 음수이면 NEGATIVE_NUMBER, 저장 가능한 DECIMAL 범위를 넘으면 INVALID_REQUEST다.

수수료 차감 후 순송금액이 0 이상이면 발급한다. 순송금이 1원처럼 작아 수취액·USD 금액이 `0.00`으로 반올림되는 견적도 현재는 발급·접수되며(요구사항이 음수만 거부하고 최소 금액을 정하지 않았다), 그 접수는 일일 한도에 0.00 USD로 합산된다. 최소 금액 규칙은 정책 결정 사항이다. 견적 저장은 사용자 행에 외래키 공유 잠금을 요청하므로, 같은 사용자의 접수가 그 행을 오래 쥐고 있어 대기 한도를 넘기면 견적도 503 `LOCK_TIMEOUT`(`Retry-After: 1`)을 반환한다. 이때 견적은 저장되지 않는다.

## POST /transfer/request

quoteId는 양의 숫자 문자열이며 실제 INT 식별자 범위를 검증한다.

```json
{
  "quoteId": "1"
}
```

```json
{
  "resultCode": 200,
  "resultMsg": "OK"
}
```

현재 사용자 소유이며 아직 접수하지 않은 유효 견적만 접수한다. 일일 순송금 USD 한도는 REG_NO 1000, BUSINESS_NO 5000이고 동일한 금액은 허용한다.

인증 후에는 사용자 잠금을 얻고 견적 존재 → 소유권 → (멱등성 키가 있으면 재현·재사용 판단) → 중복 → 만료 → 한도 순서로 검사한다. 잠금을 얻기 전에 대기 한도를 넘기면 503 LOCK_TIMEOUT을 반환하며 응답에 `Retry-After: 1` 헤더가 붙는다(고정 1초, 이 오류와 DB_UNAVAILABLE에만). 처음 요청에 `Idempotency-Key`를 보냈다면 같은 키로 재시도해도 중복 반영되지 않는다(24시간 안). 키 없이 보낸 요청은 재시도가 이미 접수된 상태(ALREADY_REQUESTED)로 이어질 수 있으니 이력으로 확인한다.

### 멱등성 키 (선택)

응답을 받지 못했거나 503을 받은 클라이언트가 안전하게 재시도하도록 `Idempotency-Key` 헤더를 지원한다. 키는 호출 한 건을 구분하는 값이며 클라이언트가 만든다(UUID 권장).

```sh
curl -X POST http://localhost:8080/transfer/request -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: 6f1c2b7e-0a54-4d1e-9b7a-3c2d8e1f4a90" \
  -d '{"quoteId":"1"}'
```

| 상황 | 응답 |
| --- | --- |
| 키 없음 | 기존과 동일. 이미 접수한 견적은 409 ALREADY_REQUESTED |
| 첫 요청이 성공 | 200. 이 키가 해당 견적에 묶인다 |
| 같은 키·같은 견적으로 재시도, 접수 후 24시간 미만 | 200을 다시 반환하고 아무것도 바꾸지 않는다. 견적이 이미 만료됐어도 같다 |
| 같은 키·같은 견적, 접수 후 24시간 이상 | 409 ALREADY_REQUESTED. 이력으로 접수 여부를 확인한다 |
| 같은 키·다른 견적 | 422 IDEMPOTENCY_KEY_REUSED. 두 번째 견적은 접수되지 않는다 |
| 형식 위반(빈 값, 65자 이상, 영문·숫자·`.`·`_`·`~`·`-` 외 문자) | 400 INVALID_REQUEST |

- 키 범위는 사용자별이다. 다른 사용자가 같은 문자열을 써도 서로 영향이 없다. 키는 `[A-Za-z0-9._~-]{1,64}`다.
- 24시간은 성공 응답 재현 기간이다. 키와 견적의 DB 연결은 삭제되지 않아 기간이 지난 키도 다른 견적에 재사용할 수 없다. 정확히 24시간부터는 같은 견적도 ALREADY_REQUESTED다.
- 재현 요청도 인증이 필요하다. JWT의 30분 유효기간이 지나면 재로그인한 같은 사용자 토큰으로 요청한다.
- 키 없이 성공한 접수에 새 키를 붙이거나, 성공한 견적에 다른 키를 붙이면 ALREADY_REQUESTED다.
- 성공한 접수만 키를 소비한다. LIMIT_EXCESS, QUOTE_EXPRIED, 403, 404, 503 같은 실패는 키를 남기지 않으므로 같은 키로 재시도하면 그 시점의 규칙으로 다시 평가된다. 테스트로 확인한 것은 LIMIT_EXCESS와 QUOTE_EXPRIED이고, 나머지는 성공한 접수만 키를 저장하는 구조에서 따라 나온다.
- 재현되는 것은 성공 응답뿐이다. 실패 응답을 저장해 되돌려 주지는 않는다.
- 타인의 견적에 자기 키를 써도 키 판단 없이 403 FORBIDDEN이다.

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
  "resultCode": 200,
  "resultMsg": "OK",
  "userId": "demo@example.com",
  "name": "테스트 사용자",
  "todayTransferCount": 1,
  "todayTransferUsdAmount": 306.31,
  "history": [
    {
      "sourceAmount": 400000,
      "fee": 1800,
      "usdExchangeRate": 1300.0,
      "usdAmount": 306.31,
      "targetCurrency": "USD",
      "exchangeRate": 1300.0,
      "targetAmount": 306.31,
      "requestedDate": "2026-10-11 15:05:00"
    }
  ],
  "nextCursor": null
}
```

| 필드 | 의미 |
| --- | --- |
| todayTransferCount / todayTransferUsdAmount | KST 오늘 접수한 전체 건수와 USD 합계. 커서·페이지 크기와 무관하게 당일 전체를 집계. 오늘 거래가 없으면 0, 0.00 |
| history[].sourceAmount / fee | KRW 정수로 반올림한 원화 금액 / 고정·비율 수수료의 합 |
| history[].exchangeRate / targetAmount | 수취 통화 환율 / 수취 금액 |
| history[].usdExchangeRate / usdAmount | 일일 한도 계산용 USD 환율 / 수수료 차감 후 USD 금액 |
| history[].requestedDate | 접수 시각(KST) |
| nextCursor | 다음 페이지가 있을 때만 값이 있고 마지막 페이지에서는 null |

미접수 견적은 history와 오늘 집계에서 제외한다. 집계와 목록은 별개 쿼리이며 동시 접수 중에는 조회 시점이 다를 수 있다. 여러 페이지 요청 사이에 새 거래가 생기면 오늘 집계도 바뀔 수 있다.

### 다음 페이지 읽기

응답의 `nextCursor`를 그대로 `cursor`로 전달한다.

```sh
curl "http://localhost:8080/transfer/list?size=20&cursor=<nextCursor>" -H "Authorization: Bearer $TOKEN"
```

커서는 불투명 문자열이다. 클라이언트는 내용을 해석하거나 만들지 않는다. 조회는 항상 인증된 사용자의 거래로 제한되므로 다른 사용자의 커서를 써도 타인의 데이터는 보이지 않는다. 잘못된 cursor, 숫자가 아니거나 1~100을 벗어난 size는 400 INVALID_REQUEST다.

파라미터가 없으면 최신 50건을 반환한다. 기존 전체 이력 반환에서 변경된 동작이며 더 오래된 항목은 nextCursor로 이어서 읽는다. 커서 이후 조회는 고정 스냅샷이 아니다. 앞쪽에 추가된 최신 거래를 보려면 cursor 없이 다시 첫 페이지를 요청한다.

## 요청 ID

앱이 처리하는 응답에는 `X-Request-Id` 헤더가 있다(401·404 오류 포함). 요청에 `X-Request-Id`를 보내면 영문·숫자·`.`·`_`·`-` 1~64자일 때 그 값을 쓰고, 아니면 서버가 UUID를 만든다. 오류가 났을 때 이 값을 알려 주면 서버 로그에서 같은 요청을 찾을 수 있다. 오류 JSON 본문에는 포함되지 않는다. 형식은 멱등성 키와 달리 `~`를 허용하지 않는다. 요청 ID는 로그 상관관계용이며 접수 중복 방지나 권한 판단에 쓰이지 않는다.

## 오류 계약

모든 오류는 같은 형식이다. `resultCode`는 HTTP 상태와 같다.

```json
{
  "resultCode": 400,
  "resultMsg": "QUOTE_EXPRIED"
}
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
| 409 | ALREADY_REQUESTED | 접수 | 이미 접수한 견적(멱등성 키 재현 기간이 지난 경우 포함) |
| 415 | UNSUPPORTED_MEDIA_TYPE | 본문 요청 | 지원하지 않는 요청 Content-Type |
| 422 | IDEMPOTENCY_KEY_REUSED | 접수 | 이미 다른 견적에 사용한 Idempotency-Key |
| 500 | UNKNOWN_ERROR | 모든 곳 | 내부 오류. 원시 예외와 개인정보는 노출하지 않음 |
| 503 | RATE_UNAVAILABLE | 견적 | 환율 조회 실패. 발급된 견적의 접수에는 영향이 없다 |
| 503 | RATE_STALE | 견적 | 환율 기준 시각이 허용 지연(기본 60초)을 벗어남 |
| 503 | DB_UNAVAILABLE | 모든 DB 사용 요청 | DB 연결이 끊겼거나 풀이 연결을 얻지 못한 일시 장애(SQLState 08xxx·57P01~03 또는 JDBC 연결 예외). `Retry-After: 1`이 붙으며 연결이 죽은 트랜잭션은 DB가 롤백하므로 같은 `Idempotency-Key`로 재시도할 수 있다. 다만 커밋 도중 끊기면 결과가 불확실할 수 있어(검증하지 않음) 키 없이 재시도하면 ALREADY_REQUESTED가 될 수 있으니 같은 키로 재시도하거나 이력으로 확인한다 |
| 503 | LOCK_TIMEOUT | 접수, 견적 | 사용자 행 잠금 대기 한도 초과(견적은 저장 시 외래키 공유 잠금 대기). `Retry-After: 1`(초) 헤더가 붙는다. 기본 5000ms, REMITTANCE_LOCK_TIMEOUT_MS(1~30000)로 설정 |
