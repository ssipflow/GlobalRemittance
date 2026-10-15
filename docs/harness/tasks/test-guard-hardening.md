# test-guard-hardening

- 배경: PR #17(Swagger) 후속 제안 두 가지. (1) ArchitectureTest가 domain/application의 `io/swagger/` 참조를 막지 않아 OpenAPI 어노테이션이
  내부 계층으로 새도 잡히지 않는다. (2) OpenApiTest가 `/swagger-ui.html`을 200 또는 302로 느슨하게 허용해 리다이렉트 회귀를 놓칠 수 있다.
- 사용자 결정: 후속 후보 진행. 테스트만 바꾸며 앱 동작·문서는 바뀌지 않는다(앱 문서 불변이므로 Vault 동기화 불필요).
- 계약: 금지 목록에 `io/swagger/`·`org/springdoc/` 추가, 금지 문자열 형식이 바이트코드와 맞는지 보이는 양성 대조(adapter/web에 `io/swagger/` 존재),
  `/swagger-ui.html`은 인증 없이 정확히 302이고 `Location: /swagger-ui/index.html`이며 그 경로가 200이다.
- 불변식: 앱 코드·설정·API 불변. 기존 테스트 의미 유지.
- 완료 기준: 금지 검사가 실제로 잡는지 직접 확인, Java 17 check·bootJar, dev PR.

## 구현 결과와 직접 확인
- ArchitectureTest: `FORBIDDEN` 목록 정리와 양성 대조 테스트 추가. OpenApiTest: 302와 Location 정확 검증.
- 관찰한 실제 동작: `/swagger-ui.html` → 302, `Location: /swagger-ui/index.html`, 해당 경로 200(인증 없음).
- 가드 동작 확인: implementer가 `domain/BusinessException`에 `@io.swagger.v3.oas.annotations.media.Schema`를 임시로 붙이자 ArchitectureTest가
  `BusinessException.class depends on io/swagger/`로 실패했고, 원복 뒤 `git status`에 해당 파일이 남지 않았다.
- 검증: Java 17 `clean check bootJar` 전체 통과(최종 개수는 PR 본문 기준).
- 한계: `org/springdoc/` 금지 항목은 현재 코드에 springdoc 타입을 쓰는 곳이 없어 양성 대조로 형식을 증명하지 못했다(패키지 경로 형식이라는 점에만 의존한다).
  상수 풀 문자열 검사라 리플렉션·문자열 조합 참조나 메서드 본문의 간접 참조는 잡지 못한다. Location 값은 springdoc 동작에 맞춘 것이라
  라이브러리 버전이 바뀌면 달라질 수 있다.
