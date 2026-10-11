# nelson-package

- 요청: 과제 회사 이름을 제거하고 개인 기본 패키지 com.nelson.remittance를 사용한다.
- 범위: main/test Java 경로·선언·참조, 아키텍처 검증 경로, Gradle group, README와 작업 기억.
- 기준선: 깨끗한 dev에서 feature/nelson-package 분기.
- 검증: Java 17 clean check bootJar, 포맷·lint·기존 API/아키텍처 회귀, git diff --check.
- 업무 정책·API 계약은 유지하며 저장소 이름과 과거 작업 기록은 이번 패키지 변경에 포함하지 않는다.
- 종료: 독립 내부 리뷰·검증 후 하네스 커밋, 새 dev PR 및 옵시디언 동기화.
