# API 문서

## 공통 계약

- 기본 URL 예시: `http://localhost:8080`. 본문 요청은 `Content-Type: application/json`이다.
- 모든 성공·오류 응답은 JSON이다. 성공은 HTTP 200, `resultCode: 200`, `resultMsg: "OK"`다.
- 송금 API는 `Authorization: Bearer <token>`이 필요하다. 회원가입·로그인은 인증 없이 호출한다.
- 견적·이력 응답의 날짜 문자열은 KST `yyyy-MM-dd HH:mm:ss`다. 금액과 환율은 JSON number이며 quoteId는 문자열이다.
- JWT는 발급 후 30분 유효하고 id, userId, name, expireTime과 표준 인증 클레임을 포함한다. 토큰 내부 expireTime은 KST ISO offset 형식이다.

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

계정 없음·비밀번호 불일치는 HTTP 401 UNAUTHORIZED다.

## POST /transfer/quote

| 필드 | 형식·제약 |
| --- | --- |
| amount | KRW 금액, 양수, 정수부 최대 10자리·소수부 최대 12자리 |
| targetCurrency | USD 또는 JPY |

```json
{"amount":400000,"targetCurrency":"USD"}
```

예시 환율 1300에서 수수료 1800원, 순송금액 398200원이다. 만료시각은 생성시각 +10분이다.

```json
{"resultCode":200,"resultMsg":"OK","quote":{"quoteId":"1","exchangeRate":1300.000000000000,"expireTime":"2026-10-11 15:10:00","targetAmount":306.31}}
```

quoteId와 시각은 예시다. 금액의 KRW 반올림과 수수료 차감 후 음수 여부, 저장 가능한 DECIMAL 범위도 검사한다. 소수 입력은 HALF_UP로 KRW 정수화한 후 정책을 적용한다.

## POST /transfer/request

quoteId는 양의 숫자 문자열이며 실제 INT 식별자 범위를 검증한다.

```json
{"quoteId":"1"}
```

```json
{"resultCode":200,"resultMsg":"OK"}
```

현재 사용자 소유이며 아직 접수하지 않은 유효 견적만 접수한다. 일일 순송금 USD 한도는 REG_NO 1000, BUSINESS_NO 5000이고 동일한 금액은 허용한다. 오류 우선순위는 인증 → 존재·소유권 → 중복 → 만료 → 한도다.

## GET /transfer/list

요청 본문은 없다. 오늘은 KST 날짜 기준이며, history는 접수 완료 항목 전체를 접수시각·ID 내림차순으로 반환한다.

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
  }]
}
```

미접수 견적은 history와 오늘 합계에서 제외한다. 오늘 거래가 없으면 count는 0, USD 합계는 0.00이다. 현재 페이지네이션은 없다.

## 오류 계약

```json
{"resultCode":400,"resultMsg":"QUOTE_EXPRIED"}
```

| HTTP | resultMsg | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 입력 형식·통화·자릿수·식별자 범위 오류 |
| 400 | NEGATIVE_NUMBER | 양수가 아닌 입력 또는 수수료 차감 후 음수 |
| 400 | QUOTE_EXPRIED | 만료시각 이상에서 접수 요청, 원 요구사항 철자 유지 |
| 400 | LIMIT_EXCESS | KST 하루 순송금 USD 합계 초과 |
| 401 | UNAUTHORIZED | 로그인 실패, 누락·잘못된·만료 토큰 |
| 403 | FORBIDDEN | 타인 견적 접수 또는 접근 거부 |
| 404 | QUOTE_NOT_FOUND / NOT_FOUND | 견적 없음 / 경로 없음 |
| 409 | DUPLICATE_USER / ALREADY_REQUESTED | 중복 회원 / 이미 접수한 견적 |
| 405 | METHOD_NOT_ALLOWED | 허용하지 않은 HTTP 메서드 |
| 406 | NOT_ACCEPTABLE | 지원하지 않는 응답 형식 요청 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 지원하지 않는 요청 Content-Type |
| 500 | UNKNOWN_ERROR | 내부 오류, 원시 예외·개인정보 미노출 |
