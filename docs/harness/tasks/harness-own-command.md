# harness-own-command

- 배경: 최근 세 작업(k6, review-fixes-1, review-fixes-2)에서 `finish`가 "소유 범위 밖 변경"으로 거부되었고, 하네스에 소유 파일을 추가하는 명령이 없어 로컬 상태 파일 `.harness/task.json`을 직접 편집해 해결했다. 수동 편집은 감사 흔적이 없고 실수하기 쉽다.
- 사용자 결정: 제안을 받아 진행("다음 작업").
- 변경: `harness.py own --files ...`. 활성 작업에서만 허용하며 경로 검증을 `begin`과 같은 함수(`owned_paths`)로 공유한다. 추가 내역을 `ownership_additions`(파일, 시각)로 task.json에 기록한다.
- 불변식: 기존 `begin`·`finish`·`verify`·`publish` 동작 불변. 소유 범위 외 변경 거부 규칙은 그대로이며 `own` 없이는 범위가 넓어지지 않는다. 리뷰·검증 증거는 내용 기반 snapshot에 묶여 있어 `own` 뒤 파일이 바뀌면 무효가 된다.
- 테스트(`scripts/tests/test_harness.py`): 추가 파일이 커밋에 포함됨과 기록 남음, 디렉터리·절대·상위 경로·내부 상태 거부와 목록 불변, 활성 작업 없을 때 거부, `own` 뒤 변경 시 이전 증거로 커밋 불가.
- 한계: `own`은 범위를 넓히는 도구라 남용하면 소유 제한의 의미가 약해진다. 추가 이유를 강제하지 않으며 `ownership_additions`는 로컬 상태(Git 미추적)에만 남는다. 리뷰어가 추가 내역을 보려면 `status`를 확인해야 한다.
- 추가 한계: `begin`은 깨끗한 작업 트리를 요구하지만 `own`은 이미 수정돼 있던 파일도 소유로 넣을 수 있다. snapshot 리뷰를 거치므로 검토 없이 커밋되지는 않으나, 사용자의 기존 변경을 포함하지 않는다는 AGENTS.md 원칙은 Coordinator가 지켜야 한다(하네스가 강제하지 않는다).
- 테스트 보강: 이미 검증된 snapshot의 파일을 own해도 증거 불변, own 뒤 변경 시 'Latest snapshot needs' 사유로 거부, 이미 소유한 파일 무시, 커밋 후 own 거부.
