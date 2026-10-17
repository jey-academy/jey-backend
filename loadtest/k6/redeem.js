// 동탄고 패스 사용 처리의 부하 시험. 중복 사용 방지 방식(잠금 방식)마다 같은 부하를 걸어 정확성과 응답 시간을 잰다.
//
// 앞에서 실험용 앱을 띄운다: ./gradlew loadtestRun -Plocking=optimistic   (pessimistic, unique-first, redis)
// 그다음: k6 run -e LOCKING=optimistic -e SCENARIO=contention -e N=8 --summary-export=build/k6/optimistic-contention-N8.json loadtest/k6/redeem.js
//
// 시나리오(SCENARIO)
// - contention: 반복마다 패스·학생 한 쌍에 사용 처리 N개를 동시에 보낸다(두 번 누름, 데스크 두 곳의 극단). 성공은 정확히 1개여야 한다
// - spread:     반복마다 한 쌍에 1개(경합 없음). 방식의 기본 비용
// - mixed:      반복마다 사용 처리 N/2개와 같은 패스의 링크 새로 만들기 N/2개를 동시에(버전 충돌 경로). 성공은 많아야 1개
//
// 준비(setup): 연계 학원 계정으로 패스를, 직원 계정으로 같은 이름·학부모 번호의 학생을 API로 만든다(반복 수만큼).
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const LOCKING = __ENV.LOCKING || 'unknown';
const SCENARIO = __ENV.SCENARIO || 'contention';
const N = Number(__ENV.N || (SCENARIO === 'spread' ? 1 : 8));
const PAIRS = Number(__ENV.PAIRS || 300);
const VUS = Number(__ENV.VUS || (SCENARIO === 'spread' ? 32 : 10));
const PASSWORD = __ENV.PASSWORD || 'loadtest-only-password-1';

if (!['contention', 'spread', 'mixed'].includes(SCENARIO)) {
  throw new Error(`SCENARIO는 contention, spread, mixed 중 하나: ${SCENARIO}`);
}

export const options = {
  scenarios: {
    redeem: { executor: 'shared-iterations', vus: VUS, iterations: PAIRS, maxDuration: '10m' },
  },
  setupTimeout: '10m',
  // 한 반복의 요청 N개를 정말 동시에 보낸다(기본값은 호스트마다 6개까지만 동시).
  batch: 64,
  batchPerHost: 64,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { locking: LOCKING, scenario: SCENARIO },
  thresholds: {
    // 이 둘이 깨지면 k6가 실패로 끝난다(✗). 방식이 틀렸다는 뜻이다.
    redeem_5xx: ['count==0'],
    redeem_correct: ['rate==1'],
  },
};

const redeemMs = new Trend('redeem_ms', true);
const correct = new Rate('redeem_correct');
const ok = new Counter('redeem_200');
const alreadyRedeemed = new Counter('redeem_409_already_redeemed');
const studentNotNew = new Counter('redeem_409_student_not_new');
const changedConcurrently = new Counter('redeem_409_changed_concurrently');
const otherClientError = new Counter('redeem_4xx_other');
const serverError = new Counter('redeem_5xx');
const renewOk = new Counter('renew_200');
const renewRejected = new Counter('renew_4xx');

export function setup() {
  const partner = login('loadtest-partner');
  const staff = login('loadtest-staff');
  const notice = http.get(`${BASE}/api/v1/dongtanpass/consent-notice`, partner.params());
  must(notice, 200, '고지 문구');
  const noticeVersion = notice.json('version');
  const today = new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const run = Date.now().toString(36);

  const pairs = [];
  for (let start = 0; start < PAIRS; start += 20) {
    const batch = [];
    for (let i = start; i < Math.min(start + 20, PAIRS); i++) {
      batch.push({ name: `부하${run}${i}`, phone: `0109${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}` });
    }
    const issued = http.batch(batch.map((p) => ['POST', `${BASE}/api/v1/dongtanpass/passes`, JSON.stringify({
      studentName: p.name, guardianPhone: p.phone, paidOn: today, paidAmount: 150000, paymentMethod: 'CARD',
      consentConfirmed: true, noticeVersion,
    }), partner.params(true)]));
    const registered = http.batch(batch.map((p) => ['POST', `${BASE}/api/v1/students`, JSON.stringify({
      campusId: 1, name: p.name, grade: 'HIGH_1', guardians: [{ name: '보호자', relation: 'MOTHER', phone: p.phone }],
    }), staff.params(true)]));
    batch.forEach((p, k) => {
      must(issued[k], 201, '패스 발급');
      must(registered[k], 201, '학생 등록');
      pairs.push({ passId: issued[k].json('id'), studentId: registered[k].json('id') });
    });
  }
  return { pairs, staff: staff.state(), partner: partner.state() };
}

export default function (data) {
  const pair = data.pairs[exec.scenario.iterationInTest];
  const staff = Session.from(data.staff);
  const partner = Session.from(data.partner);
  const redeemCount = SCENARIO === 'mixed' ? Math.floor(N / 2) : N;
  const renewCount = SCENARIO === 'mixed' ? N - redeemCount : 0;

  const requests = [];
  for (let i = 0; i < redeemCount; i++) {
    requests.push(['POST', `${BASE}/api/v1/dongtanpass/passes/${pair.passId}/redeem`,
      JSON.stringify({ studentId: pair.studentId }), staff.params(true, { name: 'redeem' })]);
  }
  for (let i = 0; i < renewCount; i++) {
    requests.push(['POST', `${BASE}/api/v1/dongtanpass/passes/${pair.passId}/link`, null,
      partner.params(true, { name: 'renew' })]);
  }
  const responses = http.batch(requests);

  let successes = 0;
  let failures5xx = 0;
  responses.slice(0, redeemCount).forEach((res) => {
    redeemMs.add(res.timings.duration);
    if (res.status === 200) {
      successes++;
      ok.add(1);
    } else if (res.status >= 500 || res.status === 0) {
      failures5xx++;
      serverError.add(1);
    } else {
      countRejection(res);
    }
  });
  responses.slice(redeemCount).forEach((res) => (res.status === 200 ? renewOk : renewRejected).add(1));

  // contention·spread는 정확히 1개, mixed는 많아야 1개(링크 새로 만들기에 모두 지면 0개 — 다시 시도하면 된다).
  const expected = SCENARIO === 'mixed' ? successes <= 1 : successes === 1;
  correct.add(expected && failures5xx === 0);
  check(null, {
    '성공이 정해진 수(중복 사용 없음)': () => expected,
    '5xx 없음': () => failures5xx === 0,
  });
}

function countRejection(res) {
  const code = codeOf(res);
  if (code === 'DONGTANPASS_PASS_ALREADY_REDEEMED') alreadyRedeemed.add(1);
  else if (code === 'DONGTANPASS_STUDENT_NOT_NEW') studentNotNew.add(1);
  else if (code === 'DONGTANPASS_PASS_CHANGED_CONCURRENTLY') changedConcurrently.add(1);
  else otherClientError.add(1);
}

function codeOf(res) {
  try {
    return res.json('code');
  } catch (e) {
    return null;
  }
}

// 로그인한 세션 하나. 쿠키(세션, CSRF)를 직접 들고 다닌다(가상 사용자마다의 쿠키 저장소를 쓰지 않는다 — 두 계정을 함께 쓴다).
class Session {
  constructor(cookies) {
    this.cookies = cookies;
  }

  static from(state) {
    return new Session(state);
  }

  state() {
    return this.cookies;
  }

  params(idempotent = false, tags = {}) {
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': this.cookies['XSRF-TOKEN'] };
    if (idempotent) headers['Idempotency-Key'] = uuid();
    return { headers, cookies: this.cookies, jar: new http.CookieJar(), tags, responseCallback: http.expectedStatuses({ min: 200, max: 499 }) };
  }
}

function login(loginId) {
  const jar = new http.CookieJar();
  must(http.get(`${BASE}/api/v1/auth/csrf`, { jar }), 204, 'CSRF 토큰');
  const csrf = jar.cookiesForURL(BASE)['XSRF-TOKEN'][0];
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ loginId, password: PASSWORD }), {
    jar, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
  });
  must(res, 200, `${loginId} 로그인`);
  const cookies = {};
  for (const [name, values] of Object.entries(jar.cookiesForURL(BASE))) cookies[name] = values[0];
  return new Session(cookies);
}

function must(res, status, what) {
  if (res.status !== status) {
    throw new Error(`${what} 실패: ${res.status} ${res.body}`);
  }
}

function uuid() {
  const hex = [...Array(32)].map(() => Math.floor(Math.random() * 16).toString(16)).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20)}`;
}
