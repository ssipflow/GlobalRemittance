# swagger-openapi

- 배경: 사용자 요청. 다음 기능 작업 전에 API를 Swagger UI로 확인·호출할 수 있게 한다.
- 접근: springdoc-openapi(Spring Boot 3.3 호환 2.x). 명세는 컨트롤러·DTO 코드에서 생성하고 문서와 어긋나지 않게 테스트로 고정한다.
- 경로: `/swagger-ui.html`(UI), `/v3/api-docs`(JSON 명세). 두 경로는 인증 없이 열린다(기존 `anyRequest().permitAll()`).
- 노출 정책: 기본 켜짐. `REMITTANCE_API_DOCS_ENABLED=false`로 UI와 명세를 함께 끌 수 있다.
- 보안 스키마: Bearer JWT를 `bearerAuth`로 선언하고 `/transfer/*`에만 요구한다. Swagger UI의 Authorize에 로그인 토큰을 넣는다.
- 명세 내용: 모든 엔드포인트의 요약, 요청 필드 제약, `Idempotency-Key`·`size`·`cursor` 파라미터, 주요 오류 응답
  (공통 오류 형식 `Result`)을 한글로 설명한다. 비밀번호·식별번호 같은 민감 값의 예시는 실제 값을 쓰지 않는다.
- 불변식: domain/application에는 Swagger 의존성이 들어가지 않는다(ArchitectureTest). 기존 API 동작과 오류 계약은 변하지 않는다.
- 완료 기준: 명세가 5개 경로(오퍼레이션 5개)과 bearerAuth, 오류 응답, 헤더·쿼리 파라미터를 포함하는지 테스트,
  UI 페이지 도달, 끈 설정에서 404 JSON, 기존 테스트 전부 통과, Java 17 check·bootJar, README·docs/application 갱신.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람.

## 구현 결과
- 의존성 springdoc-openapi-starter-webmvc-ui 2.5.0(버전 고정). UI `/swagger-ui.html`, 명세 `/v3/api-docs`.
- `REMITTANCE_API_DOCS_ENABLED`(기본 true)가 springdoc.api-docs/swagger-ui를 함께 제어. 끄면 기존 404 JSON 계약.
- 명세 선언은 adapter/web(OpenApiConfiguration + 컨트롤러/DTO 어노테이션)에만 둔다. 오류 처리용 `/error`는 `paths-to-exclude`로 제외.
- 보안 경로 permitAll은 기존 `anyRequest().permitAll()`과 동작이 같고 의도를 드러내는 장치다.

## 내부 리뷰 반영
- 독립 reviewer: 차단 결함 없음. 비차단 제안은 반영하지 않고 남긴다: 리다이렉트(302) 단언 강화, 200 응답 스키마·DTO 제약 반영 검증,
  ArchitectureTest 금지 목록에 `io/swagger/` 추가(테스트 파일이 소유 목록 밖), Idempotency-Key 설명에 24시간 창 추가,
  `/_test/**` 제외.
- 미확인(기록): springdoc 2.5.0/swagger-ui 5.13.0 전이 의존성의 알려진 취약점은 오프라인이라 확인하지 못했다.
  Boot 3.3.0과의 런타임 호환은 통합 테스트 통과로만 확인했다. 기본이 켜짐이므로 운영 노출 환경에서는 끄는 것을 권한다.
