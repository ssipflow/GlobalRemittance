# remove-backup-sql

- 배경: 사용자가 "backup만 지워"라고 지시했다. 루트의 `backup.sql`은 2024-06 초기 커밋("Moin Test")에서 들어온 H2 덤프(스키마와 `INSERT` 2줄, H2 `SA` 계정 해시 포함)다.
- 확인: 코드·문서·스크립트·빌드 설정 어디에서도 참조하지 않는다(`grep`으로 확인). 스키마는 Flyway SQL이 관리한다.
- 계약: 파일 삭제 외 변경 없음. 운영 코드·테스트 불변.
- 한계: 과거 커밋 이력에는 파일이 남는다. 이력 재작성은 하지 않는다(하네스 규칙). 해시를 이력에서도 지우려면 사용자 결정과 별도 절차가 필요하다.
- 종료: 커밋·push·dev PR. 승인·병합은 사람. `finish`는 `diff.renames=false` 환경변수로 실행한다.

## 직접 확인
- 삭제 후 `git grep backup.sql`로 남은 참조 0건(이 기록·state.md 제외)을 확인했고, `./gradlew clean check bootJar` 통과(harness verify)로 빌드 영향이 없음을 확인했다. 독립 리뷰어도 참조 0건과 변경 범위를 확인했다.
