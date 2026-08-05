#!/usr/bin/env node
/**
 * imlate 부하 테스트 — "등록 마감(기본 21:45) 직전 몰림" 시뮬레이션
 *
 *   교육생 약 200명이 마감 직전에 동시에 몰릴 때
 *     · 전원이 정상적으로 등록되는가 (유실 0건)
 *     · **기숙사 공용 와이파이(NAT) 뒤에서 전원이 같은 IP 로 보여도** 막히지 않는가   ← §1B
 *     · WAL(Redis) ↔ DB 이중 기록이 부하 상황에서도 어긋나지 않는가 (R7)
 *     · 같은 사람이 동시에 여러 번 눌러도 DB 에는 1건만 남는가 (멱등성)
 *     · rate limiter 가 **정상 사용자는 막지 않으면서** 도배·폭주만 차단하는가 (R14)
 *     · 응답시간(p50/p90/p99)과 처리량이 감당 가능한 수준인가
 *   를 실제 기동 중인 앱 + 실제 MySQL/Redis 를 상대로 측정하고 검증한다.
 *
 *   ※ scripts/integration-test.mjs 는 "기능이 계약대로 동작하는가"를 보고,
 *     이 스크립트는 "동시에 몰렸을 때 버티는가"를 본다. 검증 범위가 겹치지 않는다.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * ★ 이 스크립트가 놓쳤던 결함 (반드시 읽을 것)
 *
 *   예전 기본 시나리오는 요청마다 서로 다른 X-Forwarded-For(10.77.x.x)를 붙여
 *   200명을 **서로 다른 IP** 로 흉내 냈다. 그래서 "200명 동시 등록 전원 성공"이 나왔다.
 *   그러나 실제 운영은 **전원이 기숙사 공용 와이파이 = 공인 IP 하나**를 공유한다.
 *
 *     → **부하 테스트의 기본 시나리오가 실제 사용 환경과 정반대였다.**
 *
 *   그 결과 "register 는 IP당 8회/분" 이라는 한도 때문에 마감 직전 9번째 학생부터
 *   429 로 막히는, 운영이 불가능한 결함을 이 부하 테스트가 그대로 통과시켰다.
 *   `--same-ip` 옵션이 있긴 했지만 "공격 시나리오"로만 취급되어 기본 실행에서 빠져 있었다.
 *
 *   이제 §1B "공용 와이파이" 시나리오가 **기본 실행에 항상 포함**되며,
 *   전원 201 / 429 0건 / DB 200건을 단언한다. 수정 전 코드로는 반드시 실패한다.
 * ────────────────────────────────────────────────────────────────────────────
 *
 * 시나리오 구성
 *   §1   IP 분산 기준선   — 200명이 서로 다른 IP (비현실적. XFF 를 신뢰하는 설정에서만 수행)
 *   §1B  공용 와이파이     — 200명이 **모두 같은 IP**, 서로 다른 사람  → 전원 201, 429 0건
 *   §2   동시 등록 경합    — 같은 사람 동시 연타                        → DB 1건(멱등)
 *   §3A  같은 사람 도배    — 같은 IP · **같은 사람** 반복               → 429 (개인 버킷)
 *   §3B  옆자리 사용자     — 도배 차단 중 같은 IP · **다른 사람**       → 201 (막히지 않는다)
 *   §3C  단일 회선 폭주    — 한 IP 에서 global 한도를 넘는 대량 요청    → 429 (IP 버킷 = DDoS 방어)
 *   §4   부하 후 정합성    — WAL ↔ DB
 *   §5   명단 렌더링       — 사감 발송(기본 21:50) 준비 비용
 *
 * 사용법
 *   node scripts/load-test.mjs                          # 기본: 위 시나리오 전부
 *   node scripts/load-test.mjs --students 200 --concurrency 20
 *   node scripts/load-test.mjs --same-ip                # §1(IP 분산 기준선)을 건너뛴다
 *   node scripts/load-test.mjs --keep                   # 끝나고 데이터를 지우지 않음
 *   node scripts/load-test.mjs --help
 *
 * 옵션
 *   --base-url <URL>        기본 http://localhost:8080
 *   --admin-key <KEY>       기본 local-dev-admin-key (명단 렌더링 점검에만 사용)
 *   --students <N>          §1 기준선 인원. 기본 200
 *   --wifi-students <N>     §1B 공용 와이파이 인원. 기본 --students 와 동일
 *   --concurrency <N>       동시 실행 워커 수. 기본 20
 *   --race <N>              같은 사람을 동시에 등록해 보는 요청 수. 기본 20
 *   --spam <N>              §3A 같은 사람 도배 최대 시도 수(429 가 나오면 조기 종료). 기본 40
 *   --flood <N>             §3C 단일 회선 폭주 요청 수. 기본 global 한도 × 1.8 + 40 (상한 3000)
 *   --register-capacity <N> register(IP) 한도. **기본은 기동 중인 앱의 설정을 읽어 온다**
 *   --global-capacity <N>   global(IP) 한도. 위와 동일
 *   --person-capacity <N>   register(개인) 한도. 위와 동일
 *   --p99-budget <MS>       p99 응답시간 경고 임계값. 기본 2000
 *   --same-ip               §1 IP 분산 기준선을 건너뛴다(단일 IP 집중 점검 모드)
 *   --mysql <컨테이너명>     기본 imlate-mysql
 *   --redis <컨테이너명>     기본 imlate-redis
 *   --db-user/--db-pass/--db-name   기본 imlate/imlate/imlate
 *   --keep                  끝나고 테스트 데이터를 지우지 않음
 *
 * 한도값은 하드코딩하지 않는다
 *   `/actuator/env` (local 프로파일이 열어 둔다) 로 **기동 중인 앱의 실제 설정값**을 읽어
 *   단언을 맞춘다. 읽지 못하면 위 `--*-capacity` 인자(또는 그 기본값)를 쓴다.
 *   → 백엔드가 한도를 조정해도 이 스크립트를 고칠 필요가 없다.
 *
 * X-Forwarded-For 와 클라이언트 IP (중요 — 예전 설명이 틀렸다)
 *   `imlate.rate-limit.trusted-proxies` 가 **비어 있으면 XFF 를 신뢰하지 않는다**.
 *   로컬 기본값이 비어 있으므로 이 스크립트가 붙이는 10.x 주소는 **무시되고**,
 *   전원이 같은 클라이언트(= remoteAddr 하나)로 취급된다. 그것이 곧 실제 운영 환경이다.
 *   스크립트는 사전 점검에서 **XFF 가 실제로 반영되는지 Redis 버킷 키로 직접 확인**하고,
 *   반영되지 않으면 §1(IP 분산 기준선)을 건너뛴다. 헤더를 믿고 넘어가지 않는다.
 *
 * 주의
 *   - 이 스크립트는 자기가 만든 데이터(호수 LT* )를 지웁니다. **로컬 전용**이며
 *     base-url 이 localhost/127.0.0.1 이 아니면 실행을 거부합니다.
 *   - DB 는 room_number LIKE 'LT%' 만, WAL 은 자기 항목만 지웁니다.
 *   - rate limit 버킷(`imlate:rl:*`)은 **전부** 비웁니다. 버킷 키에 개인 식별자 해시가
 *     섞이면서 "내 것만" 고르는 패턴이 불안정해졌고, 어차피 TTL 이 1분짜리 임시 카운터라
 *     로컬에서 전부 지우는 편이 안전합니다.
 */
import { execFileSync } from 'node:child_process'

// ── 인자 파싱 ────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback
}
const intArg = (name, fallback) => {
  const v = Number.parseInt(arg(name, String(fallback)), 10)
  return Number.isFinite(v) && v > 0 ? v : fallback
}
const hasArg = (name) => argv.indexOf(`--${name}`) >= 0
const flag = (name) => argv.includes(`--${name}`)

if (flag('help') || flag('h')) {
  // docs/LOCAL-TESTING.md 가 "옵션은 스크립트에게 직접 물어라"라고 안내하므로 반드시 응답한다.
  console.log(`
imlate 부하 테스트 — 마감 직전 몰림 시뮬레이션

  node scripts/load-test.mjs                  기본: 아래 시나리오 전부
  node scripts/load-test.mjs --same-ip        §1(IP 분산 기준선)을 건너뛴다
  node scripts/load-test.mjs --keep           끝나고 데이터를 지우지 않는다

시나리오
  §1   IP 분산 기준선   200명이 서로 다른 IP (비현실적. XFF 를 신뢰하는 설정에서만 수행)
  §1B  ★공용 와이파이   200명이 **모두 같은 IP**, 서로 다른 사람  → 전원 201, 429 0건
  §2   동시 등록 경합    같은 사람 동시 연타                       → DB 1건(멱등)
  §3A  같은 사람 도배    같은 IP · **같은 사람** 반복              → 429 (개인 버킷)
  §3B  옆자리 사용자     도배 차단 중 같은 IP · **다른 사람**      → 201 (막히지 않는다)
  §3C  단일 회선 폭주    한 IP 에서 global 한도 초과               → 429 (IP 버킷 = DDoS 방어)
  §4   부하 후 정합성    WAL ↔ DB
  §5   명단 렌더링       사감 발송(기본 21:50) 준비 비용

옵션
  --base-url <URL>         기본 http://localhost:8080
  --admin-key <KEY>        기본 local-dev-admin-key
  --students <N>           §1 기준선 인원 (기본 200)
  --wifi-students <N>      §1B 공용 와이파이 인원 (기본 --students 와 동일)
  --concurrency <N>        동시 실행 워커 수 (기본 20)
  --race <N>               §2 같은 사람 동시 요청 수 (기본 20)
  --spam <N>               §3A 도배 최대 시도 수, 429 나오면 조기 종료 (기본 40)
  --flood <N>              §3C 폭주 요청 수 (기본 global 한도 x1.8 + 40, 상한 3000)
  --register-capacity <N>  register(IP) 한도 — 기본은 기동 중인 앱 설정을 읽어 온다
  --global-capacity <N>    global(IP) 한도 — 위와 동일
  --person-capacity <N>    register(개인) 한도 — 위와 동일
  --p99-budget <MS>        p99 경고 임계값 (기본 2000)
  --same-ip                §1 기준선 생략 (단일 IP 집중 점검)
  --mysql/--redis <이름>    컨테이너 이름 (기본 imlate-mysql / imlate-redis)
  --db-user/--db-pass/--db-name   기본 imlate/imlate/imlate
  --keep                   테스트 데이터를 남긴다
  --help                   이 도움말

참고
  · 한도값은 하드코딩하지 않는다. /actuator/env 로 앱의 실제 설정을 읽어 단언을 맞춘다.
  · trusted-proxies 가 비어 있으면 X-Forwarded-For 는 무시된다(로컬 기본값).
    그 경우 §1 은 자동으로 건너뛴다 — 위조 헤더로 IP 를 나눌 수 없기 때문이다.
  · 로컬 전용. base-url 이 localhost/127.0.0.1 이 아니면 실행을 거부한다.
  · 자세한 배경은 이 파일 상단 주석과 docs/LOCAL-TESTING.md §2.5 를 본다.
`)
  process.exit(0)
}

const BASE = arg('base-url', 'http://localhost:8080').replace(/\/$/, '')
const API = `${BASE}/api/v1`
const ADMIN_KEY = arg('admin-key', 'local-dev-admin-key')
const STUDENTS = intArg('students', 200)
const WIFI_STUDENTS = intArg('wifi-students', STUDENTS)
const CONCURRENCY = intArg('concurrency', 20)
const RACE_REQUESTS = intArg('race', 20)
const SPAM_MAX = intArg('spam', 40)
const P99_BUDGET_MS = intArg('p99-budget', 2000)
const SKIP_BASELINE = flag('same-ip')
const MYSQL_CONTAINER = arg('mysql', 'imlate-mysql')
const REDIS_CONTAINER = arg('redis', 'imlate-redis')
const DB_USER = arg('db-user', 'imlate')
const DB_PASS = arg('db-pass', 'imlate')
const DB_NAME = arg('db-name', 'imlate')
const KEEP_DATA = flag('keep')

// 한도값: 인자로 주면 그 값을 강제하고, 안 주면 사전 점검에서 앱 설정을 읽어 채운다.
// (아래 기본값은 "앱에 물어보지도 못했을 때"의 최후 폴백이며 SPEC §8 의 기본값과 같다)
let REGISTER_CAPACITY = intArg('register-capacity', 300)
let GLOBAL_CAPACITY = intArg('global-capacity', 1200)
let PERSON_CAPACITY = intArg('person-capacity', 5)
const REGISTER_CAPACITY_FIXED = hasArg('register-capacity')
const GLOBAL_CAPACITY_FIXED = hasArg('global-capacity')
const PERSON_CAPACITY_FIXED = hasArg('person-capacity')
/**
 * 각 한도값이 "믿을 수 있는 출처"에서 왔는가.
 * 앱에서 읽었거나 사람이 인자로 준 값만 신뢰한다. 폴백 기본값에 기대어
 * 단언을 실패시키면 **없는 결함을 만들어 내게 되므로**, 그 경우는 경고로 낮춘다.
 */
const CAP_TRUSTED = {
  global: GLOBAL_CAPACITY_FIXED,
  register: REGISTER_CAPACITY_FIXED,
  person: PERSON_CAPACITY_FIXED,
}
/** 신뢰할 수 있는 한도값에서 나온 단언만 실패로 처리하고, 아니면 경고로 남긴다. */
const checkIfTrusted = (kind, name, cond, detail = '') => {
  if (CAP_TRUSTED[kind]) check(name, cond, detail)
  else if (!cond) warn(`${name} (한도값 출처가 불확실해 경고로 낮춤)`, detail)
  else info(`(참고) ${name} — 한도값 출처가 불확실해 참고로만 표시`)
}
let FLOOD_REQUESTS = hasArg('flood') ? intArg('flood', 0) : 0 // 0 = 사전 점검에서 계산

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE)) {
  console.error(`거부: 이 스크립트는 데이터를 삭제하므로 로컬에서만 실행할 수 있습니다. (base-url=${BASE})`)
  process.exit(2)
}

// ── 테스트 데이터 규약 ───────────────────────────────────────────────────────
//   모든 호수를 'LT' 로 시작시켜 이 스크립트가 만든 행만 정확히 골라내고 정리한다.
const ROOM_LOAD = (i) => `LTS${String(i + 1).padStart(4, '0')}`  // §1  IP 분산 기준선
const ROOM_WIFI = (i) => `LTW${String(i + 1).padStart(4, '0')}`  // §1B 공용 와이파이 200명
const ROOM_RACE = 'LTR001'                                       // §2  동시 등록 경합(같은 사람)
const ROOM_SPAM = 'LTP001'                                       // §3A 같은 사람 도배
const ROOM_NEIGHBOR = 'LTN001'                                   // §3B 옆자리 사용자(도배범과 같은 IP)
const ROOM_OTHER_LINE = 'LTN002'                                 // §3C 폭주 회선과 다른 회선의 사용자
const ROOM_FLOOD = 'LTF001'                                      // §3C 폭주 중 등록 시도
const ROOM_CANARY = 'LTC001'                                     // 사전 점검용 카나리아
const CLASSES = ['1반', '2반', '3반', '4반', '5반', '6반', '7반', '8반']

/** 부하 발생용 가짜 클라이언트 IP. 전부 10.* 대역이라 로그에서 구분하기 쉽다. */
const WIFI_IP = '10.77.255.1'   // §1B 공용 와이파이 — 전원이 공유하는 하나의 공인 IP
const LOAD_IP = (i) => `10.77.${Math.floor(i / 250)}.${(i % 250) + 1}` // §1 서로 다른 IP
const RACE_IP = (i) => `10.88.${Math.floor(i / 250)}.${(i % 250) + 1}`
const SPAM_IP = '10.66.66.66'   // §3A/§3B 도배범과 옆자리 사용자가 공유하는 IP
const FLOOD_IP = '10.66.99.99'  // §3C 폭주 회선
const CLEAN_IP = '10.55.55.55'  // §3C 이후 "다른 회선은 멀쩡한가" 확인용
const CONTROL_IP = '10.44.44.44' // 스크립트 자신의 점검용 요청

// ── 결과 집계 ────────────────────────────────────────────────────────────────
let pass = 0
let fail = 0
const failures = []
const warnings = []
/** 시나리오별 요청 수·소요를 요약에 그대로 찍기 위해 모아 둔다. */
const scenarios = []

const check = (name, cond, detail = '') => {
  if (cond) {
    pass++
    console.log(`  [32mPASS[0m  ${name}`)
  } else {
    fail++
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`)
    console.log(`  [31mFAIL[0m  ${name}${detail ? ` — ${detail}` : ''}`)
  }
}
const warn = (name, detail = '') => {
  warnings.push(`${name}${detail ? ` — ${detail}` : ''}`)
  console.log(`  [33mWARN[0m  ${name}${detail ? ` — ${detail}` : ''}`)
}
const section = (t) => console.log(`\n[36m═══ ${t} [0m`)
const info = (t) => console.log(`        ${t}`)

// ── HTTP ─────────────────────────────────────────────────────────────────────
/**
 * 한 번의 HTTP 호출. 응답시간(ms)을 함께 돌려준다.
 * ip 를 주면 X-Forwarded-For 로 붙인다(신뢰 프록시 설정에 따라 무시될 수 있다 — 위 주석 참고).
 * visitor 를 주면 통계용 방문자 식별자를 개인별로 다르게 만든다.
 */
async function call(method, path, { body, ip, visitor, headers = {} } = {}) {
  const h = {
    'Content-Type': 'application/json',
    'X-Visitor-Id': visitor ?? `load-${ip ?? 'control'}`,
    ...(ip ? { 'X-Forwarded-For': ip } : {}),
    ...headers,
  }
  const started = performance.now()
  try {
    const res = await fetch(`${API}${path}`, {
      method,
      headers: h,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    })
    const text = await res.text()
    const ms = performance.now() - started
    let json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      /* 비-JSON 응답 */
    }
    return {
      status: res.status, json, text, ms, headers: res.headers,
      limit: res.headers.get('x-ratelimit-limit'),
      retryAfter: res.headers.get('retry-after'),
    }
  } catch (e) {
    // 연결 실패/타임아웃도 지표로 남긴다(status=0).
    return { status: 0, json: null, text: String(e?.message ?? e), ms: performance.now() - started, limit: null, retryAfter: null }
  }
}

/** 기동 중인 앱의 **실제** 설정값을 읽는다. local 프로파일이 env 엔드포인트를 열어 둔다. */
/**
 * 응답 헤더 X-RateLimit-Limit 로 **실제 적용된** 한도를 읽는다.
 *
 * /actuator/env 는 값을 "******" 로 마스킹하므로(show-values 기본 NEVER) 쓸 수 없고,
 * 마스킹을 풀면 DB 비밀번호까지 노출된다. 헤더는 런타임 결정값이라 더 정확하고 부작용이 없다.
 *
 * 주의: 등록 경로는 IP 축과 개인 축을 모두 검사하므로 헤더에는 **더 빡빡한 쪽**이 실린다.
 *       (기본 설정에서는 개인 10 < IP 300 이므로 개인 한도가 보인다)
 */
async function limitFromHeader(path, { method = 'GET', body, headers = {} } = {}) {
  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...headers },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(5000),
    })
    const raw = res.headers.get('x-ratelimit-limit')
    const v = Number(raw)
    return Number.isFinite(v) && v > 0 ? v : null
  } catch {
    return null
  }
}

async function envNumber(...names) {
  for (const name of names) {
    try {
      const res = await fetch(`${BASE}/actuator/env/${encodeURIComponent(name)}`, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) continue
      const json = await res.json()
      const n = Number(json?.property?.value)
      if (Number.isFinite(n) && n > 0) return { value: n, source: name }
    } catch {
      /* actuator 가 닫혀 있으면 폴백 */
    }
  }
  return null
}

const registerBody = (i) => ({
  className: CLASSES[i % CLASSES.length],
  studentName: `부하생${String(i + 1).padStart(4, '0')}`,
  roomNumber: ROOM_LOAD(i),
})

const wifiBody = (i) => ({
  className: CLASSES[i % CLASSES.length],
  studentName: `와이파이생${String(i + 1).padStart(4, '0')}`,
  roomNumber: ROOM_WIFI(i),
})

// ── 인프라 헬퍼 (integration-test.mjs 와 동일한 방식) ─────────────────────────
const docker = (container, args) =>
  execFileSync('docker', ['exec', container, ...args],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 }).trim()

const mysql = (sql) =>
  docker(MYSQL_CONTAINER, ['mysql', `-u${DB_USER}`, `-p${DB_PASS}`, '-D', DB_NAME,
    '--default-character-set=utf8mb4', '-N', '-B', '-e', sql])

const redis = (...args) => docker(REDIS_CONTAINER, ['redis-cli', ...args])

const dbCount = (where) => Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE ${where}`))

/** 이 스크립트가 만든 WAL 항목만 골라 [{field, entry}] 로 돌려준다. */
function walLoadEntries(date) {
  const raw = redis('hgetall', `imlate:wal:${date}`)
  if (!raw) return []
  const lines = raw.split('\n')
  const out = []
  for (let i = 0; i + 1 < lines.length; i += 2) {
    const field = lines[i].trim()
    const value = lines[i + 1]
    if (!value || !value.includes('"roomNumber":"LT')) continue
    try {
      out.push({ field, entry: JSON.parse(value) })
    } catch {
      out.push({ field, entry: null })
    }
  }
  return out
}

/** 키를 100개씩 끊어 지운다(윈도우 커맨드라인 길이 제한 회피). */
function redisDelKeys(keys) {
  for (let i = 0; i < keys.length; i += 100) {
    const chunk = keys.slice(i, i + 100).filter(Boolean)
    if (chunk.length) redis('del', ...chunk)
  }
  return keys.length
}

const rateLimitKeys = () =>
  redis('--scan', '--pattern', 'imlate:rl:*').split('\n').map((k) => k.trim()).filter(Boolean)

/**
 * rate limit 버킷을 전부 비운다.
 *
 * 예전에는 `imlate:rl:*:10.*` 만 지웠지만, 버킷이 **IP + 개인식별자 해시** 2단으로 바뀌면서
 * 개인 버킷 키에는 10.* 이 들어가지 않는다. 남은 개인 버킷이 다음 시나리오를 오염시키므로
 * 로컬 전용인 이 스크립트에서는 전부 지운다(TTL 1분짜리 임시 카운터라 손실이 없다).
 */
function clearRateLimits() {
  return redisDelKeys(rateLimitKeys())
}

// ── 지표 ─────────────────────────────────────────────────────────────────────
const newMetrics = (label) =>
  ({ label, samples: [], status: new Map(), blockedBy: new Map(), retryAfterCount: 0, rateLimitedCode: 0, elapsedMs: 0 })

function record(m, res) {
  m.samples.push(res.ms)
  m.status.set(res.status, (m.status.get(res.status) ?? 0) + 1)
  if (res.status === 429) {
    // X-RateLimit-Limit 값으로 어떤 규칙이 막았는지 구분한다.
    const key = res.limit ?? '?'
    m.blockedBy.set(key, (m.blockedBy.get(key) ?? 0) + 1)
    if (res.retryAfter) m.retryAfterCount++
    if (res.json?.code === 'RATE_LIMITED') m.rateLimitedCode++
  }
}

const cnt = (m, status) => m.status.get(status) ?? 0
const total = (m) => m.samples.length
const sum5xx = (m) => [...m.status.entries()].filter(([s]) => s >= 500 || s === 0).reduce((a, [, v]) => a + v, 0)
/** 등록이 실제로 받아들여진 건수(201 신규 + 200 중복). rate limit 통과 여부 판정에 쓴다. */
const accepted = (m) => cnt(m, 201) + cnt(m, 200)

/** 경과 시간 동안 리필된 토큰 수(부분 리필 허용 범위 계산용). */
const refilledDuring = (elapsedMs, capacityPerMinute) => Math.floor((elapsedMs / 1000) * (capacityPerMinute / 60))

/** 최근접 순위(nearest-rank) 백분위수. */
function percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
  return sorted[idx]
}

const STATUS_LABEL = {
  0: '연결실패/타임아웃', 200: '200 중복(멱등)', 201: '201 신규등록', 400: '400 검증실패',
  409: '409 등록마감', 429: '429 rate limit 차단', 500: '500 서버오류', 502: '502 오류', 503: '503 오류',
}

/** X-RateLimit-Limit 숫자를 사람이 읽을 수 있는 규칙 이름으로 바꾼다(설정에서 읽은 값 기준). */
function ruleLabel(limitHeader) {
  const n = Number(limitHeader)
  const names = []
  if (n === GLOBAL_CAPACITY) names.push('global(IP)')
  if (n === REGISTER_CAPACITY) names.push('register(IP)')
  if (n === PERSON_CAPACITY) names.push('register(개인)')
  if (!names.length) return `limit=${limitHeader}`
  return names.join('|') // 한도값이 겹치면 둘 다 보여 준다(추측하지 않는다)
}

function printMetrics(m) {
  const n = total(m)
  console.log(`\n        ── ${m.label} ─────────────────────────────`)
  if (!n) {
    info('요청 없음')
    return
  }
  const secs = m.elapsedMs / 1000
  info(`총 요청       ${n}건 / ${secs.toFixed(2)}초 / ${(n / Math.max(secs, 0.001)).toFixed(1)} req/s`)
  ;[...m.status.entries()].sort((a, b) => a[0] - b[0]).forEach(([s, c]) => {
    info(`  ${(STATUS_LABEL[s] ?? `${s} 응답`).padEnd(22)} ${String(c).padStart(5)}건 (${((c / n) * 100).toFixed(1)}%)`)
  })
  if (m.blockedBy.size) {
    const detail = [...m.blockedBy.entries()].map(([limit, c]) => `${ruleLabel(limit)} ${c}건`).join(', ')
    info(`  └ 차단 규칙            ${detail}`)
  }
  const sorted = [...m.samples].sort((a, b) => a - b)
  const avg = m.samples.reduce((a, b) => a + b, 0) / n
  info(`응답시간      p50 ${percentile(sorted, 50).toFixed(0)}ms · p90 ${percentile(sorted, 90).toFixed(0)}ms`
    + ` · p99 ${percentile(sorted, 99).toFixed(0)}ms · max ${sorted[sorted.length - 1].toFixed(0)}ms · 평균 ${avg.toFixed(0)}ms`)
}

/** 시나리오 요약 줄을 등록한다(마지막에 표로 출력). */
function noteScenario(label, requests, elapsedMs, note = '') {
  scenarios.push({ label, requests, elapsedMs, note })
}

// ── 동시 실행 풀 ─────────────────────────────────────────────────────────────
/** items 를 concurrency 개의 워커로 나눠 처리하고, 진행률을 주기적으로 출력한다. */
async function runPool(items, concurrency, worker, { label = '진행', every = 50 } = {}) {
  let next = 0
  let done = 0
  const started = performance.now()
  const width = String(items.length).length
  const runner = async () => {
    for (;;) {
      const i = next++
      if (i >= items.length) return
      await worker(items[i], i)
      done++
      if (every && (done % every === 0 || done === items.length)) {
        const el = (performance.now() - started) / 1000
        info(`${label} ${String(done).padStart(width)}/${items.length}`
          + ` (${String(Math.round((done / items.length) * 100)).padStart(3)}%)`
          + ` · ${el.toFixed(1)}s · ${(done / Math.max(el, 0.001)).toFixed(0)} req/s`)
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, runner))
  return performance.now() - started
}

// ── 사전 점검 ────────────────────────────────────────────────────────────────
async function preflight() {
  section('0. 사전 점검')

  let health
  try {
    health = await fetch(`${BASE}/actuator/health`, { signal: AbortSignal.timeout(5000) }).then((r) => r.json())
  } catch (e) {
    console.error(`\n애플리케이션에 연결할 수 없습니다: ${BASE}`)
    console.error('  → docker compose up -d 후 backend 를 bootRun 으로 띄운 뒤 다시 실행하세요.')
    console.error(`  (${e.message})`)
    process.exit(2)
  }
  check('애플리케이션 health = UP', health.status === 'UP', JSON.stringify(health.status))
  check('DB 연결 UP', health.components?.db?.status === 'UP')
  check('Redis 연결 UP', health.components?.redis?.status === 'UP')

  try {
    docker(MYSQL_CONTAINER, ['true'])
  } catch {
    console.error(`\nMySQL 컨테이너(${MYSQL_CONTAINER})에 접근할 수 없습니다. --mysql 로 이름을 지정하세요.`)
    process.exit(2)
  }
  try {
    redis('ping')
  } catch {
    console.error(`\nRedis 컨테이너(${REDIS_CONTAINER})에 접근할 수 없습니다. --redis 로 이름을 지정하세요.`)
    process.exit(2)
  }

  const win = (await call('GET', '/registrations/window', { ip: CONTROL_IP })).json
  if (!win?.open) {
    console.error(`\n등록 창이 닫혀 있어 부하 시험을 진행할 수 없습니다(현재 서버시간 ${win?.serverTime}).`)
    console.error('IMLATE_REGISTRATION_CLOSE_TIME=23:59 로 앱을 재기동한 뒤 다시 실행하세요.')
    process.exit(2)
  }
  const date = win.date
  info(`대상일 = ${date} · 마감 = ${win.closesAt} · 남은 시간 ${Math.round(win.secondsUntilClose / 60)}분`)

  // 지난 실행이 남긴 부하 데이터 정리 (자기 것만)
  const stale = dbCount(`room_number LIKE 'LT%'`)
  if (stale > 0) {
    mysql(`DELETE FROM return_registration WHERE room_number LIKE 'LT%'`)
    info(`이전 실행이 남긴 부하 데이터 ${stale}건을 정리했습니다.`)
  }
  purgeLoadWal(date)
  clearRateLimits()

  // ── 실제 적용 중인 rate limit 설정을 앱에서 읽는다 ──────────────────────────
  const limiter = await readLimiterConfig()

  // 카나리아: 앱이 실제로 이 MySQL/Redis 컨테이너를 보고 있는지 확인한다.
  //   (다른 스택이 8080 을 점유한 경우를 여기서 잡아낸다)
  const canary = await call('POST', '/registrations', {
    ip: CONTROL_IP,
    body: { className: '점검반', studentName: '사전점검', roomNumber: ROOM_CANARY },
  })
  check('카나리아 등록 201', canary.status === 201, `status=${canary.status} ${canary.text.slice(0, 120)}`)
  check(`앱이 --mysql(${MYSQL_CONTAINER}) 컨테이너의 DB 를 사용 중`,
    dbCount(`room_number='${ROOM_CANARY}'`) === 1,
    '앱이 다른 DB 를 보고 있습니다. --mysql 옵션을 확인하세요.')
  check(`앱이 --redis(${REDIS_CONTAINER}) 컨테이너의 WAL 을 사용 중`,
    walLoadEntries(date).some((w) => w.entry?.roomNumber === ROOM_CANARY),
    '앱이 다른 Redis 를 보고 있습니다. --redis 옵션을 확인하세요.')
  if (fail > 0) {
    console.error('\n사전 점검에 실패했습니다. 대상 환경을 먼저 확인하세요.')
    cleanup(date)
    process.exit(1)
  }

  // 카나리아는 본 시험 집계에 섞이지 않도록 즉시 제거한다.
  mysql(`DELETE FROM return_registration WHERE room_number='${ROOM_CANARY}'`)
  purgeLoadWal(date)

  // ── 설정값 자체가 "공용 와이파이 200명"을 감당하도록 잡혀 있는가 ────────────
  //   부하를 걸기 전에 설정만 보고도 알 수 있는 결함이다. 여기서 먼저 잡는다.
  section('0B. rate limit 설정이 공용 와이파이 200명을 감당하는가 (설정 검사)')
  info(`적용 중: global(IP) ${GLOBAL_CAPACITY}/분 · register(IP) ${REGISTER_CAPACITY}/분`
    + ` · register(개인) ${PERSON_CAPACITY}/분  ${limiter.sources}`)
  check('rate limit 이 켜져 있다 (꺼져 있으면 이 시험은 아무것도 증명하지 못한다)',
    limiter.enabled, 'imlate.rate-limit.enabled=false 로 보입니다.')
  checkIfTrusted('register', `register(IP) 한도(${REGISTER_CAPACITY}) ≥ 동시 등록 인원(${WIFI_STUDENTS})`,
    REGISTER_CAPACITY >= WIFI_STUDENTS,
    `공용 와이파이 뒤 ${WIFI_STUDENTS}명이 IP 하나를 공유하므로 이 값이 인원보다 작으면 정상 사용자가 막힌다.`)
  checkIfTrusted('global', `global(IP) 한도(${GLOBAL_CAPACITY}) ≥ 인원 × 2 (${WIFI_STUDENTS * 2})`,
    GLOBAL_CAPACITY >= WIFI_STUDENTS * 2,
    '학생 한 명이 최소 2회(마감 조회 + 등록) 호출한다.')
  checkIfTrusted('person', `register(개인) 한도(${PERSON_CAPACITY})가 도배를 막을 만큼 작다 (< ${WIFI_STUDENTS})`,
    PERSON_CAPACITY < WIFI_STUDENTS,
    '개인 버킷이 IP 버킷만큼 크면 도배를 전혀 막지 못한다.')

  // ── X-Forwarded-For 가 실제로 반영되는지 Redis 버킷 키로 직접 확인 ──────────
  clearRateLimits()
  const probeIp = '10.99.99.99'
  await call('GET', '/registrations/window', { ip: probeIp })
  const keysAfterProbe = rateLimitKeys()
  const xffHonored = keysAfterProbe.some((k) => k.endsWith(`:${probeIp}`))
  if (limiter.enabled && !keysAfterProbe.length) {
    warn('rate limit 버킷이 Redis 에 생기지 않았다', '로컬 폴백 리미터로 동작 중일 수 있습니다.')
  }
  if (xffHonored) {
    info('X-Forwarded-For 가 클라이언트 식별에 반영됩니다(trusted-proxies 설정됨). §1 기준선을 수행합니다.')
  } else {
    info('X-Forwarded-For 가 무시됩니다(trusted-proxies 비어 있음 = 로컬 기본값).')
    info('→ 위조 헤더로 IP 를 나눌 수 없으므로 §1(IP 분산 기준선)은 건너뜁니다.')
    info('→ 모든 요청이 하나의 클라이언트로 취급됩니다. 이것이 곧 실제 운영(공용 와이파이) 조건입니다.')
  }

  // §3C 폭주 규모: global 한도를 확실히 넘기도록 잡는다(리필분 포함).
  if (!FLOOD_REQUESTS) {
    FLOOD_REQUESTS = Math.min(3000, Math.ceil(GLOBAL_CAPACITY * 1.8) + 40)
  }

  // JIT 워밍업 — 첫 요청의 클래스 로딩 비용이 p99 를 왜곡하지 않도록 한다(측정 대상 아님).
  for (let i = 0; i < 10; i++) await call('GET', '/registrations/window', { ip: CONTROL_IP })
  clearRateLimits()
  info('워밍업 완료. 부하를 시작합니다.')
  return { date, xffHonored, enabled: limiter.enabled }
}

/**
 * 기동 중인 앱의 rate limit 설정을 읽어 전역 한도값을 채운다.
 * 명령행으로 값을 준 항목은 덮어쓰지 않는다(사람이 준 값이 항상 우선).
 */
async function readLimiterConfig() {
  const sources = []
  let enabled = true
  try {
    const res = await fetch(`${BASE}/actuator/env/imlate.rate-limit.enabled`, { signal: AbortSignal.timeout(5000) })
    if (res.ok) {
      const json = await res.json()
      enabled = String(json?.property?.value) !== 'false'
    }
  } catch {
    /* actuator 가 닫혀 있으면 켜져 있다고 가정하고, 아래 버킷 탐지로 다시 확인한다 */
  }

  // 한도는 응답 헤더에서 읽는다(actuator 는 값을 마스킹한다).
  if (!GLOBAL_CAPACITY_FIXED) {
    const v = await limitFromHeader('/api/v1/registrations/window')
    if (v) { GLOBAL_CAPACITY = v; CAP_TRUSTED.global = true; sources.push('global←헤더') }
  }
  if (!REGISTER_CAPACITY_FIXED) {
    // 등록 경로 헤더에는 개인 한도가 실리므로(더 빡빡함) IP 축 한도는 직접 관측할 수 없다.
    // IP 축은 §3C(단일 회선 폭주)에서 429 가 나는지로 간접 검증한다.
    const v = await envNumber('imlate.rate-limit.register.capacity')
    if (v) { REGISTER_CAPACITY = v.value; CAP_TRUSTED.register = true; sources.push('register←앱') }
  }
  if (!PERSON_CAPACITY_FIXED) {
    // 개인 버킷 규칙의 프로퍼티 이름은 SPEC §8 기준 `register-person` 이지만,
    // 다른 이름으로 구현되었을 가능성까지 열어 두고 순서대로 시도한다.
    // 등록 경로의 X-RateLimit-Limit 은 IP·개인 중 더 빡빡한 쪽 = 개인 한도다.
    //
    // 프로브는 **비파괴적**이어야 한다. 정상 본문을 보내면 실제 등록이 하나 생겨
    // 이후 건수 단언(DB/WAL)이 전부 어긋난다.
    // 그래서 허용 문자 규칙을 일부러 어긴 이름을 쓴다.
    //   - 인터셉터는 정규화만 하고 검증하지 않으므로 personKey 가 만들어져 개인 버킷이 평가된다
    //     → X-RateLimit-Limit 헤더가 정상적으로 실린다
    //   - 컨트롤러의 @Pattern 에서 400 으로 거부되어 DB·WAL 에는 아무것도 남지 않는다
    const v = await limitFromHeader('/api/v1/registrations', {
      method: 'POST',
      body: { className: 'ZZ', studentName: 'LimitProbe!!', roomNumber: 'LT000' },
    })
    if (v) { PERSON_CAPACITY = v; CAP_TRUSTED.person = true; sources.push('개인←헤더') }
  }
  const unknown = Object.entries(CAP_TRUSTED).filter(([, ok]) => !ok).map(([k]) => k)
  if (unknown.length) {
    warn(`한도값을 앱에서 읽지 못했습니다: ${unknown.join(', ')}`,
      'X-RateLimit-* 헤더가 없거나(리미터 비활성) actuator 가 값을 마스킹했습니다. 해당 항목의 수치 단언은 경고로 낮춥니다. ' +
      '※ register(IP) 는 등록 헤더에 개인 한도가 실려 원래 직접 관측되지 않습니다 — §3C 로 간접 검증합니다.')
  }
  return {
    enabled,
    sources: sources.length ? `(${sources.join(', ')})` : '(앱 설정을 읽지 못해 인자/기본값 사용)',
  }
}

/** WAL 에서 이 스크립트가 만든 항목(LT* 호수)만 지운다. */
function purgeLoadWal(date) {
  const fields = walLoadEntries(date).map((w) => w.field)
  for (let i = 0; i < fields.length; i += 100) {
    const chunk = fields.slice(i, i + 100)
    if (chunk.length) redis('hdel', `imlate:wal:${date}`, ...chunk)
  }
  return fields.length
}

// ── 1. IP 분산 기준선 (비현실적 — 참고용) ────────────────────────────────────
async function phaseBaseline(date) {
  section(`1. [기준선·비현실적] 마감 직전 동시 등록 — ${STUDENTS}명이 **서로 다른 IP** / 동시 ${CONCURRENCY}`)
  info(`요청마다 서로 다른 X-Forwarded-For(10.77.x.x)를 붙여 ${STUDENTS}명을 흉내 냅니다.`)
  info('★ 실제 운영은 이렇지 않다. 전원이 기숙사 공용 와이파이 하나를 공유한다(§1B).')
  info('  이 시나리오만 돌리다가 "IP당 8회/분" 결함을 놓쳤다. 순수 처리량 측정용으로만 남긴다.')
  clearRateLimits()

  const reg = newMetrics('[기준선] 등록 POST /api/v1/registrations (IP 분산)')
  const win = newMetrics('[기준선] 마감 카운트다운 GET /api/v1/registrations/window (IP 분산)')

  const items = Array.from({ length: STUDENTS }, (_, i) => i)
  const elapsed = await runPool(items, CONCURRENCY, async (i) => {
    // 실제 사용자는 페이지를 열면서 서버 시간을 먼저 받아 간다.
    record(win, await call('GET', '/registrations/window', { ip: LOAD_IP(i), visitor: `load-s${i}` }))
    record(reg, await call('POST', '/registrations', { ip: LOAD_IP(i), visitor: `load-s${i}`, body: registerBody(i) }))
  }, { label: '등록 진행', every: STUDENTS >= 100 ? 50 : Math.max(1, Math.ceil(STUDENTS / 4)) })

  reg.elapsedMs = elapsed
  win.elapsedMs = elapsed
  printMetrics(reg)
  printMetrics(win)

  const rows = dbCount(`registration_date='${date}' AND room_number LIKE 'LTS%'`)
  check(`[기준선] ${STUDENTS}명 전원 201 신규 등록`, cnt(reg, 201) === STUDENTS,
    `201=${cnt(reg, 201)} 200=${cnt(reg, 200)} 429=${cnt(reg, 429)} 그외=${total(reg) - cnt(reg, 201)}`)
  check('[기준선] 429 = 0건', cnt(reg, 429) + cnt(win, 429) === 0,
    `등록 429=${cnt(reg, 429)}, 창조회 429=${cnt(win, 429)}`)
  check(`[기준선] DB 등록 건수 == ${STUDENTS}`, rows === STUDENTS, `db=${rows}`)
  check('[기준선] 5xx/연결실패 0건', sum5xx(reg) + sum5xx(win) === 0,
    `등록 ${sum5xx(reg)}건, 창조회 ${sum5xx(win)}건`)
  reportP99(reg, '[기준선] 등록')

  noteScenario('1.  IP 분산 기준선 (비현실적)', total(reg) + total(win), elapsed)
  return [reg, win]
}

// ── 1B. ★공용 와이파이 — 실제 운영 환경 ──────────────────────────────────────
async function phaseSharedWifi(date, state) {
  section(`1B. ★공용 와이파이(NAT) — ${WIFI_STUDENTS}명이 **모두 같은 출발지 IP** 로 마감 직전 등록`)
  info('기숙사 공용 WiFi 뒤에서는 전원이 공인 IP 하나를 공유한다. 이것이 실제 운영 조건이다.')
  info(state.xffHonored
    ? `출발지 = ${WIFI_IP} 하나 (X-Forwarded-For 로 고정)`
    : '출발지 = 앱이 보는 remoteAddr 하나 (XFF 를 신뢰하지 않으므로 자연스럽게 단일 IP)')
  info(`각자 반/이름/호수가 다르므로 개인 버킷은 1명당 1개만 소비한다.`)
  info(`기대: 전원 201 · 429 0건 · DB ${WIFI_STUDENTS}건.`)
  info('  → 여기서 429 가 나오면 마감 직전(기본 21:45)에 정상 교육생이 차단된다는 뜻이다(운영 불가).')
  clearRateLimits()

  const reg = newMetrics('★[공용WiFi] 등록 POST /api/v1/registrations (단일 IP)')
  const win = newMetrics('★[공용WiFi] 마감 카운트다운 GET /api/v1/registrations/window (단일 IP)')

  const items = Array.from({ length: WIFI_STUDENTS }, (_, i) => i)
  const elapsed = await runPool(items, CONCURRENCY, async (i) => {
    record(win, await call('GET', '/registrations/window', { ip: WIFI_IP, visitor: `wifi-${i}` }))
    record(reg, await call('POST', '/registrations', { ip: WIFI_IP, visitor: `wifi-${i}`, body: wifiBody(i) }))
  }, { label: '공용WiFi 등록', every: WIFI_STUDENTS >= 100 ? 50 : Math.max(1, Math.ceil(WIFI_STUDENTS / 4)) })

  reg.elapsedMs = elapsed
  win.elapsedMs = elapsed
  printMetrics(reg)
  printMetrics(win)

  const rows = dbCount(`registration_date='${date}' AND room_number LIKE 'LTW%'`)
  const walRows = walLoadEntries(date).filter((w) => String(w.entry?.roomNumber).startsWith('LTW')).length

  check(`★ 같은 IP 의 ${WIFI_STUDENTS}명 전원 201 신규 등록`, cnt(reg, 201) === WIFI_STUDENTS,
    `201=${cnt(reg, 201)} 200=${cnt(reg, 200)} 429=${cnt(reg, 429)} 그외=${total(reg) - cnt(reg, 201)}`
    + ' — 429 가 있으면 IP 한도가 인원보다 작다(공용 와이파이에서 정상 사용자가 막힌다).')
  check('★ 429 rate limit 차단 0건 (등록 + 마감 조회)', cnt(reg, 429) + cnt(win, 429) === 0,
    `등록 429=${cnt(reg, 429)}, 창조회 429=${cnt(win, 429)}`)
  check(`★ DB 등록 건수 == ${WIFI_STUDENTS}`, rows === WIFI_STUDENTS, `db=${rows}`)
  check(`WAL 항목도 ${WIFI_STUDENTS}건 (유실 0)`, walRows === WIFI_STUDENTS, `wal=${walRows}`)
  check('5xx/연결실패 0건', sum5xx(reg) + sum5xx(win) === 0,
    `등록 ${sum5xx(reg)}건, 창조회 ${sum5xx(win)}건`)
  if (!state.enabled) {
    warn('rate limit 이 꺼져 있어 이 통과는 증거가 되지 못한다', '켠 상태로 다시 실행하세요.')
  }
  reportP99(reg, '★[공용WiFi] 등록')

  noteScenario('1B. 공용 와이파이 (같은 IP·다른 사람)', total(reg) + total(win), elapsed)
  return [reg, win]
}

/** p99 는 실패가 아니라 경고로 다룬다(환경 편차가 크다). */
function reportP99(m, label) {
  const sorted = [...m.samples].sort((a, b) => a - b)
  const p99 = percentile(sorted, 99)
  if (p99 <= P99_BUDGET_MS) {
    check(`${label} p99 응답시간 ${P99_BUDGET_MS}ms 이하`, true, `p99=${p99.toFixed(0)}ms`)
  } else {
    warn(`${label} p99 응답시간이 임계값을 초과`, `p99=${p99.toFixed(0)}ms > ${P99_BUDGET_MS}ms (실패로 처리하지 않음)`)
  }
}

// ── 2. 동시 등록 경합(멱등성) ────────────────────────────────────────────────
async function phaseRace(date) {
  section(`2. 동시 등록 경합 — 같은 사람을 ${RACE_REQUESTS}개 요청으로 동시에 등록`)
  info('교육생이 버튼을 연타하거나 여러 기기에서 동시에 누른 상황. DB 에는 1건만 남아야 한다.')
  info(`개인 버킷(${PERSON_CAPACITY}회/분)이 있으므로 일부는 429 로 막힌다 — 그것도 정상이다.`)
  info('여기서 확인할 것은 "통과한 요청들 사이에서 중복 행이 생기지 않는가" 이다.')
  clearRateLimits()

  const person = { className: '9반', studentName: '동시등록', roomNumber: ROOM_RACE }
  const m = newMetrics(`동일인 동시 등록 ${RACE_REQUESTS}건`)

  // 진짜 "동시"를 만들기 위해 풀을 쓰지 않고 한꺼번에 발사한다.
  // IP 를 흩어 놓아도 개인 버킷은 공유되므로, IP 를 나누는 것은 IP 버킷 소진만 피하려는 목적이다.
  const started = performance.now()
  const results = await Promise.all(
    Array.from({ length: RACE_REQUESTS }, (_, i) => call('POST', '/registrations',
      { ip: RACE_IP(i), visitor: `race-${i}`, body: person })))
  m.elapsedMs = performance.now() - started
  results.forEach((r) => record(m, r))
  printMetrics(m)

  const ok = accepted(m)
  const rows = dbCount(`registration_date='${date}' AND room_number='${ROOM_RACE}'`)
  check('DB 에는 정확히 1건만 생성됨 (멱등성)', rows === 1, `db=${rows}`)
  check('201 신규 응답은 정확히 1건', cnt(m, 201) === 1, `201=${cnt(m, 201)}`)
  check('통과한 나머지는 전부 200 + duplicate=true',
    cnt(m, 200) === ok - 1 && results.filter((r) => r.status === 200).every((r) => r.json?.duplicate === true),
    `200=${cnt(m, 200)} 통과=${ok}`)
  check('통과 + 차단 = 전체 요청 수 (그 밖의 상태코드 없음)',
    ok + cnt(m, 429) === RACE_REQUESTS, `통과=${ok} 429=${cnt(m, 429)} 전체=${RACE_REQUESTS}`)
  check('경합 상황에서 5xx 0건', sum5xx(m) === 0, `5xx=${sum5xx(m)}`)
  if (cnt(m, 429) > 0) {
    info(`개인 버킷이 ${cnt(m, 429)}건을 차단했습니다(설정 ${PERSON_CAPACITY}회/분).`)
  }

  const walForRace = walLoadEntries(date).filter((w) => w.entry?.roomNumber === ROOM_RACE)
  const distinctPersons = new Set(walForRace.map((w) => `${w.entry.className}|${w.entry.studentName}|${w.entry.roomNumber}`))
  info(`WAL 항목 ${walForRace.length}건 → 동일인 판정(personKey) 기준 ${distinctPersons.size}명`)
  check('WAL 은 동일인 기준 1명으로 수렴 (대사 시 중복 복구 없음)', distinctPersons.size === 1,
    `personKey=${distinctPersons.size}`)
  check('차단된 요청은 WAL 에도 남지 않음 (WAL 항목 수 == 통과 건수)',
    walForRace.length === ok, `wal=${walForRace.length} 통과=${ok}`)
  check('경합에서 진 요청도 WAL 상태가 COMMITTED 로 정리됨',
    walForRace.every((w) => w.entry?.status === 'COMMITTED'),
    walForRace.map((w) => w.entry?.status).join(','))

  noteScenario('2.  동시 등록 경합 (멱등성)', total(m), m.elapsedMs)
  return [m]
}

// ── 3A. 같은 IP · 같은 사람 도배 → 개인 버킷이 막는다 ────────────────────────
async function phasePersonSpam(date) {
  section(`3A. 같은 IP · **같은 사람** 도배 — 최대 ${SPAM_MAX}회 연속 제출 (개인 버킷 ${PERSON_CAPACITY}회/분)`)
  info('§1B 와 딱 하나만 다르다: 거기선 200명이 각각 1번, 여기선 1명이 여러 번.')
  info('IP 는 같지만 이쪽은 반드시 429 가 나야 한다. 이것이 "둘 다 적용"의 존재 이유다.')
  clearRateLimits()

  const person = { className: '9반', studentName: '도배사용자', roomNumber: ROOM_SPAM }
  const m = newMetrics(`동일인 반복 제출 (같은 IP ${SPAM_IP})`)
  const started = performance.now()
  let firstBlockedAt = 0
  let sent = 0
  for (let i = 0; i < SPAM_MAX; i++) {
    const res = await call('POST', '/registrations', { ip: SPAM_IP, visitor: 'spam-bot', body: person })
    record(m, res)
    sent++
    if (res.status === 429 && !firstBlockedAt) firstBlockedAt = sent
    // 차단이 확인되면 헤더 일관성 표본만 몇 건 더 모으고 멈춘다(불필요한 요청을 늘리지 않는다).
    if (firstBlockedAt && sent >= firstBlockedAt + 2) break
  }
  m.elapsedMs = performance.now() - started
  printMetrics(m)

  const ok = accepted(m)
  const rows = dbCount(`registration_date='${date}' AND room_number='${ROOM_SPAM}'`)
  const walRows = walLoadEntries(date).filter((w) => w.entry?.roomNumber === ROOM_SPAM).length

  check(`★ 같은 사람 반복 제출이 429 로 차단됨 (${firstBlockedAt || '-'}번째부터)`,
    cnt(m, 429) > 0,
    `${sent}회를 보냈지만 429 가 0건 — 개인 식별자 버킷이 동작하지 않는다.`)
  check('첫 요청은 반드시 통과한다 (정상 사용자를 막지 않는다)', cnt(m, 201) === 1, `201=${cnt(m, 201)}`)
  checkIfTrusted('person', `통과 건수(${ok})가 개인 한도(${PERSON_CAPACITY}) 이하`,
    ok <= PERSON_CAPACITY + refilledDuring(m.elapsedMs, PERSON_CAPACITY) + 1,
    `통과=${ok} 허용=${PERSON_CAPACITY}(+리필 ${refilledDuring(m.elapsedMs, PERSON_CAPACITY)})`)
  check('도배해도 DB 행은 1건 (멱등)', rows === 1, `db=${rows}`)
  check('차단된 요청은 WAL 에도 남지 않음', walRows === ok, `wal=${walRows} 통과=${ok}`)
  check('모든 429 응답에 Retry-After 헤더 + code=RATE_LIMITED',
    m.retryAfterCount === cnt(m, 429) && m.rateLimitedCode === cnt(m, 429),
    `retry-after=${m.retryAfterCount} code=${m.rateLimitedCode} / 429=${cnt(m, 429)}`)

  // ── 3B. 핵심: 도배범과 같은 IP 를 쓰는 옆자리 사용자는 막히면 안 된다 ────────
  section('3B. ★옆자리 사용자 — 도배범과 **같은 IP** 인데 **다른 사람** → 통과해야 한다')
  info('공용 와이파이에서 한 명이 도배해도 나머지 199명은 등록할 수 있어야 한다.')
  const neighbor = await call('POST', '/registrations', {
    ip: SPAM_IP, visitor: 'neighbor',
    body: { className: '9반', studentName: '옆자리사용자', roomNumber: ROOM_NEIGHBOR },
  })
  check('★ 도배 차단 중에도 같은 IP 의 다른 사람은 정상 등록(201)', neighbor.status === 201,
    `status=${neighbor.status} ${neighbor.text.slice(0, 140)}`
    + ' — 429 라면 개인이 아니라 IP 를 막고 있다는 뜻이다.')
  info(`옆자리 사용자 응답시간 ${neighbor.ms.toFixed(0)}ms`)

  noteScenario('3A. 같은 사람 도배 (개인 버킷)', total(m), m.elapsedMs)
  noteScenario('3B. 옆자리 사용자 (같은 IP·다른 사람)', 1, neighbor.ms)
  return [m]
}

// ── 3C. 단일 회선 대량 폭주 → IP 버킷이 막는다 (DDoS 방어) ───────────────────
async function phaseLineFlood(date, state) {
  section(`3C. 단일 회선 대량 폭주(DDoS) — 한 IP 에서 ${FLOOD_REQUESTS}회 (global 한도 ${GLOBAL_CAPACITY}회/분)`)
  info('IP 버킷은 이제 "정상 사용자 구분"이 아니라 "한 회선에서의 대량 폭주 차단" 전용이다.')
  info('DB 를 건드리지 않는 조회 API 로 때려 회선 단위 방어만 본다(불필요한 등록 행을 만들지 않는다).')
  clearRateLimits()

  const floodConcurrency = Math.max(CONCURRENCY, 30)
  const m = newMetrics(`단일 회선 폭주 GET /registrations/window ${FLOOD_REQUESTS}건`)
  const items = Array.from({ length: FLOOD_REQUESTS }, (_, i) => i)
  m.elapsedMs = await runPool(items, floodConcurrency, async () => {
    record(m, await call('GET', '/registrations/window', { ip: FLOOD_IP, visitor: 'flood-bot' }))
  }, { label: '폭주 진행', every: Math.max(50, Math.ceil(FLOOD_REQUESTS / 8)) })
  printMetrics(m)

  const okCount = cnt(m, 200)
  const refilled = refilledDuring(m.elapsedMs, GLOBAL_CAPACITY)
  check('★ 단일 회선의 대량 폭주가 429 로 차단됨', cnt(m, 429) > 0,
    `${FLOOD_REQUESTS}건 중 429=0 — global(IP) 한도(${GLOBAL_CAPACITY}/분)가 너무 크거나 IP 버킷이 없다.`)
  checkIfTrusted('global', `통과 건수(${okCount})가 global 한도(${GLOBAL_CAPACITY}) + 리필분(${refilled}) 이하`,
    okCount <= GLOBAL_CAPACITY + refilled + 5,
    `통과=${okCount} 허용상한=${GLOBAL_CAPACITY + refilled + 5} (소요 ${(m.elapsedMs / 1000).toFixed(1)}초)`)
  check('모든 429 응답에 Retry-After 헤더 + code=RATE_LIMITED',
    m.retryAfterCount === cnt(m, 429) && m.rateLimitedCode === cnt(m, 429),
    `retry-after=${m.retryAfterCount} code=${m.rateLimitedCode} / 429=${cnt(m, 429)}`)
  check('폭주 중에도 5xx 0건', sum5xx(m) === 0, `5xx=${sum5xx(m)}`)

  // 폭주 회선에서는 등록도 막혀 있어야 한다(같은 IP 버킷을 공유한다).
  const blockedRegister = await call('POST', '/registrations', {
    ip: FLOOD_IP, visitor: 'flood-bot',
    body: { className: '9반', studentName: '폭주회선', roomNumber: ROOM_FLOOD },
  })
  check('폭주로 소진된 회선에서는 등록도 차단된다(429)', blockedRegister.status === 429,
    `status=${blockedRegister.status}`)
  check('차단된 등록은 DB 에 저장되지 않음',
    dbCount(`registration_date='${date}' AND room_number='${ROOM_FLOOD}'`) === 0)

  if (state.xffHonored) {
    // IP 단위 격리: 폭주 회선을 막아도 다른 회선은 멀쩡해야 한다.
    const other = await call('POST', '/registrations', {
      ip: CLEAN_IP, visitor: 'clean-line',
      body: { className: '9반', studentName: '다른회선사용자', roomNumber: ROOM_OTHER_LINE },
    })
    check('폭주 회선을 차단해도 다른 IP 는 정상 등록(201)', other.status === 201,
      `status=${other.status} ${other.text.slice(0, 120)}`)
  } else {
    info('XFF 를 신뢰하지 않는 설정이라 "다른 IP 는 멀쩡한가"는 로컬에서 확인할 수 없습니다(건너뜀).')
    info('  → 이 항목은 운영(ALB 뒤)에서 trusted-proxies 를 설정한 뒤 확인합니다.')
  }

  // 다음 단계(정합성·명단 렌더링)가 자기 요청 때문에 막히지 않도록 버킷을 비운다.
  clearRateLimits()
  noteScenario('3C. 단일 회선 폭주 (IP 버킷)', total(m) + (state.xffHonored ? 2 : 1), m.elapsedMs)
  return [m]
}

// ── 4. 부하 후 정합성 (WAL ↔ DB) ─────────────────────────────────────────────
function phaseConsistency(date) {
  section('4. 부하 후 데이터 정합성 — WAL(Redis) ↔ DB (R7)')

  const wal = walLoadEntries(date)
  const walOk = wal.filter((w) => w.entry)
  const persons = new Set(walOk.map((w) => `${w.entry.className}|${w.entry.studentName}|${w.entry.roomNumber}`))
  const rows = dbCount(`registration_date='${date}' AND room_number LIKE 'LT%'`)

  // DB 행의 wal_id 가 실제 WAL 항목으로 존재하는지 (= 이중 기록이 실제로 연결되어 있는지)
  const dbWalIds = mysql(`SELECT wal_id FROM return_registration`
    + ` WHERE registration_date='${date}' AND room_number LIKE 'LT%'`).split('\n').map((s) => s.trim()).filter(Boolean)
  const walFields = new Set(wal.map((w) => w.field))
  const orphans = dbWalIds.filter((id) => !walFields.has(id))

  info(`DB ${rows}건 · WAL 항목 ${wal.length}건 · WAL 동일인 기준 ${persons.size}명`)
  check('WAL JSON 이 깨진 항목 없음', walOk.length === wal.length, `깨짐=${wal.length - walOk.length}`)
  check('WAL 건수(동일인 기준) == DB 건수', persons.size === rows, `wal=${persons.size} db=${rows}`)
  check('모든 DB 행이 WAL 항목과 wal_id 로 연결됨',
    dbWalIds.length === rows && orphans.length === 0,
    `db=${rows} wal_id=${dbWalIds.length} WAL 미기록=${orphans.length}`)
  check('WAL 항목이 모두 COMMITTED (PENDING/FAILED 잔여 없음)',
    walOk.every((w) => w.entry.status === 'COMMITTED'),
    [...new Set(walOk.map((w) => w.entry.status))].join(','))

  const ttl = Number(redis('ttl', `imlate:wal:${date}`))
  check('WAL 키에 TTL 이 유지됨', ttl > 0, `ttl=${ttl}s`)

  // 중복 인원이 생기지 않았는지 (동시성 버그가 있으면 여기서 잡힌다)
  const dupPersons = Number(mysql(
    `SELECT COUNT(*) FROM (SELECT class_name, student_name, room_number FROM return_registration`
    + ` WHERE registration_date='${date}' AND room_number LIKE 'LT%'`
    + ` GROUP BY class_name, student_name, room_number HAVING COUNT(*) > 1) d`))
  check('동일인 중복 행 0건', dupPersons === 0, `중복=${dupPersons}`)
  return rows
}

// ── 5. 대량 명단 렌더링 (발송 준비 비용) ─────────────────────────────────────
async function phasePreview(date, totalRows) {
  section(`5. 명단 렌더링 — 사감 발송(기본 21:50) 준비 비용 측정 (총 ${totalRows}명)`)
  const res = await call('POST', `/admin/notifications/preview?date=${date}`, {
    ip: CONTROL_IP,
    headers: { 'X-Admin-Key': ADMIN_KEY },
  })
  if (res.status === 401) {
    warn('관리 API 인증 실패로 명단 렌더링 점검을 건너뜀', '--admin-key 를 확인하세요.')
    return
  }
  check('명단 미리보기 200', res.status === 200, `status=${res.status} ${res.text.slice(0, 120)}`)
  if (res.status !== 200) return
  info(`대상 ${res.json?.targetCount}명 · 대사 포함 렌더링 ${res.ms.toFixed(0)}ms`
    + ` · 문자 ${String(res.json?.smsBody ?? '').length}자 · 메일 ${String(res.json?.emailText ?? '').length}자`)
  check('공용 와이파이로 등록한 인원이 명단에 포함됨',
    String(res.json?.emailText ?? '').includes('와이파이생0001'), '명단 렌더링 결과에 부하 데이터가 없습니다.')
  check('대량 명단에서도 한글이 깨지지 않음',
    !/�/.test(String(res.json?.smsBody ?? '') + String(res.json?.emailText ?? '')))
  if (res.ms > 3000) warn('명단 렌더링이 3초를 초과', `${res.ms.toFixed(0)}ms`)
}

// ── 정리 ─────────────────────────────────────────────────────────────────────
function cleanup(date) {
  if (KEEP_DATA) {
    info('\n--keep 이 지정되어 테스트 데이터를 남깁니다.')
    return
  }
  const rows = dbCount(`room_number LIKE 'LT%'`)
  mysql(`DELETE FROM return_registration WHERE room_number LIKE 'LT%'`)
  const walDeleted = purgeLoadWal(date)
  const rl = clearRateLimits()
  info(`\n정리 완료 — DB ${rows}건, WAL ${walDeleted}건, rate limit 버킷 ${rl}개를 삭제했습니다.`)
  info('※ Redis 통계 카운터(imlate:stats:*)는 되돌리지 않습니다(부하분이 반영된 상태로 남습니다).')
}

// ── 본 시험 ──────────────────────────────────────────────────────────────────
async function run() {
  const startedAt = performance.now()
  console.log(`[36mimlate 부하 테스트[0m  대상=${BASE}`)
  console.log(`  기준선 ${STUDENTS}명 · 공용WiFi ${WIFI_STUDENTS}명 · 동시 ${CONCURRENCY} · p99 임계값 ${P99_BUDGET_MS}ms`
    + (SKIP_BASELINE ? ' · --same-ip(기준선 생략)' : ''))

  const state = await preflight()
  const metrics = []
  if (state.xffHonored && !SKIP_BASELINE) {
    metrics.push(...await phaseBaseline(state.date))
  } else {
    const why = SKIP_BASELINE ? '--same-ip 지정' : 'XFF 를 신뢰하지 않는 설정'
    noteScenario('1.  IP 분산 기준선 (비현실적)', 0, 0, `건너뜀 — ${why}`)
  }
  metrics.push(...await phaseSharedWifi(state.date, state))
  metrics.push(...await phaseRace(state.date))
  if (state.enabled) {
    metrics.push(...await phasePersonSpam(state.date))
    metrics.push(...await phaseLineFlood(state.date, state))
  } else {
    warn('rate limit 이 꺼져 있어 차단 시나리오(3A/3B/3C)를 건너뜀', 'imlate.rate-limit.enabled=true 로 실행하세요.')
    noteScenario('3.  차단 시나리오', 0, 0, '건너뜀 — rate limit 비활성')
  }
  const totalRows = phaseConsistency(state.date)
  await phasePreview(state.date, totalRows)

  // ── 총계 ──────────────────────────────────────────────────────────────────
  section('6. 전체 요약')
  const all = newMetrics('전체')
  metrics.forEach((m) => {
    all.samples.push(...m.samples)
    m.status.forEach((c, s) => all.status.set(s, (all.status.get(s) ?? 0) + c))
    m.blockedBy.forEach((c, s) => all.blockedBy.set(s, (all.blockedBy.get(s) ?? 0) + c))
  })
  all.elapsedMs = performance.now() - startedAt
  printMetrics(all)
  check('전체 구간 5xx/연결실패 0건', sum5xx(all) === 0, `5xx=${sum5xx(all)}`)

  // 시나리오별 요청 수·소요 (요청 수가 크게 늘었으므로 어디에 시간이 쓰였는지 명시한다)
  console.log('\n        ── 시나리오별 요청 수 / 소요 ─────────────────────────────')
  let reqSum = 0
  let elapsedSum = 0
  for (const s of scenarios) {
    reqSum += s.requests
    elapsedSum += s.elapsedMs
    const rate = s.elapsedMs > 0 ? `${(s.requests / (s.elapsedMs / 1000)).toFixed(0)} req/s` : '-'
    info(`${s.label.padEnd(36)} ${String(s.requests).padStart(6)}건 · ${(s.elapsedMs / 1000).toFixed(1).padStart(6)}초 · ${rate.padStart(10)}`
      + (s.note ? `  (${s.note})` : ''))
  }
  info(`${'─'.repeat(36)} ${'─'.repeat(6)}    ${'─'.repeat(6)}`)
  info(`${'합계 (부하 요청)'.padEnd(36)} ${String(reqSum).padStart(6)}건 · ${(elapsedSum / 1000).toFixed(1).padStart(6)}초`)
  info(`${'전체 소요 (점검·정리 포함)'.padEnd(36)} ${' '.repeat(6)}    ${((performance.now() - startedAt) / 1000).toFixed(1).padStart(6)}초`)

  cleanup(state.date)

  console.log(`\n${'═'.repeat(72)}`)
  console.log(`  결과: ${pass}건 통과, ${fail}건 실패, ${warnings.length}건 경고`
    + ` · 부하 요청 ${reqSum}건 · 총 소요 ${((performance.now() - startedAt) / 1000).toFixed(1)}초`)
  console.log(`  적용 한도: global(IP) ${GLOBAL_CAPACITY}/분 · register(IP) ${REGISTER_CAPACITY}/분`
    + ` · register(개인) ${PERSON_CAPACITY}/분`)
  if (warnings.length) {
    console.log('\n  경고:')
    warnings.forEach((w) => console.log(`   - ${w}`))
  }
  if (fail) {
    console.log('\n  실패 목록:')
    failures.forEach((f) => console.log(`   - ${f}`))
    console.log('\n  §1B(공용 와이파이)가 실패했다면 한도가 아니라 **설계**를 의심하라.')
    console.log('  IP 하나로만 버킷을 만들면 NAT 뒤 200명은 절대 통과할 수 없다(docs/SPEC.md §8).')
  }
  console.log('═'.repeat(72))
  process.exit(fail ? 1 : 0)
}

run().catch((e) => {
  console.error('\n부하 테스트 중 예외:', e)
  process.exit(2)
})
