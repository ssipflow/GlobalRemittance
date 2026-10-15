# 새 송금 서비스 요구사항

원본: 사용자가 지정한 옵시디언 `Goals Of Project`. 이 문서는 구현·검증용 요구사항이며
개인 Vault의 경로나 다른 노트는 저장하지 않는다. 기존 애플리케이션은 전면 교체한다.

## 실행 환경과 공통 규칙

- Java 17 이상, Spring Boot 3 이상, embedded H2, Gradle, JUnit.
- 모든 HTTP 성공·오류 응답은 JSON. JSON 본문을 받는 API는 application/json.
- 비밀번호는 BCrypt, 주민/사업자 번호는 AES로 저장. 인증 토큰은 발급 후 30분.
- API 시간은 KST. 모든 소수점 연산은 HALF_UP. 환율 최대 소수 12자리.
- 통화 금액은 Java Currency.defaultFractionDigits를 사용(KRW 0, USD 2, JPY 0).
- 환율은 수취 통화 → KRW(예: USD_to_KRW). 금액과 환율은 BigDecimal.
- README에 요구사항 구현 여부, 구현 방법, 실제 검증 결과와 회고/제한을 기록.

## 사용자와 인증

- POST /user/signup: userId(email, unique), password, name, idType(REG_NO/BUSINESS_NO), idValue.
- 응답: resultCode=200, resultMsg=OK.
- POST /user/login: userId, password. 기본 응답과 token을 반환.
- JWT payload: DB id, userId, name, expireTime. 표준 iat/exp/sub도 사용 가능.
- 이후 송금 API는 Bearer 토큰에서 식별한 사용자만 접근.

## 송금 견적

- POST /transfer/quote: amount(KRW), targetCurrency(USD/JPY).
- 기본 응답과 quote:{quoteId(문자열),exchangeRate,expireTime,targetAmount}.
- 견적 유효시간 10분, API 날짜 yyyy-MM-dd HH:mm:ss(KST).
- USD amount ≤ 1,000,000: fixedFee=1000, feeRatio=0.002.
- USD amount > 1,000,000: fixedFee=3000, feeRatio=0.001.
- JPY: fixedFee=3000, feeRatio=0.05.
- ratedFee=sourceAmount*feeRatio, fee=ratedFee+fixedFee.
- targetAmount=(sourceAmount-fee)/target_to_KRW. usdAmount는 같은 순송금액/USD_to_KRW.
- 음수 입력/음수 수취금액은 NEGATIVE_NUMBER. 견적과 적용 환율/수수료를 저장.

## 접수와 이력

- POST /transfer/request: quoteId(문자열), 기본 성공 응답.
- REG_NO의 일일 한도 1000 USD, BUSINESS_NO는 5000 USD. 초과시 LIMIT_EXCESS.
- 만료시 원본 철자인 QUOTE_EXPRIED, 그 외 예상치 못한 오류는 UNKNOWN_ERROR.
- 동일 견적 중복 접수/다른 사용자 견적 접근을 막고 한도 검사와 접수를 원자적으로 처리.
- GET /transfer/list: 기본 응답, userId, name, todayTransferCount,
  todayTransferUsdAmount, history(전체 접수 거래).
- history 항목: sourceAmount,fee,usdExchangeRate,usdAmount,targetCurrency,
  exchangeRate,targetAmount,requestedDate. 오늘 집계는 KST 일자 기준.

## 저장 데이터

- users: 순차 id, 유일 userId, password, name, idType, idValue, createdAt,updatedAt.
- remittances: 순차 id, FK userId/index, sourceAmount,targetAmount,targetCurrency,
  exchangeRate,fixedFee,ratedFee,feeRatio,usdAmount,usdExchangeRate,
  expireTime,nullable requestedTime,createdAt,updatedAt.
- 금융 수치 저장은 DECIMAL(22,12). 시간 저장 기준은 설계 결정에서 명시.

## 요구사항 해석

문서의 수취금액 예시는 JPY 소수점, 환율 방향, 수수료 예시가 규칙과 일부 다르다.
명시된 공식과 Currency 소수 자릿수 규칙을 우선한다. 원문 SQL timestamp 차이는
UTC Instant 저장과 KST 표시로 통일한다. 환율 제공처는 지정되지 않아 설정 어댑터로
격리하며 실제 시세를 자동 조회한다고 주장하지 않는다.
