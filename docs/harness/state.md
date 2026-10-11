# 현재 상태

- 목표: Goals Of Project를 기준으로 송금 서비스를 처음부터 구현한다.
- 진행: feature/remittance-rebuild에서 기존 애플리케이션 전체를 클린 아키텍처로 교체.
- 요구사항: docs/requirements/project-goals.md. 원본은 사용자가 지정한 Goals Of Project 노트.
- 컨벤션: domain/application/adapter/bootstrap 의존성, 한글로 업무 불변식·설계 이유 주석.
- 완료 기준: 회원가입·로그인·견적·접수·이력 API, 금액/시간/보안/동시성 회귀 검증,
  포맷·린트와 Gradle 빌드, 독립 리뷰와 검증, README 회고, dev PR 및 옵시디언 동기화.
- 환경: 프로젝트 Java 17 호환, 현재 로컬 JVM은 Java 21. 실제 검증 런타임을 명시한다.
- 검증: Java 17 앱 27개, Spotless·Checkstyle main/test 통과, 실행 JAR 빌드 확인.
  최종 snapshot의 독립 전체 검증·리뷰 후 커밋한다.
- 기준선: 기존 홈 H2/외부 환율 API 의존 테스트는 실행하지 않음. 새 테스트는 임시 H2와 고정 시간/환율.
- 기억: 기존 하네스·브랜치 정책과 Git 이력은 유지. 새 요구사항을 이전 개선 백로그보다 우선한다.
- 보안: 실행 키는 환경변수로 받는다. 개인 경로와 키는 기억 파일에 저장하지 않는다.
- 옵시디언: 프로젝트 전용 동기화 연결됨. 실제 결과는 무시되는 로컬 보고서에서 확인.
- 실행 범위: 로컬 내부 개선·검증·커밋·작업 브랜치 push·dev PR까지. 승인·병합은 사람.
- 작업 기록: docs/harness/tasks/remittance-rebuild.md.
- 다음: 사람이 dev PR을 승인/병합한다. 이후 실제 환율 공급과 거래량 증가에 따른 집계를 검토한다.
