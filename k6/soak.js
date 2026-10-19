// 지속(soak) 부하: 일정한 VU로 수 분간 유지하며 시간이 지나도 응답시간이 나빠지지 않는지 본다.
// 전체 시간을 3등분(early/mid/late)해 태그하고 같은 상한을 모두에 건다. 마지막 구간이 처음 구간과
// 같은 기준을 지켜야 한다. 상한(150ms)은 관찰값(약 12ms)보다 크게 넉넉해 다른 머신에서도 통과하도록 둔 값이므로
// 10배 안팎의 급격한 악화(풀 고갈·심한 누수)만 잡고 완만한 악화는 놓친다. 정밀한 회귀 판정은 관찰 수치를 비교해야 한다.
import { sleep } from 'k6';
import { setPhase, toSeconds, transferIteration } from './lib.js';

const DURATION = __ENV.DURATION || '5m';
const VUS = Number(__ENV.VUS || 20);
const TOTAL = toSeconds(DURATION);

export const options = {
  scenarios: {
    soak: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    unexpected_responses: ['count==0'],
    invariant_violations: ['count==0'],
    server_error_rate: ['rate<0.01'],
    http_req_failed: ['rate<0.001'],
    // 세 구간에 같은 상한을 둔다. 한 구간이라도 넘으면 악화로 본다.
    'http_req_duration{phase:early,name:request}': ['p(95)<150'],
    'http_req_duration{phase:mid,name:request}': ['p(95)<150'],
    'http_req_duration{phase:late,name:request}': ['p(95)<150'],
    'http_req_duration{phase:early,name:quote}': ['p(95)<150'],
    'http_req_duration{phase:mid,name:quote}': ['p(95)<150'],
    'http_req_duration{phase:late,name:quote}': ['p(95)<150'],
    'flow_healthy_rate{phase:late}': ['rate>0.99'],
  },
};

export function setup() {
  return { start: Date.now() };
}

export default function (data) {
  const t = (Date.now() - data.start) / 1000;
  setPhase(t < TOTAL / 3 ? 'early' : t < (2 * TOTAL) / 3 ? 'mid' : 'late');
  transferIteration();
  sleep(0.2 + Math.random() * 0.3);
}
