#!/usr/bin/env node
/**
 * imlate 부하 테스트 — "22:00 마감 직전 몰림" 시뮬레이션
 *
 *   교육생 약 200명이 마감 직전에 동시에 몰릴 때
 *     · 전원이 정상적으로 등록되는가 (유실 0건)
 *     · WAL(Redis) ↔ DB 이중 기록이 부하 상황에서도 어긋나지 않는가 (R7)
 *     · 같은 사람이 동시에 여러 번 눌러도 DB 에는 1건만 남는가 (멱등성)
 *     · rate limiter 가 **정상 사용자는 막지 않으면서** 악의적 과다요청만 차단하는가 (R14)
 *     · 응답시간(p50/p90/p99)과 처리량이 감당 가능한 수준인가
 *   를 실제 기동 중인 앱 + 실제 MySQL/Redis 를 상대로 측정하고 검증한다.
 *
 *   ※ scripts/integration-test.mjs 는 "기능이 계약대로 동작하는가"를 보고,
 *     이 스크립트는 "동시에 몰렸을 때 버티는가"를 본다. 검증 범위가 겹치지 않는다.
 *
 * 사용법
 *   node scripts/load-test.mjs                          # 기본: IP 200개 분산 부하
 *   node scripts/load-test.mjs --students 200 --concurrency 20
 *   node scripts/load-test.mjs --same-ip                # 단일 IP 로 몰아쳐 rate limit 확인
 *   node scripts/load-test.mjs --keep                   # 끝나고 데이터를 지우지 않음
 *
 * 옵션
 *   --base-url <URL>        기본 http://localhost:8080
 *   --admin-key <KEY>       기본 local-dev-admin-key (200명 명단 렌더링 점검에만 사용)
 *   --students <N>          동시 등록 인원. 기본 200
 *   --concurrency <N>       동시 실행 워커 수. 기본 20
 *   --race <N>              같은 사람을 동시에 등록해 보는 요청 수. 기본 20
 *   --attack <N>            단일 IP 과다요청 시나리오의 요청 수. 기본 40
 *   --register-capacity <N> register 규칙의 분당 허용량. 기본 8 (application.yml 값)
 *   --p99-budget <MS>       p99 응답시간 경고 임계값. 기본 2000
 *   --same-ip               모든 부하를 한 IP 에서 발생시킨다(= NAT 뒤 200명 시나리오)
 *   --mysql <컨테이너명>     기본 imlate-mysql
 *   --redis <컨테이너명>     기본 imlate-redis
 *   --db-user/--db-pass/--db-name   기본 imlate/imlate/imlate
 *   --keep                  끝나고 테스트 데이터를 지우지 않음
 *
 * rate limit 우회(중요)
 *   register 규칙은 **IP 당 8회/분**인데 부하 테스트는 한 PC(같은 IP)에서 200명을 흉내 낸다.
 *   백엔드의 ClientIpResolver 는 X-Forwarded-For 의 **첫 IP** 를 클라이언트 IP 로 쓰고
 *   (없으면 X-Real-IP → remoteAddr), RateLimitInterceptor 는 그 값을 그대로 버킷 키
 *   `imlate:rl:{scope}:{clientIp}` 에 넣는다. 따라서 요청마다 서로 다른 X-Forwarded-For 를
 *   붙이면 서로 다른 사용자로 취급되어 200명 동시 등록을 그대로 재현할 수 있다.
 *   (운영에서는 ALB 가 이 헤더를 덮어쓰므로 외부에서 위조할 수 없다.)
 *
 * 주의
 *   - 이 스크립트는 자기가 만든 데이터(호수 LT* )를 지웁니다. **로컬 전용**이며
 *     base-url 이 localhost/127.0.0.1 이 아니면 실행을 거부합니다.
 *   - 다른 데이터는 건드리지 않습니다(DB 는 room_number LIKE 'LT%' 만,
 *     Redis 는 자기 WAL 필드와 10.* 대역 rate limit 버킷만 삭제).
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
const flag = (name) => argv.includes(`--${name}`)

const BASE = arg('base-url', 'http://localhost:8080').replace(/\/$/, '')
const API = `${BASE}/api/v1`
const ADMIN_KEY = arg('admin-key', 'local-dev-admin-key')
const STUDENTS = intArg('students', 200)
const CONCURRENCY = intArg('concurrency', 20)
const RACE_REQUESTS = intArg('race', 20)
const ATTACK_REQUESTS = intArg('attack', 40)
const REGISTER_CAPACITY = intArg('register-capacity', 8)
const REGISTER_REFILL_PER_SEC = REGISTER_CAPACITY / 60 // application.yml: 8 tokens / 60s
const P99_BUDGET_MS = intArg('p99-budget', 2000)
const SAME_IP = flag('same-ip')
const MYSQL_CONTAINER = arg('mysql', 'imlate-mysql')
const REDIS_CONTAINER = arg('redis', 'imlate-redis')
const DB_USER = arg('db-user', 'imlate')
const DB_PASS = arg('db-pass', 'imlate')
const DB_NAME = arg('db-name', 'imlate')
const KEEP_DATA = flag('keep')

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE)) {
  console.error(`거부: 이 스크립트는 데이터를 삭제하므로 로컬에서만 실행할 수 있습니다. (base-url=${BASE})`)
  process.exit(2)
}

// ── 테스트 데이터 규약 ───────────────────────────────────────────────────────
//   모든 호수를 'LT' 로 시작시켜 이 스크립트가 만든 행만 정확히 골라내고 정리한다.
const ROOM_LOAD = (i) => `LTS${String(i + 1).padStart(4, '0')}` // 정상 부하 200명
const ROOM_RACE = 'LTR001'                                      // 동시 등록 경합(같은 사람)
const ROOM_ATTACK = (i) => `LTA${String(i + 1).padStart(4, '0')}` // 단일 IP 과다요청
const ROOM_CANARY = 'LTC001'                                    // 사전 점검용 카나리아
const ROOM_INNOCENT = 'LTN001'                                  // 공격 직후의 정상 사용자
const CLASSES = ['1반', '2반', '3반', '4반', '5반', '6반', '7반', '8반']

/** 부하 발생용 가짜 클라이언트 IP. 전부 10.* 대역이라 정리할 때 정확히 골라낼 수 있다. */
const SINGLE_IP = '10.77.255.1'  // --same-ip 모드 / NAT 뒤 사용자
const LOAD_IP = (i) => (SAME_IP ? SINGLE_IP : `10.77.${Math.floor(i / 250)}.${(i % 250) + 1}`)
const RACE_IP = (i) => `10.88.${Math.floor(i / 250)}.${(i % 250) + 1}`
const ATTACKER_IP = '10.66.66.66'
const INNOCENT_IP = '10.55.55.55'
const CONTROL_IP = '10.44.44.44' // 스크립트 자신의 점검용 요청

// ── 결과 집계 ────────────────────────────────────────────────────────────────
let pass = 0
let fail = 0
const failures = []
const warnings = []

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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── HTTP ─────────────────────────────────────────────────────────────────────
/**
 * 한 번의 HTTP 호출. 응답시간(ms)을 함께 돌려준다.
 * ip 를 주면 X-Forwarded-For 로 붙여 서로 다른 클라이언트를 흉내 낸다.
 */
async function call(method, path, { body, ip, headers = {} } = {}) {
  const h = {
    'Content-Type': 'application/json',
    'X-Visitor-Id': `load-${ip ?? 'control'}`,
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

const registerBody = (i) => ({
  className: CLASSES[i % CLASSES.length],
  studentName: `부하생${String(i + 1).padStart(4, '0')}`,
  roomNumber: ROOM_LOAD(i),
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

/** 이 스크립트가 쓰는 10.* 대역 rate limit 버킷만 비운다(다른 클라이언트 버킷은 건드리지 않음). */
function clearOwnRateLimits() {
  const keys = redis('--scan', '--pattern', 'imlate:rl:*:10.*').split('\n').map((k) => k.trim()).filter(Boolean)
  return redisDelKeys(keys)
}

// ── 지표 ─────────────────────────────────────────────────────────────────────
const newMetrics = (label) =>
  ({ label, samples: [], status: new Map(), blockedBy: new Map(), retryAfterCount: 0, rateLimitedCode: 0, elapsedMs: 0 })

function record(m, res) {
  m.samples.push(res.ms)
  m.status.set(res.status, (m.status.get(res.status) ?? 0) + 1)
  if (res.status === 429) {
    // X-RateLimit-Limit 값으로 어떤 규칙이 막았는지 구분한다(8=register, 120=global).
    const key = res.limit ?? '?'
    m.blockedBy.set(key, (m.blockedBy.get(key) ?? 0) + 1)
    if (res.retryAfter) m.retryAfterCount++
    if (res.json?.code === 'RATE_LIMITED') m.rateLimitedCode++
  }
}

const cnt = (m, status) => m.status.get(status) ?? 0
const total = (m) => m.samples.length
const sum5xx = (m) => [...m.status.entries()].filter(([s]) => s >= 500 || s === 0).reduce((a, [, v]) => a + v, 0)

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
    const detail = [...m.blockedBy.entries()]
      .map(([limit, c]) => `${limit === String(REGISTER_CAPACITY) ? 'register' : limit === '120' ? 'global' : `limit=${limit}`} ${c}건`)
      .join(', ')
    info(`  └ 차단 규칙            ${detail}`)
  }
  const sorted = [...m.samples].sort((a, b) => a - b)
  const avg = m.samples.reduce((a, b) => a + b, 0) / n
  info(`응답시간      p50 ${percentile(sorted, 50).toFixed(0)}ms · p90 ${percentile(sorted, 90).toFixed(0)}ms`
    + ` · p99 ${percentile(sorted, 99).toFixed(0)}ms · max ${sorted[sorted.length - 1].toFixed(0)}ms · 평균 ${avg.toFixed(0)}ms`)
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
  clearOwnRateLimits()

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

  // JIT 워밍업 — 첫 요청의 클래스 로딩 비용이 p99 를 왜곡하지 않도록 한다(측정 대상 아님).
  for (let i = 0; i < 10; i++) await call('GET', '/registrations/window', { ip: CONTROL_IP })
  clearOwnRateLimits()
  info('워밍업 완료. 부하를 시작합니다.')
  return date
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

// ── 1. 정상 사용자 동시 등록 ─────────────────────────────────────────────────
async function phaseLoad(date) {
  section(SAME_IP
    ? `1. 단일 IP 부하 — ${STUDENTS}명이 같은 IP(${SINGLE_IP})에서 등록 시도`
    : `1. 마감 직전 동시 등록 — ${STUDENTS}명 / 동시 ${CONCURRENCY}`)
  if (SAME_IP) {
    info('NAT·공용 WiFi 뒤에서 전원이 한 IP 로 보이는 상황을 재현합니다.')
    info(`register 규칙(${REGISTER_CAPACITY}회/분)과 global 규칙(120회/분)에 막혀 대부분 429 여야 정상입니다.`)
  } else {
    info(`요청마다 서로 다른 X-Forwarded-For(10.77.x.x)를 붙여 ${STUDENTS}명을 흉내 냅니다.`)
  }

  const reg = newMetrics('등록 POST /api/v1/registrations')
  const win = newMetrics('마감 카운트다운 GET /api/v1/registrations/window')

  const items = Array.from({ length: STUDENTS }, (_, i) => i)
  const elapsed = await runPool(items, CONCURRENCY, async (i) => {
    if (!SAME_IP) {
      // 실제 사용자는 페이지를 열면서 서버 시간을 먼저 받아 간다.
      record(win, await call('GET', '/registrations/window', { ip: LOAD_IP(i) }))
    }
    record(reg, await call('POST', '/registrations', { ip: LOAD_IP(i), body: registerBody(i) }))
  }, { label: '등록 진행', every: STUDENTS >= 100 ? 50 : Math.max(1, Math.ceil(STUDENTS / 4)) })

  reg.elapsedMs = elapsed
  win.elapsedMs = elapsed
  printMetrics(reg)
  if (total(win)) printMetrics(win)

  const rows = dbCount(`registration_date='${date}' AND room_number LIKE 'LTS%'`)

  if (!SAME_IP) {
    check(`${STUDENTS}명 전원 201 신규 등록`, cnt(reg, 201) === STUDENTS,
      `201=${cnt(reg, 201)} 200=${cnt(reg, 200)} 429=${cnt(reg, 429)} 그외=${total(reg) - cnt(reg, 201)}`)
    check('정상 사용자는 rate limit 에 걸리지 않음 (429 = 0건)', cnt(reg, 429) + cnt(win, 429) === 0,
      `등록 429=${cnt(reg, 429)}, 창조회 429=${cnt(win, 429)}`)
    check(`DB 등록 건수 == ${STUDENTS}`, rows === STUDENTS, `db=${rows}`)
  } else {
    // 단일 IP: register 규칙(분당 capacity)만큼만 통과해야 한다. 부분 리필분은 허용한다.
    const refilled = Math.floor((elapsed / 1000) * REGISTER_REFILL_PER_SEC)
    const okCount = cnt(reg, 201)
    check('429 rate limit 차단이 실제로 발생', cnt(reg, 429) > 0, `429=${cnt(reg, 429)}`)
    check(`성공 건수가 register 설정(${REGISTER_CAPACITY}회/분)과 일치`,
      okCount >= REGISTER_CAPACITY && okCount <= REGISTER_CAPACITY + refilled,
      `201=${okCount}, 허용범위 ${REGISTER_CAPACITY}~${REGISTER_CAPACITY + refilled} (소요 ${(elapsed / 1000).toFixed(1)}초 리필분 포함)`)
    check('차단된 요청은 DB 에 저장되지 않음', rows === okCount, `db=${rows} 201=${okCount}`)
    check('모든 429 응답에 Retry-After 헤더 + code=RATE_LIMITED',
      reg.retryAfterCount === cnt(reg, 429) && reg.rateLimitedCode === cnt(reg, 429),
      `retry-after=${reg.retryAfterCount} code=${reg.rateLimitedCode} / 429=${cnt(reg, 429)}`)
  }
  check('5xx/연결실패 0건', sum5xx(reg) + sum5xx(win) === 0,
    `등록 ${sum5xx(reg)}건, 창조회 ${sum5xx(win)}건`)

  const sorted = [...reg.samples].sort((a, b) => a - b)
  const p99 = percentile(sorted, 99)
  if (p99 <= P99_BUDGET_MS) {
    check(`등록 p99 응답시간 ${P99_BUDGET_MS}ms 이하`, true, `p99=${p99.toFixed(0)}ms`)
  } else {
    warn(`등록 p99 응답시간이 임계값을 초과`, `p99=${p99.toFixed(0)}ms > ${P99_BUDGET_MS}ms (실패로 처리하지 않음)`)
  }
  return { reg, win }
}

// ── 2. 동시 등록 경합(멱등성) ────────────────────────────────────────────────
async function phaseRace(date) {
  section(`2. 동시 등록 경합 — 같은 사람을 ${RACE_REQUESTS}개 요청으로 동시에 등록`)
  info('교육생이 버튼을 연타하거나 여러 기기에서 동시에 누른 상황. DB 에는 1건만 남아야 한다.')
  clearOwnRateLimits()

  const person = { className: '9반', studentName: '동시등록', roomNumber: ROOM_RACE }
  const m = newMetrics(`동일인 동시 등록 ${RACE_REQUESTS}건`)

  // 진짜 "동시"를 만들기 위해 풀을 쓰지 않고 한꺼번에 발사한다.
  // rate limit 이 개입하면 멱등성 검증이 흐려지므로 요청마다 다른 IP 를 쓴다.
  const started = performance.now()
  const results = await Promise.all(
    Array.from({ length: RACE_REQUESTS }, (_, i) => call('POST', '/registrations', { ip: RACE_IP(i), body: person })))
  m.elapsedMs = performance.now() - started
  results.forEach((r) => record(m, r))
  printMetrics(m)

  const rows = dbCount(`registration_date='${date}' AND room_number='${ROOM_RACE}'`)
  check('DB 에는 정확히 1건만 생성됨 (멱등성)', rows === 1, `db=${rows}`)
  check('201 신규 응답은 정확히 1건', cnt(m, 201) === 1, `201=${cnt(m, 201)}`)
  check(`나머지 ${RACE_REQUESTS - 1}건은 200 + duplicate=true`,
    cnt(m, 200) === RACE_REQUESTS - 1 && results.filter((r) => r.status === 200).every((r) => r.json?.duplicate === true),
    `200=${cnt(m, 200)}`)
  check('경합 상황에서 5xx 0건', sum5xx(m) === 0, `5xx=${sum5xx(m)}`)

  const walForRace = walLoadEntries(date).filter((w) => w.entry?.roomNumber === ROOM_RACE)
  const distinctPersons = new Set(walForRace.map((w) => `${w.entry.className}|${w.entry.studentName}|${w.entry.roomNumber}`))
  info(`WAL 항목 ${walForRace.length}건 → 동일인 판정(personKey) 기준 ${distinctPersons.size}명`)
  check('WAL 은 동일인 기준 1명으로 수렴 (대사 시 중복 복구 없음)', distinctPersons.size === 1,
    `personKey=${distinctPersons.size}`)
  check('경합에서 진 요청도 WAL 상태가 COMMITTED 로 정리됨',
    walForRace.every((w) => w.entry?.status === 'COMMITTED'),
    walForRace.map((w) => w.entry?.status).join(','))
  return m
}

// ── 3. 악의적 과다요청 차단 ──────────────────────────────────────────────────
async function phaseAttack(date) {
  section(`3. 악의적 과다요청 — 단일 IP(${ATTACKER_IP})에서 ${ATTACK_REQUESTS}회 연타`)
  clearOwnRateLimits()

  const m = newMetrics(`공격자 단일 IP ${ATTACK_REQUESTS}건`)
  const items = Array.from({ length: ATTACK_REQUESTS }, (_, i) => i)
  m.elapsedMs = await runPool(items, Math.min(10, CONCURRENCY), async (i) => {
    record(m, await call('POST', '/registrations', {
      ip: ATTACKER_IP,
      body: { className: '9반', studentName: `과다요청${String(i + 1).padStart(3, '0')}`, roomNumber: ROOM_ATTACK(i) },
    }))
  }, { label: '공격 진행', every: 20 })
  printMetrics(m)

  const okCount = cnt(m, 201)
  const refilled = Math.floor((m.elapsedMs / 1000) * REGISTER_REFILL_PER_SEC)
  const rows = dbCount(`registration_date='${date}' AND room_number LIKE 'LTA%'`)

  check('과다요청이 429 로 차단됨', cnt(m, 429) > 0, `429=${cnt(m, 429)}`)
  check('모든 429 응답에 Retry-After 헤더 + code=RATE_LIMITED',
    m.retryAfterCount === cnt(m, 429) && m.rateLimitedCode === cnt(m, 429),
    `retry-after=${m.retryAfterCount} code=${m.rateLimitedCode} / 429=${cnt(m, 429)}`)
  check(`통과 건수가 register 설정(${REGISTER_CAPACITY}회/분)과 일치`,
    okCount >= REGISTER_CAPACITY && okCount <= REGISTER_CAPACITY + refilled,
    `201=${okCount}, 허용범위 ${REGISTER_CAPACITY}~${REGISTER_CAPACITY + refilled}`)
  check('차단된 요청은 DB 에 저장되지 않음', rows === okCount, `db=${rows} 201=${okCount}`)
  check('차단된 요청은 WAL 에도 남지 않음',
    walLoadEntries(date).filter((w) => String(w.entry?.roomNumber).startsWith('LTA')).length === okCount)
  check('공격 중에도 5xx 0건', sum5xx(m) === 0, `5xx=${sum5xx(m)}`)

  // 핵심: 공격이 진행 중이어도 다른 IP 의 정상 사용자는 영향을 받지 않아야 한다.
  const innocent = await call('POST', '/registrations', {
    ip: INNOCENT_IP,
    body: { className: '9반', studentName: '선의사용자', roomNumber: ROOM_INNOCENT },
  })
  check('공격 직후에도 다른 IP 의 정상 사용자는 정상 등록(201)', innocent.status === 201,
    `status=${innocent.status} ${innocent.text.slice(0, 120)}`)
  info(`정상 사용자 응답시간 ${innocent.ms.toFixed(0)}ms`)
  return m
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
}

// ── 5. 200명 명단 렌더링 (발송 준비 비용) ────────────────────────────────────
async function phasePreview(date) {
  section('5. 200명 명단 렌더링 — 22:10 발송 준비 비용 측정')
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
  check('부하로 등록한 인원이 명단에 포함됨',
    String(res.json?.emailText ?? '').includes('부하생0001'), '명단 렌더링 결과에 부하 데이터가 없습니다.')
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
  const rl = clearOwnRateLimits()
  info(`\n정리 완료 — DB ${rows}건, WAL ${walDeleted}건, rate limit 버킷 ${rl}개를 삭제했습니다.`)
  info('※ Redis 통계 카운터(imlate:stats:*)는 되돌리지 않습니다(부하분이 반영된 상태로 남습니다).')
}

// ── 본 시험 ──────────────────────────────────────────────────────────────────
async function run() {
  const startedAt = performance.now()
  console.log(`[36mimlate 부하 테스트[0m  대상=${BASE}`)
  console.log(`  인원 ${STUDENTS}명 · 동시 ${CONCURRENCY} · 모드 ${SAME_IP ? '단일 IP(--same-ip)' : 'IP 분산(기본)'}`
    + ` · p99 임계값 ${P99_BUDGET_MS}ms`)

  const date = await preflight()
  const phases = []
  phases.push(await phaseLoad(date))
  phases.push(await phaseRace(date))
  if (!SAME_IP) {
    phases.push(await phaseAttack(date))
  } else {
    info('\n--same-ip 모드에서는 1단계가 이미 단일 IP 차단을 검증하므로 공격 시나리오를 건너뜁니다.')
  }
  phaseConsistency(date)
  if (!SAME_IP) await phasePreview(date)

  // ── 총계 ──────────────────────────────────────────────────────────────────
  section('6. 전체 요약')
  const all = newMetrics('전체')
  const flat = phases.flatMap((p) => (p.samples ? [p] : Object.values(p)))
  flat.forEach((m) => {
    all.samples.push(...m.samples)
    m.status.forEach((c, s) => all.status.set(s, (all.status.get(s) ?? 0) + c))
    m.blockedBy.forEach((c, s) => all.blockedBy.set(s, (all.blockedBy.get(s) ?? 0) + c))
  })
  all.elapsedMs = performance.now() - startedAt
  printMetrics(all)
  check('전체 구간 5xx/연결실패 0건', sum5xx(all) === 0, `5xx=${sum5xx(all)}`)

  cleanup(date)

  console.log(`\n${'═'.repeat(66)}`)
  console.log(`  결과: ${pass}건 통과, ${fail}건 실패, ${warnings.length}건 경고`
    + ` · 총 소요 ${((performance.now() - startedAt) / 1000).toFixed(1)}초`)
  if (warnings.length) {
    console.log('\n  경고:')
    warnings.forEach((w) => console.log(`   - ${w}`))
  }
  if (fail) {
    console.log('\n  실패 목록:')
    failures.forEach((f) => console.log(`   - ${f}`))
  } else if (!SAME_IP) {
    console.log('\n  다음도 확인해 보세요: node scripts/load-test.mjs --same-ip')
  }
  console.log('═'.repeat(66))
  process.exit(fail ? 1 : 0)
}

run().catch((e) => {
  console.error('\n부하 테스트 중 예외:', e)
  process.exit(2)
})
