// 접수 흐름 부하: 점진 램프업으로 VU를 늘리며 가입(VU별 고유 사용자) -> 로그인 -> 견적 -> 접수를 반복한다.
// 사용자 분산: VU마다 고유 사용자를 쓰고 일일 한도에 닿으면 새 사용자로 교체하므로 한도 때문에 부하가 막히지 않는다.
// 임계값 근거는 docs/harness/tasks/k6-load-test.md의 로컬 관찰값 + 여유다(운영 성능 보증 아님).
import { sleep } from 'k6';
import { transferIteration } from './lib.js';

const PEAK = Number(__ENV.VUS || 30);

export const options = {
  stages: [
    { duration: '20s', target: Math.ceil(PEAK / 3) },
    { duration: '40s', target: PEAK },
    { duration: '40s', target: PEAK },
    { duration: '15s', target: 0 },
  ],
  thresholds: {
    // 계약 밖 응답(500/UNKNOWN_ERROR, 401, 알 수 없는 코드)은 한 건도 없어야 한다.
    unexpected_responses: ['count==0'],
    // 재시도 불변식(같은 키 200 재현, 키 없는 재시도 409) 위반 0건.
    invariant_violations: ['count==0'],
    // 정의된 503까지 포함한 5xx 비율 상한.
    server_error_rate: ['rate<0.01'],
    // 전송 실패(401/500 등 목록 밖 상태)는 사실상 없어야 한다.
    http_req_failed: ['rate<0.001'],
    // 가입·로그인은 BCrypt로 느려서 견적·접수·이력과 따로 상한을 둔다.
    'http_req_duration{name:quote}': ['p(95)<150'],
    'http_req_duration{name:request}': ['p(95)<150'],
    'http_req_duration{name:list}': ['p(95)<150'],
    'http_req_duration{name:signup}': ['p(95)<1000'],
    'http_req_duration{name:login}': ['p(95)<1000'],
  },
};

export default function () {
  transferIteration();
  sleep(0.2 + Math.random() * 0.3);
}
