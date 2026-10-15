# readme-run-args

- 배경: 사용자 질문. README 실행 안내가 환경변수 방식만 있어 Gradle 인자로 전달하는 방법을 함께 안내한다.
- 범위: README.md 실행 절만. 코드·설정 변경 없음. 앱 이해용 문서(docs/application) 변경이 없어 Obsidian 동기화는 하지 않는다.
- 확인한 사실: REMITTANCE_* 환경변수가 없는 셸에서 `./gradlew bootRun --args="--remittance.aes-key=… --remittance.jwt-secret=…"`
  (메모리 DB, 별도 포트)로 기동되어 가입이 200으로 응답했다. 커맨드라인 인자가 `${REMITTANCE_AES_KEY}` 자리표시자를 덮어쓴다.
- 서술 원칙: 환경변수를 기본으로 유지하고 인자 방식은 로컬 개발 대안으로 쓴다. 인자는 프로세스 목록·셸 히스토리에 남는 위험을 명시한다.
  `./gradlew -Dremittance…`는 Gradle JVM 프로퍼티라 앱에 전달되지 않는다는 점도 적는다.
- 추가 확인: 같은 조건에서 키를 `-Dremittance.aes-key=…`로만 주면 `Could not resolve placeholder 'REMITTANCE_AES_KEY'`로 기동이 실패했다.
