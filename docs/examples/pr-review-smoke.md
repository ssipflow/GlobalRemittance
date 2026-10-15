# PR 리뷰 동작 확인용 샘플

이 문서는 실행 코드가 아닌 리뷰 테스트용 예제다. 운영 코드와 테스트 설정은 바꾸지 않는다.
아래 함수의 계약은 수수료를 차감한 원화 금액에 환율을 곱해 수취액을 반환하는 것이다.
리뷰어가 계약과 구현의 차이를 찾아 PR 코멘트로 남기는지 확인한다.

```python
def calculate_received_amount(amount, fee, exchange_rate):
    return amount * exchange_rate
```

입력 예시: amount=10000, fee=1000, exchange_rate=0.001.
계약에 따른 기대값은 9.0이다. 이 샘플은 의도적인 리뷰 대상이며 실제 기능에 적용하지 않는다.

확인할 흐름: 작업 브랜치 → dev 대상 PR → AI 리뷰 코멘트 → 사람의 판단.
AI는 이 테스트에서 approve, merge 또는 auto-merge를 수행하지 않는다.
테스트 완료 후 사람이 PR을 닫거나 예제를 수정해 병합할지 결정한다.
