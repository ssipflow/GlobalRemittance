# readme-run-args-restore

- 요청: README 먼저 실행하기에서 상세 가이드로 이동했던 export 이외의 파라미터 전달 예시를 복원한다.
- 기준: dev fe1225e, feature/readme-run-args-restore. README와 작업 기억만 변경한다.
- 변경: Compose는 명령 단위 REMITTANCE_DB_PASSWORD로 실행하고, bootRun --args로 AES·JWT·spring.datasource.password·환율을 전달하는 예시를 추가한다. 자리표시자만 사용하며 실제 키·비밀번호는 기록하지 않는다.
- 설명: 앱 인자 우선순위, Gradle -D가 자동 전달되지 않는 점, 기존 키 재사용과 로컬 인자 노출 주의는 유지한다.
- 검증: git diff --check 등록, 독립 reviewer/verifier의 설정 속성명·셸 예시 대조. 앱·DB·테스트는 새로 실행하지 않는다.
- 최종 snapshot에서 리뷰·verify 후 finish·publish한다. README는 export 대상이 아니고 앱 설명·구조 변경도 없어 옵시디언 본문 동기화는 필요하지 않다.
