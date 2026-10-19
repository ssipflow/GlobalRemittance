// k6 부하 테스트 공통 헬퍼.
// 책임: (1) VU별 고유 사용자 가입·로그인, (2) 견적·접수·이력 호출, (3) 응답을
// "정상 / 정의된 거절 / 정의된 503 / 예상 밖"으로 분류해 메트릭으로 집계.
// 설계 이유: 4xx(ALREADY_REQUESTED, LIMIT_EXCESS)와 정의된 503(LOCK_TIMEOUT 등)은 API 계약에
// 있는 정상 동작이라 실패로 세지 않는다. 실패는 "예상 밖 응답" 한 가지로 모아 임계값을 0건으로 건다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 사용자 이메일 접두사다. 기본값(Date.now())은 VU 초기화마다 달라져 한 실행 안에서도 VU별로 다르므로,
// 실행 단위로 사용자를 묶어 구분하려면 RUN_ID를 직접 지정한다. 유일성은 VU 번호와 순번이 보장한다.
export const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
// 예시용 비밀번호이며 실제 계정 비밀번호가 아니다. 환경변수로 바꿀 수 있다.
const PASSWORD = __ENV.LOAD_USER_PASSWORD || 'k6-load-example-pw';
// JWT 유효기간(30분)보다 짧게 잡아 장시간 실행에서도 미리 재로그인한다.
const TOKEN_TTL_MS = 25 * 60 * 1000;

// 200 외에 API 계약에 정의된 400/409/503은 전송 실패(http_req_failed)로 세지 않는다.
// 이 목록 밖(401, 500 등)만 http_req_failed가 된다. 의미 판단은 아래 classify가 한다.
http.setResponseCallback(http.expectedStatuses(200, 400, 409, 503));

// 예상 밖 응답: 계약에 없는 상태/오류 코드. 0건이어야 한다.
export const unexpected = new Counter('unexpected_responses');
// 정의된 거절(409 ALREADY_REQUESTED, 400 LIMIT_EXCESS)과 정의된 503. 태그 code로 분포를 본다.
export const rejected = new Counter('expected_rejections');
export const defined503 = new Counter('defined_503');
// 5xx 비율(정의된 503 포함). 예상 밖 500과 별도로 서버 부담을 본다.
export const serverErrorRate = new Rate('server_error_rate');
// 응답이 "건강한지": 계약에 정의된 응답(200 또는 정의된 4xx 거절)이면 true, 정의된 503이나 예상 밖 응답이면 false.
// 의도한 중복 재시도의 409가 감점되지 않게 하면서, 부하로 503/오류가 늘어난 구간을 회복 판정에서 잡는다.
export const healthyRate = new Rate('flow_healthy_rate');
// 재시도(같은 키)가 계약대로 200으로 재현되고 키 없는 재시도가 409인지 확인한 불변식 위반 수.
export const invariantViolations = new Counter('invariant_violations');

// 계약에 정의된 (HTTP 상태, resultMsg) 조합. 호출 종류별로 허용 범위가 다르다.
const ALLOWED = {
  signup: { 200: ['OK'] },
  login: { 200: ['OK'] },
  quote: { 200: ['OK'], 503: ['RATE_STALE', 'RATE_UNAVAILABLE', 'DB_UNAVAILABLE'] },
  request: {
    200: ['OK'],
    400: ['LIMIT_EXCESS'],
    409: ['ALREADY_REQUESTED'],
    503: ['LOCK_TIMEOUT', 'DB_UNAVAILABLE'],
  },
  list: { 200: ['OK'], 503: ['DB_UNAVAILABLE'] },
};

function parseBody(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

let loggedUnexpected = 0;

// 응답을 분류하고 메트릭에 기록한다. 반환값은 { status, msg, body, ok }이며 ok는 HTTP 200 + OK다.
export function classify(res, kind, tags) {
  const body = parseBody(res);
  const msg = body && body.resultMsg ? body.resultMsg : 'NO_BODY';
  const allowedMsgs = ALLOWED[kind][res.status];
  const known = allowedMsgs !== undefined && allowedMsgs.indexOf(msg) >= 0;
  const t = Object.assign({ kind }, tags);

  serverErrorRate.add(res.status >= 500, t);
  if (!known) {
    unexpected.add(1, Object.assign({ status: String(res.status), code: msg }, t));
    // 결함 재현 단서를 남기되 로그가 폭주하지 않게 VU당 3건까지만 출력한다.
    if (loggedUnexpected < 3) {
      loggedUnexpected += 1;
      console.error(`예상 밖 응답 kind=${kind} status=${res.status} resultMsg=${msg}`);
    }
  } else if (res.status === 503) {
    defined503.add(1, Object.assign({ code: msg }, t));
  } else if (res.status !== 200) {
    rejected.add(1, Object.assign({ code: msg }, t));
  }
  const ok = known && res.status === 200;
  healthyRate.add(known && res.status < 500, t);
  return { status: res.status, msg, body, ok };
}

function jsonParams(name, extra) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, (extra && extra.headers) || {});
  return { headers, tags: { name } };
}

// VU(자바스크립트 런타임)마다 모듈 변수가 따로 있으므로 사용자 상태를 VU 안에 둔다.
const state = { seq: 0, userId: null, token: null, tokenAt: 0, count: 0 };

function newUserId() {
  state.seq += 1;
  // 이메일 형식을 지키며 실행 ID·VU·순번으로 고유하게 만든다.
  return `k6load-${RUN_ID}-vu${__VU}-u${state.seq}@example.com`;
}

export function signup(userId) {
  const body = JSON.stringify({
    userId,
    password: PASSWORD,
    name: 'k6 부하 사용자',
    idType: 'REG_NO',
    idValue: `k6-${RUN_ID}-${__VU}-${state.seq}`,
  });
  return classify(http.post(`${BASE_URL}/user/signup`, body, jsonParams('signup')), 'signup');
}

export function login(userId) {
  const body = JSON.stringify({ userId, password: PASSWORD });
  const r = classify(http.post(`${BASE_URL}/user/login`, body, jsonParams('login')), 'login');
  return r.ok && r.body.token ? r.body.token : null;
}

// 현재 VU의 사용자를 보장한다. 일일 한도(개인 1000 USD)에 닿으면 새 사용자로 교체한다.
export function ensureSession() {
  const now = Date.now();
  if (state.userId && state.token && now - state.tokenAt > TOKEN_TTL_MS) {
    state.token = login(state.userId);
    state.tokenAt = now;
  }
  if (!state.userId || !state.token) {
    const userId = newUserId();
    if (signup(userId).ok) {
      const token = login(userId);
      if (token) {
        state.userId = userId;
        state.token = token;
        state.tokenAt = now;
        state.count = 0;
      }
    }
  }
  return state.token;
}

// 한도에 닿았을 때 다음 반복에서 새 사용자를 쓰도록 세션을 버린다.
export function rotateUser() {
  state.userId = null;
  state.token = null;
}

function authParams(token, name, extraHeaders) {
  return jsonParams(name, { headers: Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders || {}) });
}

export function quote(token, amount) {
  const body = JSON.stringify({ amount, targetCurrency: Math.random() < 0.7 ? 'USD' : 'JPY' });
  const r = classify(http.post(`${BASE_URL}/transfer/quote`, body, authParams(token, 'quote')), 'quote');
  r.quoteId = r.ok && r.body.quote ? r.body.quote.quoteId : null;
  return r;
}

export function requestTransfer(token, quoteId, idempotencyKey) {
  const extra = idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {};
  const res = http.post(`${BASE_URL}/transfer/request`, JSON.stringify({ quoteId }), authParams(token, 'request', extra));
  return classify(res, 'request');
}

export function list(token) {
  return classify(http.get(`${BASE_URL}/transfer/list?size=20`, authParams(token, 'list')), 'list');
}

// 소액(1만~3만 KRW)만 보내 일일 한도에 닿을 때까지 한 사용자로 접수하고, LIMIT_EXCESS가 나오면 새 사용자로 교체한다.
export function smallAmount() {
  return 10000 + Math.floor(Math.random() * 20001);
}

// 한 번의 반복: 견적 -> 접수(대부분 멱등성 키 포함) -> 가끔 재시도·이력 조회.
// 재시도 검증: 같은 키 재시도는 200(재현), 키 없는 재시도는 409 ALREADY_REQUESTED여야 한다.
export function transferIteration() {
  const token = ensureSession();
  if (!token) {
    return;
  }
  const q = quote(token, smallAmount());
  if (!q.quoteId) {
    return;
  }
  const useKey = Math.random() < 0.7;
  const key = useKey ? `k6-${RUN_ID}-${__VU}-${__ITER}-${Math.floor(Math.random() * 1e9)}` : null;
  const r = requestTransfer(token, q.quoteId, key);
  if (r.msg === 'LIMIT_EXCESS') {
    rotateUser();
    return;
  }
  if (r.ok) {
    const dice = Math.random();
    if (key && dice < 0.1) {
      // 같은 키 재시도: 이미 접수된 견적이어도 200으로 재현되어야 한다.
      const again = requestTransfer(token, q.quoteId, key);
      if (again.status !== 200 && again.status !== 503) {
        invariantViolations.add(1);
      }
    } else if (dice < 0.2) {
      // 키 없는(또는 다른) 재시도: 이미 접수된 견적은 409 ALREADY_REQUESTED여야 한다.
      const dup = requestTransfer(token, q.quoteId, null);
      if (dup.status !== 409 && dup.status !== 503) {
        invariantViolations.add(1);
      }
    } else if (dice < 0.3) {
      list(token);
    }
  }
}

// 이후 이 VU가 보내는 모든 요청과 커스텀 메트릭에 phase 태그를 붙인다(구간별 임계값용).
export function setPhase(phase) {
  exec.vu.metrics.tags.phase = phase;
}

// "30s", "5m", "1h" 형태를 초로 바꾼다(soak/spike 단계 계산용).
export function toSeconds(text) {
  const m = /^(\d+)(s|m|h)$/.exec(text);
  if (!m) {
    throw new Error(`지원하지 않는 시간 형식: ${text} (예: 90s, 5m, 1h)`);
  }
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}
