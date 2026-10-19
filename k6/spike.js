// 폭주(spike): 낮은 기준 부하 -> 갑작스러운 VU 급증 -> 복귀. 급증 뒤 정상 응답이 회복되는지 본다.
// 구간은 시작 시각 기준 경과 시간으로 태그한다(base / spike / recovery).
import { sleep } from 'k6';
import { setPhase, transferIteration } from './lib.js';

const BASE = Number(__ENV.BASE_VUS || 5);
const SPIKE = Number(__ENV.SPIKE_VUS || 100);
// 단계 길이(초). 복귀 구간 태그 계산에 같이 쓴다.
const T_BASE = 20;
const T_UP = 5;
const T_HOLD = 20;
const T_DOWN = 5;

export const options = {
  stages: [
    { duration: `${T_BASE}s`, target: BASE },
    { duration: `${T_UP}s`, target: SPIKE },
    { duration: `${T_HOLD}s`, target: SPIKE },
    { duration: `${T_DOWN}s`, target: BASE },
    { duration: '45s', target: BASE },
  ],
  thresholds: {
    // 가입·로그인(BCrypt)은 급증 순간 수 초까지 느려지는 것이 관찰됐지만 CPU 바운드 특성이라 지연 임계값을 걸지 않는다.
    // 대신 계약 밖 응답은 막는다.
    unexpected_responses: ['count==0'],
    invariant_violations: ['count==0'],
    // 급증 중에는 느려질 수 있으나 5xx(정의된 503 포함)는 전체 1% 미만이어야 한다.
    server_error_rate: ['rate<0.01'],
    // 기준 구간 대비 급증 구간 상한은 넓게, 복귀 구간은 기준 수준으로 좁게 건다.
    'http_req_duration{phase:spike,name:request}': ['p(95)<1000'],
    'http_req_duration{phase:recovery,name:request}': ['p(95)<150'],
    'http_req_duration{phase:recovery,name:quote}': ['p(95)<150'],
    // 복귀 구간의 흐름은 거의 모두 계약대로 응답(정상 또는 정의된 거절, 503 아님)이어야 한다.
    'flow_healthy_rate{phase:recovery}': ['rate>0.99'],
  },
};

export function setup() {
  return { start: Date.now() };
}

export default function (data) {
  const t = (Date.now() - data.start) / 1000;
  // 복귀 구간은 감소 단계가 끝난 뒤 10초 여유를 두고 시작한다(대기열 소진 시간).
  const spikeEnd = T_BASE + T_UP + T_HOLD;
  const phase = t < T_BASE ? 'base' : t < spikeEnd + T_DOWN + 10 ? 'spike' : 'recovery';
  setPhase(phase);
  transferIteration();
  sleep(0.2 + Math.random() * 0.3);
}
