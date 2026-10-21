// 용량(capacity) 탐색: 초당 도착률을 단계적으로 올려 지연·오류가 무너지는 지점과 병목을 찾는다.
// 통과/실패 게이트가 아니라 탐색용이다. 임계값은 abortOnFail 없이 "단계별 메트릭을 요약에 노출"하는 용도다.
// 설계 이유: VU 수 고정(폐쇄 모델)은 서버가 느려지면 요청도 같이 줄어 한계를 가린다.
// ramping-arrival-rate(개방 모델)는 서버 상태와 무관하게 일정 속도로 요청을 보내므로 대기열이 쌓이는 시점이 드러난다.
// 서버가 못 따라가면 VU가 소진되어 dropped_iterations가 늘어난다(= 한계 신호).
//
// 환경변수:
//   SCENARIO       auth | transfer | signup | list (기본 transfer)
//                  auth: 로그인 반복 / transfer: 견적+송금 요청 흐름 / signup: 매 반복 신규 사용자 가입 / list: 이력 조회
//   STEPS          단계별 초당 반복 수, 쉼표 구분
//                  (auth, signup 기본 5,10,15,20,30,40,60 / transfer 기본 50,100,200,300,400,600 / list 기본 100,200,400,600,800,1000)
//   STEP_SECONDS   각 단계 유지 시간(초, 기본 30)
//   RAMP_SECONDS   단계 사이 증가 시간(초, 기본 5)
//   USERS          사전 생성 사용자 수 (transfer 기본 1000, 사용자당 일일 한도로 총 반복 수/250보다 크게 / auth, list 기본 100 / signup은 사용 안 함)
//                  list는 setup에서 사용자마다 송금 3건씩 만들어 이력을 채운다
//   PRE_VUS, MAX_VUS  사전 할당/최대 VU (기본 200 / 1500)
//   BASE_URL, RUN_ID, LOAD_USER_PASSWORD  lib.js와 동일
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, RUN_ID, classify } from './lib.js';

// 상태 0은 서버 응답이 아니라 연결 실패나 시험 종료로 인한 요청 중단이다. 예상 밖 응답과 섞이지 않게 따로 센다.
const aborted = new Counter('aborted_requests');

function track(res, kind) {
  if (res.status === 0) {
    aborted.add(1);
    return { status: 0, msg: 'ABORTED', body: null, ok: false };
  }
  return classify(res, kind);
}

const SCENARIO = __ENV.SCENARIO || 'transfer';
if (!['auth', 'transfer', 'signup', 'list'].includes(SCENARIO)) {
  throw new Error(`SCENARIO는 auth, transfer, signup, list 중 하나여야 합니다: ${SCENARIO}`);
}
// BCrypt가 병목인 시나리오(auth, signup)는 낮은 도착률에서 시작한다.
const DEFAULT_STEPS = {
  auth: '5,10,15,20,30,40,60',
  signup: '5,10,15,20,30,40,60',
  transfer: '50,100,200,300,400,600',
  list: '100,200,400,600,800,1000',
};
const STEPS = (__ENV.STEPS || DEFAULT_STEPS[SCENARIO])
  .split(',')
  .map((s) => Number(s.trim()));
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);
const RAMP_SECONDS = Number(__ENV.RAMP_SECONDS || 5);
const USERS = SCENARIO === 'signup' ? 0 : Number(__ENV.USERS || (SCENARIO === 'transfer' ? 1000 : 100));
// list의 setup에서 사용자당 만들 송금 수
const LIST_SEED_TRANSFERS = 3;
const PRE_VUS = Number(__ENV.PRE_VUS || 200);
const MAX_VUS = Number(__ENV.MAX_VUS || 1500);
// 예시용 비밀번호이며 실제 계정 비밀번호가 아니다(lib.js와 같은 값).
const PASSWORD = __ENV.LOAD_USER_PASSWORD || 'k6-load-example-pw';
// setup 병렬 배치 크기. BCrypt가 CPU를 많이 쓰므로 너무 크게 잡지 않는다.
const CHUNK = 15;

if (STEPS.some((s) => !(s > 0))) {
  throw new Error(`STEPS에 양수가 아닌 값이 있습니다: ${__ENV.STEPS}`);
}
const STEP_LEN = RAMP_SECONDS + STEP_SECONDS;
const TOTAL_SECONDS = STEPS.length * STEP_LEN;
// JWT 유효기간이 30분이므로 setup 시간을 포함해도 실행이 20분을 넘지 않게 막는다.
if (TOTAL_SECONDS > 20 * 60) {
  throw new Error(`총 실행 시간 ${TOTAL_SECONDS}s가 20분을 넘습니다(JWT 30분 유효, 준비 시간 포함 여유). STEPS/STEP_SECONDS를 줄이세요.`);
}

const stages = [];
STEPS.forEach((rate) => {
  stages.push({ duration: `${RAMP_SECONDS}s`, target: rate });
  stages.push({ duration: `${STEP_SECONDS}s`, target: rate });
});

// 단계별 지표가 요약에 나오도록 항상 참인 임계값을 건다(실패하지 않으며 중단도 없다).
const thresholds = {
  unexpected_responses: ['count>=0'],
  server_error_rate: ['rate>=0'],
  dropped_iterations: ['count>=0'],
};
// 시나리오의 대표 호출(name 태그)
const MAIN_NAME = { auth: 'login', transfer: 'request', signup: 'signup', list: 'list' }[SCENARIO];
STEPS.forEach((rate) => {
  const tag = `{step:r${rate}}`;
  thresholds[`http_req_duration{step:r${rate},name:${MAIN_NAME}}`] = ['max>=0'];
  thresholds[`http_req_duration${tag}`] = ['max>=0'];
  thresholds[`http_reqs${tag}`] = ['count>=0'];
  thresholds[`server_error_rate${tag}`] = ['rate>=0'];
  thresholds[`unexpected_responses${tag}`] = ['count>=0'];
});

export const options = {
  setupTimeout: '300s',
  scenarios: {
    capacity: {
      executor: 'ramping-arrival-rate',
      startRate: STEPS[0],
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages,
    },
  },
  thresholds,
};

function jsonParams(name, token, extraHeaders) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, extraHeaders || {});
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags: { name } };
}

// list용: 사용자마다 승인된 송금을 LIST_SEED_TRANSFERS건씩 만들어 이력이 비어 있지 않게 한다.
// 견적 후 고유 Idempotency-Key로 요청하며, 사용자 CHUNK명씩 병렬로 처리한다.
function seedTransfers(users) {
  let done = 0;
  for (let round = 0; round < LIST_SEED_TRANSFERS; round++) {
    for (let from = 0; from < users.length; from += CHUNK) {
      const part = users.slice(from, from + CHUNK);
      const quotes = http.batch(
        part.map((u) => [
          'POST',
          `${BASE_URL}/transfer/quote`,
          JSON.stringify({ amount: 5000 + Math.floor(Math.random() * 1001), targetCurrency: 'USD' }),
          jsonParams('quote', u.token),
        ]),
      );
      const reqs = [];
      part.forEach((u, k) => {
        const q = classify(quotes[k], 'quote');
        if (q.ok && q.body.quote) {
          reqs.push([
            'POST',
            `${BASE_URL}/transfer/request`,
            JSON.stringify({ quoteId: q.body.quote.quoteId }),
            jsonParams('request', u.token, { 'Idempotency-Key': `k6cap-${RUN_ID}-seed-${from + k}-${round}` }),
          ]);
        }
      });
      if (reqs.length > 0) {
        http.batch(reqs).forEach((res) => {
          if (classify(res, 'request').ok) {
            done += 1;
          }
        });
      }
    }
  }
  console.log(`이력용 송금 ${done}/${users.length * LIST_SEED_TRANSFERS}건 준비`);
}

// 사용자를 CHUNK개씩 병렬로 가입시키고 로그인해 { userId, token } 목록을 만든다.
export function setup() {
  const users = [];
  if (SCENARIO === 'signup') {
    console.log(`사전 사용자 없음(signup), 총 실행 ${TOTAL_SECONDS}s, 단계 ${STEPS.join(',')}`);
    return users;
  }
  for (let from = 0; from < USERS; from += CHUNK) {
    const ids = [];
    for (let i = from; i < Math.min(from + CHUNK, USERS); i++) {
      ids.push(`k6cap-${RUN_ID}-u${i}@example.com`);
    }
    const signups = http.batch(
      ids.map((userId, k) => [
        'POST',
        `${BASE_URL}/user/signup`,
        JSON.stringify({
          userId,
          password: PASSWORD,
          name: 'k6 용량 사용자',
          idType: 'REG_NO',
          idValue: `k6cap-${RUN_ID}-${from + k}`,
        }),
        jsonParams('signup'),
      ]),
    );
    const logins = http.batch(
      ids.map((userId) => [
        'POST',
        `${BASE_URL}/user/login`,
        JSON.stringify({ userId, password: PASSWORD }),
        jsonParams('login'),
      ]),
    );
    ids.forEach((userId, k) => {
      const s = classify(signups[k], 'signup');
      const l = classify(logins[k], 'login');
      if (s.ok && l.ok && l.body.token) {
        users.push({ userId, token: l.body.token });
      }
    });
  }
  if (SCENARIO === 'list') {
    seedTransfers(users);
  }
  if (users.length === 0) {
    throw new Error('사전 사용자 생성에 모두 실패했습니다. 서버 상태와 BASE_URL을 확인하세요.');
  }
  console.log(`사전 사용자 ${users.length}/${USERS}명 준비, 총 실행 ${TOTAL_SECONDS}s, 단계 ${STEPS.join(',')}`);
  return users;
}

// 시나리오 시작 후 경과 시간으로 현재 단계를 구해 이후 모든 요청·메트릭에 step 태그를 붙인다.
function setStep() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const idx = Math.min(STEPS.length - 1, Math.max(0, Math.floor(elapsed / STEP_LEN)));
  exec.vu.metrics.tags.step = `r${STEPS[idx]}`;
}

function authIteration(users) {
  const u = users[Math.floor(Math.random() * users.length)];
  track(
    http.post(`${BASE_URL}/user/login`, JSON.stringify({ userId: u.userId, password: PASSWORD }), jsonParams('login')),
    'login',
  );
}

function transferIteration(users) {
  const token = users[Math.floor(Math.random() * users.length)].token;
  // 소액(5000~6000 KRW, 약 4 USD)이라도 사용자당 일일 한도(USD 1000)는 사용자당 약 250건이면 소진된다.
  // 총 반복 수가 USERS×약 250을 넘으면 LIMIT_EXCESS 거절이 섞여 결과가 왜곡되므로 USERS를 총 반복 수에 맞춰 키운다.
  const amount = 5000 + Math.floor(Math.random() * 1001);
  const qRes = http.post(
    `${BASE_URL}/transfer/quote`,
    JSON.stringify({ amount, targetCurrency: Math.random() < 0.7 ? 'USD' : 'JPY' }),
    jsonParams('quote', token),
  );
  const q = track(qRes, 'quote');
  const quoteId = q.ok && q.body.quote ? q.body.quote.quoteId : null;
  if (!quoteId) {
    return;
  }
  const request = (key) =>
    track(
      http.post(
        `${BASE_URL}/transfer/request`,
        JSON.stringify({ quoteId }),
        jsonParams('request', token, key ? { 'Idempotency-Key': key } : {}),
      ),
      'request',
    );
  const key = Math.random() < 0.7 ? `k6cap-${RUN_ID}-${__VU}-${__ITER}-${Math.floor(Math.random() * 1e9)}` : null;
  const r = request(key);
  if (!r.ok) {
    return;
  }
  const dice = Math.random();
  if (key && dice < 0.1) {
    request(key); // 같은 키 재시도: 200 재현
  } else if (dice < 0.2) {
    request(null); // 키 없는 재시도: 409 ALREADY_REQUESTED
  } else if (dice < 0.3) {
    track(http.get(`${BASE_URL}/transfer/list?size=20`, jsonParams('list', token)), 'list');
  }
}

// 반복마다 새 사용자를 가입시킨다. 시나리오 인스턴스 안에서 유일한 반복 번호로 충돌을 막는다.
function signupIteration() {
  const n = exec.scenario.iterationInInstance;
  track(
    http.post(
      `${BASE_URL}/user/signup`,
      JSON.stringify({
        userId: `k6cap-${RUN_ID}-s${n}@example.com`,
        password: PASSWORD,
        name: 'k6 용량 사용자',
        idType: 'REG_NO',
        idValue: `k6cap-${RUN_ID}-s${n}`,
      }),
      jsonParams('signup'),
    ),
    'signup',
  );
}

function listIteration(users) {
  const token = users[Math.floor(Math.random() * users.length)].token;
  track(http.get(`${BASE_URL}/transfer/list?size=20`, jsonParams('list', token)), 'list');
}

export default function (users) {
  setStep();
  if (SCENARIO === 'auth') {
    authIteration(users);
  } else if (SCENARIO === 'signup') {
    signupIteration();
  } else if (SCENARIO === 'list') {
    listIteration(users);
  } else {
    transferIteration(users);
  }
}
