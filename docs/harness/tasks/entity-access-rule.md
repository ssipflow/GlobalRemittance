# entity-access-rule

- 배경: 패키지 분리(#40)로 엔티티 생성자와 `toDomain()`이 public이 되어, 다른 계층이 엔티티를 직접 만들거나 쓸 수 있게 됐다. 사용자가 규칙으로 막자고 결정했다.
- 계약: `adapter/persistence` 밖의 어떤 main 클래스도 `com/nelson/remittance/adapter/persistence/entity/` 타입을 참조하지 않는다(`ArchitectureTest`, 바이트코드 상수 풀 검사). 양성 대조로 `repository` 클래스가 실제로 엔티티 문자열을 참조함을, 단위 테스트로 위반 샘플이 감지되고 비슷한 패키지는 오탐하지 않음을 확인한다.
- 불변식: 운영 코드 불변. 규칙이 항상 통과하는 일을 막기 위해 클래스 파일이 하나도 없으면 실패한다.
- 종료: 커밋·push·dev PR 후 옵시디언 sync. 승인·병합은 사람. `finish`는 `diff.renames=false` 환경변수로 실행한다.

## 구현 결과와 직접 확인
- 구현은 implementer가 `ArchitectureTest`에 테스트 3개(규칙, 양성 대조, 위반 샘플 감지)와 헬퍼를 추가했다. Spotless는 `spotlessApply`로 정리했다.
- **규칙이 실제로 위반을 잡는지 직접 확인**: 임시로 `adapter/web` 아래에 `UserEntity`를 참조하는 클래스를 만들어 `ArchitectureTest`를 돌리면 `Violation.class references JPA entity package …` 메시지로 `onlyPersistenceAdapterReferencesJpaEntities`가 실패했다. 확인 후 임시 파일을 지웠고 최종 변경은 `ArchitectureTest`와 문서뿐이다.
- 현재 코드에서는 규칙이 통과한다(위반 없음).
- 테스트: check 197개(기존 194 + 규칙 관련 3), postgresTest 17개 통과, 실패·건너뜀 0(결과 XML 합산).
- 한계: 바이트코드 상수 풀의 `/` 표기 참조만 잡는다. 점(.) 표기 문자열(`@EntityScan("…")`, JPQL 문자열의 FQN 등)은 잡지 못하며 현재 그런 참조는 없다. 리뷰어의 권고(상수 위치, 샘플 이름)는 비차단이라 반영하지 않았다.
