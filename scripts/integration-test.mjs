#!/usr/bin/env node
/**
 * imlate 로컬 통합 테스트
 *
 *   실제로 기동 중인 애플리케이션 + 실제 MySQL + 실제 Redis 를 상대로
 *   등록 → WAL → DB → 대사 → 발송 → 조회 → 통계 전 구간을 검증한다.
 *   (단위 테스트가 아니라 "진짜로 돌아가는가"를 보는 시험이다.)
 *
 * 사용법
 *   node scripts/integration-test.mjs
 *   node scripts/integration-test.mjs --drills              # 장애 훈련 포함(Redis / MySQL 정지·복구)
 *   node scripts/integration-test.mjs --base-url http://localhost:8080 --admin-key local-dev-admin-key
 *
 * 옵션
 *   --base-url <URL>        기본 http://localhost:8080
 *   --admin-key <KEY>       기본 local-dev-admin-key (application-local.yml 값)
 *   --cohort <N>            교육생 규모. rate limit 설정이 이 인원을 감당하는지 검사한다. 기본 200
 *   --spam <N>              동일인 도배 최대 시도 수(429 가 나오면 조기 종료). 기본 40
 *   --mysql <컨테이너명>     기본 imlate-mysql
 *   --redis <컨테이너명>     기본 imlate-redis
 *   --db-user/--db-pass/--db-name   기본 imlate/imlate/imlate
 *   --drills                Redis / MySQL 을 실제로 내렸다 올리는 장애 훈련 수행
 *                           (MySQL 훈련은 DB 를 정지한 뒤 다시 살리므로 몇 분이 더 걸린다)
 *   --keep                  끝나고 테스트 데이터를 지우지 않음(눈으로 보고 싶을 때)
 *
 * 주의
 *   - 이 스크립트는 대상 DB 의 데이터를 지웁니다. **로컬 전용**이며,
 *     base-url 이 localhost/127.0.0.1 이 아니면 실행을 거부합니다.
 *   - Docker 로 띄운 MySQL/Redis 컨테이너를 docker exec 로 직접 확인합니다.
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
/** 교육생 규모(R14 기준). rate limit 한도가 이 인원을 감당하는지 설정 단계에서 검사한다. */
const COHORT = intArg('cohort', 200)
/** 동일인 도배 시나리오의 최대 시도 수. 429 가 나오면 그 즉시 멈춘다. */
const SPAM_MAX = intArg('spam', 40)
const MYSQL_CONTAINER = arg('mysql', 'imlate-mysql')
const REDIS_CONTAINER = arg('redis', 'imlate-redis')
const DB_USER = arg('db-user', 'imlate')
const DB_PASS = arg('db-pass', 'imlate')
const DB_NAME = arg('db-name', 'imlate')
const RUN_DRILLS = flag('drills')
const KEEP_DATA = flag('keep')

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE)) {
  console.error(`거부: 이 스크립트는 데이터를 삭제하므로 로컬에서만 실행할 수 있습니다. (base-url=${BASE})`)
  process.exit(2)
}

// ── 결과 집계 ────────────────────────────────────────────────────────────────
let pass = 0
let fail = 0
const failures = []
const skipped = []

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
const section = (t) => console.log(`\n[36m═══ ${t} [0m`)
const info = (t) => console.log(`        ${t}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── HTTP / 인프라 헬퍼 ───────────────────────────────────────────────────────
async function req(method, path, { body, headers = {}, visitor = 'integration-test' } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Visitor-Id': visitor, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* 비-JSON 응답 */
  }
  return { status: res.status, json, text, headers: res.headers }
}

const docker = (container, args) =>
  execFileSync('docker', ['exec', container, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

const mysql = (sql) =>
  docker(MYSQL_CONTAINER, ['mysql', `-u${DB_USER}`, `-p${DB_PASS}`, '-D', DB_NAME, '--default-character-set=utf8mb4', '-N', '-B', '-e', sql])

const redis = (...args) => docker(REDIS_CONTAINER, ['redis-cli', ...args])

const redisKeys = (pattern) =>
  redis('--scan', '--pattern', pattern).split('\n').map((k) => k.trim()).filter(Boolean)

const redisDelPattern = (pattern) => {
  const keys = redisKeys(pattern)
  if (keys.length) redis('del', ...keys)
  return keys.length
}

/**
 * 기동 중인 앱의 **실제 설정값**을 읽는다(local 프로파일이 /actuator/env 를 열어 둔다).
 * 여러 이름을 주면 순서대로 시도하고 처음 찾은 값을 돌려준다. 못 읽으면 null.
 *
 * 한도·시각을 스크립트에 하드코딩하지 않기 위한 장치다.
 * (예전에는 rate limit 한도 "8" 이 박혀 있어서, 한도를 고쳐도 테스트가 같이 거짓말을 했다)
 */
async function envValue(...names) {
  for (const name of names) {
    try {
      const res = await fetch(`${BASE}/actuator/env/${encodeURIComponent(name)}`)
      if (!res.ok) continue
      const json = await res.json()
      const v = json?.property?.value
      if (v !== undefined && v !== null && String(v).trim() !== '') return String(v).trim()
    } catch {
      /* actuator 가 닫혀 있으면 null 로 처리하고 동작 검증에 맡긴다 */
    }
  }
  return null
}

async function envNumber(...names) {
  const v = await envValue(...names)
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
}

// ── 시각 헬퍼 ────────────────────────────────────────────────────────────────
//
// 마감·발송 시각은 **전부 설정값**이고 운영자 요청으로 언제든 앞당겨질 수 있다
// (실제로 22:00/22:10 → 21:45/21:50 으로 앞당겨졌다).
// 그래서 이 스크립트는 시각을 하드코딩하지 않고, 응답·설정에서 읽어 **관계**만 단언한다.

/** "21:45" · "21:45:00" · "2026-08-05T21:45:00+09:00" 에서 하루 중 분(minute of day)을 뽑는다. */
const timeToMinutes = (value) => {
  const m = /(?:^|T)(\d{1,2}):(\d{2})/.exec(String(value ?? ''))
  return m ? Number(m[1]) * 60 + Number(m[2]) : null
}

/** 분(minute of day) → "HH:mm". 로그용. */
const hhmm = (min) =>
  min === null || min === undefined
    ? '?'
    : `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`

/**
 * 6필드 cron("초 분 시 일 월 요일")의 발화 시각을 분 단위 배열로 돌려준다.
 *   "0 50 21 * * *"   → [21*60+50]
 *   "0 5,20 22 * * *" → [22*60+5, 22*60+20]
 * 와일드카드나 스텝이 섞인 시험용 cron(예: 매 분 발화)이면 null 을 돌려 단언을 건너뛴다.
 */
const cronToMinutes = (cron) => {
  const f = String(cron ?? '').trim().split(/\s+/)
  if (f.length !== 6) return null
  const numbers = (field) => (/^\d+(,\d+)*$/.test(field) ? field.split(',').map(Number) : null)
  const minutes = numbers(f[1])
  const hours = numbers(f[2])
  if (!minutes || !hours) return null
  const out = []
  for (const h of hours) for (const m of minutes) out.push(h * 60 + m)
  return out.sort((a, b) => a - b)
}

/**
 * WAL 해시(`imlate:wal:{date}`)의 값들을 JSON 파싱해 객체 배열로 돌려준다.
 * redis-cli 는 tty 가 아닐 때 값을 한 줄에 하나씩 원문 그대로 뱉으므로 줄 단위로 파싱하면 된다.
 * (JSON.parse 를 쓰므로 한글이 UTF-8 원문이든 \uXXXX 이스케이프든 동일하게 복원된다)
 */
const walEntries = (date) => {
  const raw = redis('hvals', `imlate:wal:${date}`)
  if (!raw) return []
  return raw
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line)
      } catch {
        return null
      }
    })
    .filter(Boolean)
}

/**
 * WAL 항목들의 **고유 인원 수**(personKey = `반|이름|호수`).
 *
 * 중복 선행 조회가 WAL append 뒤로 내려간 뒤로는 같은 사람이 재제출할 때마다 walId 가 하나씩
 * 더 쌓인다. 따라서 원시 항목 수(HLEN)는 DB 행 수보다 클 수 있고, DB 와 맞춰 봐야 하는 것은
 * 이 고유 인원 수다(ReconciliationService 도 personKey 로 dedupe 해서 센다).
 */
const walPersonKeys = (entries) =>
  new Set(entries.map((e) => `${e.className}|${e.studentName}|${e.roomNumber}`))

/** WAL 해시를 field(=walId) → entry 쌍으로 읽는다. 정리(HDEL)에는 field 가 필요하다. */
const walFieldEntries = (date) => {
  const raw = redis('hgetall', `imlate:wal:${date}`)
  if (!raw) return []
  const lines = raw.split('\n')
  const out = []
  for (let i = 0; i + 1 < lines.length; i += 2) {
    const field = lines[i].trim()
    try {
      out.push({ field, entry: JSON.parse(lines[i + 1]) })
    } catch {
      out.push({ field, entry: null })
    }
  }
  return out
}

/**
 * 조건에 맞는 WAL 항목을 지운다. 어떤 섹션이 임시로 만든 등록을 흔적 없이 되돌릴 때 쓴다.
 * (뒤 섹션들이 "정확히 N건" 을 단언하므로, 중간 섹션은 자기 쓰레기를 스스로 치워야 한다)
 */
const purgeWal = (date, predicate) => {
  const fields = walFieldEntries(date).filter((w) => predicate(w.entry)).map((w) => w.field)
  for (let i = 0; i < fields.length; i += 100) {
    const chunk = fields.slice(i, i + 100)
    if (chunk.length) redis('hdel', `imlate:wal:${date}`, ...chunk)
  }
  return fields.length
}

/**
 * rate limit 버킷을 **전부** 비워 다음 등록이 한도에 걸리지 않게 한다.
 *
 * 버킷은 이제 IP 버킷(`imlate:rl:{scope}:{ip}`)과 개인 버킷(개인 식별자 해시)의 2단이다.
 * 둘 다 `imlate:rl:` 네임스페이스 아래 있으므로 이 패턴 하나로 함께 지워진다.
 */
const clearRateLimit = () => redisDelPattern('imlate:rl:*')

const admin = (path, method = 'POST') => req(method, path, { headers: { 'X-Admin-Key': ADMIN_KEY } })

/**
 * 통계는 피드백 2번 반영으로 관리자 전용이 되었다(사용자 화면에 노출하지 않는다).
 * 집계 자체는 계속 동작해야 하므로 관리자 키로 확인한다.
 */
const adminStats = async () => (await admin('/stats/summary', 'GET')).json

/** 대사 결과도 /lookup 응답에서 빠지고 관리자 전용 엔드포인트로 옮겨졌다. */
const adminReconciliation = async (date) => (await admin(`/admin/reconciliation?date=${date}`, 'GET')).json

// ── 테스트 데이터 ────────────────────────────────────────────────────────────
const STUDENTS = [
  ['1', '김하늘', '301'], ['1', '박서준', '302'], ['1', '이도윤', '303'],
  ['2', '최지우', '401'], ['2', '정민서', '402'], ['2', '강하준', '403'],
  ['3', '윤서아', '501'], ['3', '임채원', '502'], ['3', '한지훈', '503'],
  ['4', '오유진', '601'], ['4', 'AliceKim', '602'], ['4', '남궁민수', '101'],
]

/**
 * 모든 등록이 쓰는 기본 취소 비밀번호.
 *
 * 시나리오마다 다른 값을 쓰면 "어느 등록이 어느 비밀번호였는지" 추적하느라 테스트가 복잡해진다.
 * 비밀번호가 실제로 검증되는지는 13-1 절에서 <b>다른 값</b>을 넣어 따로 확인한다.
 */
const CANCEL_PASSWORD = '2468'

async function register(className, studentName, roomNumber, opts = {}) {
  const { cancelPassword = CANCEL_PASSWORD, ...rest } = opts
  return req('POST', '/registrations', {
    body: { className, studentName, roomNumber, cancelPassword },
    ...rest,
  })
}

async function cancel(className, studentName, roomNumber, password = CANCEL_PASSWORD, opts = {}) {
  return req('POST', '/registrations/cancel', {
    body: { className, studentName, roomNumber, password },
    ...opts,
  })
}

// ── 사전 점검 ────────────────────────────────────────────────────────────────
async function preflight() {
  section('0. 사전 점검')

  let health
  try {
    health = await fetch(`${BASE}/actuator/health`).then((r) => r.json())
  } catch (e) {
    console.error(`\n애플리케이션에 연결할 수 없습니다: ${BASE}\n  → docker compose up -d 후 gradlew bootRun 이 떠 있는지 확인하세요.\n  (${e.message})`)
    process.exit(2)
  }
  check('애플리케이션 health = UP', health.status === 'UP', JSON.stringify(health.status))
  check('DB 연결 UP', health.components?.db?.status === 'UP')
  check('Redis 연결 UP', health.components?.redis?.status === 'UP')

  const albGroup = await fetch(`${BASE}/actuator/health/alb`).then((r) => r.json()).catch(() => null)
  check('ALB 전용 헬스 그룹 응답', albGroup?.status === 'UP', JSON.stringify(albGroup))

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
  check('MySQL / Redis 컨테이너 접근 가능', true)

  // 깨끗한 상태에서 시작
  mysql('DELETE FROM return_registration; DELETE FROM notification_dispatch; DELETE FROM daily_stat;')
  redis('flushall')
  info('기존 테스트 데이터를 정리했습니다.')
}

// ── 본 시험 ──────────────────────────────────────────────────────────────────
async function run() {
  await preflight()

  // ---------------------------------------------------------------- 1
  section('1. 등록 창 / 서버 시간 (R1)')
  const win = (await req('GET', '/registrations/window')).json
  check('GET /registrations/window 응답', !!win, JSON.stringify(win))
  check('등록 창이 열려 있음', win?.open === true, `open=${win?.open} serverTime=${win?.serverTime}`)
  check('복귀 시각 23:30 노출', String(win?.returnTime).startsWith('23:30'), String(win?.returnTime))
  check('통금 시각 22:30 노출', String(win?.curfewTime).startsWith('22:30'), String(win?.curfewTime))
  check('마감까지 남은 초가 양수', (win?.secondsUntilClose ?? 0) > 0, String(win?.secondsUntilClose))
  check('서버 시간이 KST(+09:00)', /\+09:00$/.test(String(win?.serverTime)), String(win?.serverTime))
  const TODAY = win.date

  // 1-1) 하루 타임라인의 **순서**를 단언한다. 시각 값 자체는 설정이므로 하드코딩하지 않는다.
  //      계약은 "00:00 등록 시작 → 마감 → 사감 발송 → (재시도) → 통금 22:30 → 일괄 개방 23:30" 이다.
  //      운영자 요청으로 마감/발송만 앞당겨졌고(22:00/22:10 → 21:45/21:50),
  //      통금·복귀는 그대로다. 그래서 값이 아니라 순서를 지킨다.
  const opensAtMin = timeToMinutes(win?.opensAt)
  const closesAtMin = timeToMinutes(win?.closesAt)
  const curfewMin = timeToMinutes(win?.curfewTime)
  const returnMin = timeToMinutes(win?.returnTime)
  info(`대상일 = ${TODAY} · 등록 창 ${hhmm(opensAtMin)} ~ ${hhmm(closesAtMin)}`
    + ` · 통금 ${hhmm(curfewMin)} · 일괄 복귀 ${hhmm(returnMin)}`)

  check('등록 시작 < 등록 마감', opensAtMin !== null && closesAtMin !== null && opensAtMin < closesAtMin,
    `opensAt=${hhmm(opensAtMin)} closesAt=${hhmm(closesAtMin)}`)
  check('통금 < 일괄 복귀', curfewMin !== null && returnMin !== null && curfewMin < returnMin,
    `curfew=${hhmm(curfewMin)} return=${hhmm(returnMin)}`)

  // 1-2) 마감 시각이 **설정값 그대로** 응답에 반영되는지(= 어딘가에 하드코딩되지 않았는지).
  const closeTimeProp = await envValue('imlate.registration.close-time')
  if (closeTimeProp === null) {
    info('※ /actuator/env 를 읽지 못해 시각 설정 검사는 건너뜁니다(local 프로파일인지 확인하세요).')
  } else {
    check(`window.closesAt(${hhmm(closesAtMin)}) = 설정값 close-time(${closeTimeProp})`,
      timeToMinutes(closeTimeProp) === closesAtMin,
      '응답이 설정을 따르지 않으면 마감 시각이 코드에 박혀 있다는 뜻이다.')
  }

  // 1-3) "마감 → 발송 → 재시도" 가 전부 통금 전에 끝나는지.
  //      요청을 한 건도 보내지 않고 잡을 수 있는 결함이라 여기서 먼저 본다.
  //
  //      단, 밤늦게 시험할 때는 등록 창을 열어 두려고 IMLATE_REGISTRATION_CLOSE_TIME=23:59 를
  //      쓰라고 안내하고 있다(LOCAL-TESTING §8.5). 그 상태에서는 순서가 당연히 깨지므로
  //      실패가 아니라 **건너뜀**으로 남긴다 — 시험용 설정을 결함으로 보고하면 신호가 죽는다.
  const dispatchCron = await envValue('imlate.notification.dispatch-cron')
  const retryCron = await envValue('imlate.notification.retry-cron')
  const dispatchMins = cronToMinutes(dispatchCron)
  const retryMins = cronToMinutes(retryCron)
  const windowStretched = closesAtMin !== null && curfewMin !== null && closesAtMin >= curfewMin

  if (windowStretched) {
    info(`※ 마감(${hhmm(closesAtMin)})이 통금(${hhmm(curfewMin)})보다 늦습니다 — 시험용으로 등록 창을 늘려 둔 상태로 봅니다.`)
    skipped.push(`1-3. 시각 정합성(마감 → 발송 → 재시도 → 통금) — 등록 창이 ${hhmm(closesAtMin)} 까지 늘어나 있어 건너뜀.`
      + ' 기본 설정으로 재기동해 한 번은 확인할 것.')
  } else if (!dispatchMins) {
    info(`※ 발송 cron(${dispatchCron ?? '?'})이 고정 시각이 아니라 스케줄 정합성 검사는 건너뜁니다(시험용 cron 으로 보입니다).`)
    skipped.push(`1-3. 시각 정합성 — 발송 cron 이 시험용(${dispatchCron ?? '?'})이라 건너뜀.`)
  } else {
    info(`발송 cron ${dispatchCron} → ${dispatchMins.map(hhmm).join(', ')}`
      + ` · 재시도 cron ${retryCron ?? '?'} → ${(retryMins ?? []).map(hhmm).join(', ') || '?'}`)
    check(`등록 마감(${hhmm(closesAtMin)}) < 통금(${hhmm(curfewMin)})`, closesAtMin < curfewMin,
      '문 잠긴 뒤에 등록을 받으면 의미가 없다.')
    check(`사감 발송(${dispatchMins.map(hhmm).join(',')})은 등록 마감(${hhmm(closesAtMin)}) 이후`,
      Math.min(...dispatchMins) > closesAtMin,
      '마감 전에 보내면 마감 직전 등록분이 명단에서 빠진다.')
    check(`사감 발송은 통금(${hhmm(curfewMin)}) 이전`, Math.max(...dispatchMins) < curfewMin,
      '문이 잠긴 뒤에 명단을 받으면 사감님이 쓸 수 없다.')
    if (retryMins?.length) {
      check('실패 채널 재시도는 최초 발송 이후', Math.min(...retryMins) > Math.max(...dispatchMins),
        `retry=${retryMins.map(hhmm).join(',')} dispatch=${dispatchMins.map(hhmm).join(',')}`)
      check(`실패 채널 재시도가 통금(${hhmm(curfewMin)}) 전에 모두 끝남`, Math.max(...retryMins) < curfewMin,
        `retry=${retryMins.map(hhmm).join(',')}`)
    }
  }

  if (!win.open) {
    console.error(`\n등록 창이 닫혀 있어 이후 시험을 진행할 수 없습니다(마감 ${hhmm(closesAtMin)} 이후).`)
    console.error('IMLATE_REGISTRATION_CLOSE_TIME=23:59 로 앱을 재기동한 뒤 다시 실행하세요.')
    process.exit(2)
  }

  // ---------------------------------------------------------------- 2
  section('2. 정상 등록 (R1, R2)')
  const first = STUDENTS.slice(0, 8)
  for (const [c, n, r] of first) {
    const res = await register(c, n, r)
    check(`201 등록: ${c} ${n} ${r}`, res.status === 201 && res.json?.duplicate === false,
      `status=${res.status} ${res.text.slice(0, 120)}`)
  }

  // ---------------------------------------------------------------- 3
  section('3. Rate limiting (R14) — IP 버킷 + 개인 식별자 버킷 2단')
  info('기숙사 공용 와이파이(NAT) 뒤에서는 교육생 전원이 같은 공인 IP 하나로 보인다.')
  info('그래서 IP 버킷은 "한 회선의 대량 폭주 차단" 전용으로 격하하고,')
  info('"같은 사람의 도배"는 요청 본문에서 뽑은 개인 식별자 버킷이 막는다. (SPEC §8)')
  info('※ 이 섹션은 자기가 만든 등록(호수 99xx)을 끝에서 스스로 지운다 — 뒤 섹션의 건수 단언을 지키기 위함.')

  // 3-0) 설정값 검사 — 요청을 보내지 않고도 알 수 있는 결함을 여기서 먼저 잡는다.
  //      한도를 스크립트에 하드코딩하지 않고 기동 중인 앱에서 읽는다.
  const capGlobal = await envNumber('imlate.rate-limit.global.capacity')
  const capRegister = await envNumber('imlate.rate-limit.register.capacity')
  const capPerson = await envNumber(
    'imlate.rate-limit.register-person.capacity',
    'imlate.rate-limit.registerPerson.capacity',
    'imlate.rate-limit.person.capacity')
  info(`적용 중: global(IP) ${capGlobal ?? '?'}/분 · register(IP) ${capRegister ?? '?'}/분`
    + ` · register(개인) ${capPerson ?? '?'}/분`)
  if (capRegister === null || capGlobal === null) {
    info('※ /actuator/env 를 읽지 못해 설정값 검사는 건너뜁니다(local 프로파일인지 확인하세요).')
  } else {
    check(`register(IP) 한도(${capRegister}) ≥ 교육생 규모(${COHORT})`, capRegister >= COHORT,
      '공용 와이파이 뒤에서 전원이 IP 하나를 공유하므로, 이 값이 인원보다 작으면 정상 사용자가 막힌다.')
    check(`global(IP) 한도(${capGlobal}) ≥ 교육생 규모 × 2 (${COHORT * 2})`, capGlobal >= COHORT * 2,
      '학생 한 명이 최소 2회(마감 조회 + 등록) 호출한다.')
  }
  if (capPerson === null) {
    info('※ 개인 버킷 설정 프로퍼티를 찾지 못했습니다. 아래 3-2 동작 검증이 판정 기준입니다.')
  } else {
    check(`register(개인) 한도(${capPerson}) < register(IP) 한도(${capRegister ?? '?'})`,
      capRegister !== null && capPerson < capRegister,
      '개인 버킷이 IP 버킷만큼 크면 도배를 전혀 막지 못한다.')
  }

  // 3-1) ★ 같은 IP · 다른 사람 → 통과해야 한다 (이번에 고친 결함의 회귀 방지)
  //      §2 에서 이미 8명이 같은 클라이언트로 등록했다. 예전 한도(register = IP당 8회/분)에서는
  //      바로 이 다음 요청부터 429 가 났고, 그것이 "공용 와이파이에서 9번째 학생부터 막힌다"는
  //      운영 불가 결함이었다. 여기가 그 회귀를 잡는 자리다.
  const SAME_IP_OTHERS = [
    ['9', '동일아이피갑', '9901'],
    ['9', '동일아이피을', '9902'],
    ['9', '동일아이피병', '9903'],
  ]
  for (const [c, n, r] of SAME_IP_OTHERS) {
    const res = await register(c, n, r)
    check(`★ 같은 IP · 다른 사람 → 201 통과: ${n}`, res.status === 201,
      `status=${res.status} — 429 라면 IP 하나로만 버킷을 만들고 있다(공용 와이파이에서 정상 사용자가 막힌다).`)
  }

  // 3-2) ★ 같은 IP · 같은 사람 반복 → 429 로 막혀야 한다 (개인 식별자 버킷)
  // 본문은 반드시 **검증을 통과하는** 값이어야 한다. 필수 항목(취소 비밀번호)이 빠지면
  // 컨트롤러의 @Valid 가 400 으로 먼저 잘라내서, 정작 보려던 rate limit 판정에 닿지 못한다.
  const SPAM = { className: '9', studentName: '도배사용자', roomNumber: '9904', cancelPassword: CANCEL_PASSWORD }
  let spamFirst = null
  let spamBlocked = null
  let spamAttempts = 0
  let spamAccepted = 0
  let spamLastStatus = 0
  for (let i = 0; i < SPAM_MAX; i++) {
    const res = await req('POST', '/registrations', { body: SPAM })
    spamAttempts++
    spamLastStatus = res.status
    if (!spamFirst) spamFirst = res
    if (res.status === 429) {
      spamBlocked = res
      break
    }
    if (res.status === 200 || res.status === 201) spamAccepted++
    else break // 예상 밖 응답이면 더 보내지 않는다(아래 단언이 실패로 드러낸다)
  }
  check('★ 같은 IP · 같은 사람 반복 → 429 차단', !!spamBlocked,
    `${spamAttempts}회 보냈으나 429 없음(통과 ${spamAccepted}건, 마지막 status=${spamLastStatus})`
    + ' — 개인 식별자 버킷이 동작하지 않는다.')
  check('첫 요청은 반드시 통과한다 (정상 사용자를 막지 않는다)', spamFirst?.status === 201,
    `status=${spamFirst?.status}`)
  if (capPerson !== null) {
    check(`통과 건수(${spamAccepted})가 개인 한도(${capPerson}) 이하`, spamAccepted <= capPerson + 1,
      `통과=${spamAccepted} 한도=${capPerson} (+1 은 시험 중 리필 여유)`)
  }
  if (spamBlocked) {
    check('code = RATE_LIMITED', spamBlocked.json?.code === 'RATE_LIMITED', spamBlocked.text.slice(0, 100))
    check('Retry-After 헤더', !!spamBlocked.headers.get('retry-after'))
    check('X-RateLimit-Limit 헤더', !!spamBlocked.headers.get('x-ratelimit-limit'))
    check('X-RateLimit-Remaining 헤더', spamBlocked.headers.get('x-ratelimit-remaining') !== null)
  }
  check('도배해도 DB 행은 1건 (멱등 + 차단된 요청은 저장되지 않음)',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE student_name='도배사용자'`)) === 1)
  const spamWal = walFieldEntries(TODAY).filter((w) => w.entry?.roomNumber === '9904').length
  check('차단된 요청은 WAL 에도 남지 않음 (WAL 항목 수 == 통과 건수)',
    spamWal === spamAccepted, `wal=${spamWal} 통과=${spamAccepted}`)

  // 3-3) ★ X-Forwarded-For 위조로 리미터를 우회할 수 없어야 한다.
  //      로컬은 imlate.rate-limit.trusted-proxies 가 비어 있으므로 XFF 를 신뢰하지 않는다.
  //      (운영에서는 ALB 가 이 헤더를 덮어쓰지만, 신뢰 목록이 비면 어차피 무시한다)
  const FORGED_IP = '203.0.113.77' // RFC 5737 문서용 대역 — 실제 주소와 충돌하지 않는다
  const forged = await req('POST', '/registrations', {
    body: SPAM,
    headers: { 'X-Forwarded-For': FORGED_IP, 'X-Real-IP': FORGED_IP },
  })
  if (spamBlocked) {
    check('★ X-Forwarded-For 를 위조해도 차단이 풀리지 않음 (429 유지)', forged.status === 429,
      `status=${forged.status} — 위조 헤더로 새 버킷이 만들어졌다면 리미터를 우회할 수 있다는 뜻이다.`)
  }
  const forgedKeys = redisKeys(`imlate:rl:*:${FORGED_IP}`)
  check('★ 위조 IP 로 만들어진 rate limit 버킷이 없음 (XFF 를 클라이언트 식별에 쓰지 않는다)',
    forgedKeys.length === 0,
    `${forgedKeys.join(', ')} — trusted-proxies 가 비어 있는데 XFF 를 신뢰하고 있다.`)

  // 3-4) 이 섹션이 만든 임시 등록을 DB/WAL 에서 되돌린다(뒤 섹션의 "정확히 N건" 단언 보호).
  const rlRows = Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE room_number LIKE '99%'`))
  mysql(`DELETE FROM return_registration WHERE room_number LIKE '99%'`)
  const rlWal = purgeWal(TODAY, (e) => String(e?.roomNumber ?? '').startsWith('99'))

  // 등록 통계 카운터도 함께 되돌린다.
  // 통계는 RegistrationCreatedEvent 로 INCR 되므로 DB 행을 지워도 자동으로 줄지 않는다.
  // 이걸 빼먹으면 §12 의 "오늘 등록 수 = 13" 이 이 섹션이 만든 임시 등록만큼 부풀어 실패한다.
  if (rlRows > 0) {
    redis('decrby', `imlate:stats:reg:${TODAY}`, String(rlRows))
    redis('decrby', 'imlate:stats:reg:total', String(rlRows))
  }

  clearRateLimit()
  info(`섹션 3 임시 데이터 정리: DB ${rlRows}건, WAL ${rlWal}건, 통계 카운터 ${rlRows} 차감 — 이후 단언은 §2 의 8건 기준 그대로.`)

  // ---------------------------------------------------------------- 4
  section('4. 입력 검증')
  const cases = [
    ['빈 반', { className: '', studentName: '홍길동', roomNumber: '101' }],
    ['빈 이름', { className: '1', studentName: '   ', roomNumber: '101' }],
    ['스크립트 태그', { className: '1', studentName: '<script>x</script>', roomNumber: '101' }],
    ['20자 초과', { className: '1', studentName: '가'.repeat(21), roomNumber: '101' }],
    ['개행 문자', { className: '1', studentName: '홍길동\n관리자', roomNumber: '101' }],
  ]
  for (const [label, body] of cases) {
    const res = await req('POST', '/registrations', { body })
    check(`${label} → 400 거부`, res.status === 400, `status=${res.status}`)
    clearRateLimit()
  }

  // WAL append 는 정규화·검증을 **통과한 뒤에만** 일어난다(R7 쓰기 순서 2→3단계).
  // 잘못된 입력이나 429 로 막힌 요청이 WAL 을 오염시키면 대사가 유령 인원을 복구하게 된다.
  // (§3 이 만든 99xx 항목은 §3-4 에서 스스로 지웠으므로, 여기 기준값은 여전히 §2 의 8건이다)
  const walAfterInvalid = Number(redis('hlen', `imlate:wal:${TODAY}`))
  check('검증 실패·429 요청은 WAL 에 남지 않음 (정상 등록 8건 그대로)',
    walAfterInvalid === 8, `hlen=${walAfterInvalid}`)

  // ---------------------------------------------------------------- 5
  section('5. 멱등성 — 같은 사람이 몇 번을 다시 눌러도 명단에는 한 번만 (운영자 확인 요청 항목)')
  info('계약: (registration_date, class_name, student_name, room_number) 유니크 제약 + 선행 조회.')
  info('     재제출은 새 행을 만들지 않고 200 + duplicate=true 로 돌아온다. 이 동작은 깨지면 안 된다.')

  // 5-0) DB 유니크 제약이 실제로 걸려 있는지부터 본다.
  //      선행 조회는 경합(같은 사람이 동시에 두 번 제출)에서 새는 순간이 있고, 마지막 방어선은 DB 다.
  const uniqueIndexes = mysql(
    `SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)`
    + ` FROM information_schema.STATISTICS`
    + ` WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='return_registration'`
    + ` AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY' GROUP BY INDEX_NAME`)
    .split('\n').map((s) => s.trim()).filter(Boolean)
  check('DB 유니크 제약 (registration_date, class_name, student_name, room_number) 존재',
    uniqueIndexes.includes('registration_date,class_name,student_name,room_number'),
    `실제 유니크 인덱스: ${uniqueIndexes.join(' | ') || '없음'}`)

  // 5-1) ★ 같은 사람을 5회 재제출한다. 2번째부터는 전부 200 + duplicate=true 여야 하고,
  //      DB 행은 정확히 1건, 그것도 **최초에 만들어진 그 행(id 동일)** 이어야 한다.
  const [DUP_CLASS, DUP_NAME, DUP_ROOM] = STUDENTS[0]   // §2 에서 이미 201 로 등록된 사람
  const dupWhere = `registration_date='${TODAY}' AND student_name='${DUP_NAME}' AND room_number='${DUP_ROOM}'`
  const idBefore = mysql(`SELECT id FROM return_registration WHERE ${dupWhere}`)
  const registeredAtBefore = mysql(`SELECT registered_at FROM return_registration WHERE ${dupWhere}`)

  const REPEAT = 5
  const dupStatuses = []
  let dupOk = 0
  for (let i = 1; i <= REPEAT; i++) {
    clearRateLimit()   // 개인 버킷에 걸려 429 가 나면 멱등성이 아니라 리미터를 시험하게 된다(§3 에서 이미 검증했다)
    const res = await register(DUP_CLASS, DUP_NAME, DUP_ROOM)
    dupStatuses.push(res.status)
    if (res.status === 200 && res.json?.duplicate === true) dupOk++
  }
  check(`★ 같은 사람 ${REPEAT}회 재제출 → 전부 200 + duplicate=true`, dupOk === REPEAT,
    `statuses=[${dupStatuses.join(', ')}] (201 이 섞이면 새 행이 생겼다는 뜻)`)

  const dupRows = Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE ${dupWhere}`))
  check(`★ ${REPEAT}회 재제출 후에도 그 사람의 DB 행은 정확히 1건`, dupRows === 1, `rows=${dupRows}`)

  const idAfter = mysql(`SELECT id FROM return_registration WHERE ${dupWhere}`)
  check('★ 새 행이 만들어지지 않았다 (id 불변)', !!idBefore && idAfter === idBefore,
    `before=${idBefore || '없음'} after=${idAfter || '없음'}`)
  check('최초 등록 시각이 덮어써지지 않았다 (registered_at 불변)',
    !!registeredAtBefore && mysql(`SELECT registered_at FROM return_registration WHERE ${dupWhere}`) === registeredAtBefore,
    `before=${registeredAtBefore}`)
  check('전체 등록 건수도 그대로 8건',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 8)
  clearRateLimit()

  // 5-2) 공백 정규화도 같은 사람으로 취급되는지
  const normalized = await register(` ${DUP_CLASS} `, DUP_NAME, DUP_ROOM)
  check('앞뒤 공백은 정규화되어 같은 사람으로 인식', normalized.json?.duplicate === true,
    JSON.stringify(normalized.json))
  check('공백만 다른 재제출도 행을 늘리지 않음',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 8)
  clearRateLimit()

  // 5-3) 같은 사람이 **동시에** 5번 제출해도(브라우저 연타·네트워크 재시도) 행은 1건이어야 한다.
  //      선행 조회만으로는 새는 구간이며, 여기서 유니크 제약이 실제로 일하는지 드러난다.
  const CONCURRENT = 5
  const raceStatuses = (await Promise.all(
    Array.from({ length: CONCURRENT }, () => register(DUP_CLASS, DUP_NAME, DUP_ROOM))
  )).map((r) => r.status)
  check(`★ 동시 재제출 ${CONCURRENT}건에도 DB 행 1건 유지`,
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE ${dupWhere}`)) === 1,
    `statuses=[${raceStatuses.join(', ')}]`)
  check('동시 재제출이 500 을 내지 않는다 (유니크 위반을 멱등 응답으로 흡수)',
    raceStatuses.every((s) => s === 200 || s === 201 || s === 429),
    `statuses=[${raceStatuses.join(', ')}]`)
  clearRateLimit()

  // 나머지 등록
  for (const [c, n, r] of STUDENTS.slice(8)) {
    const res = await register(c, n, r)
    check(`201 등록: ${c} ${n}`, res.status === 201, `status=${res.status}`)
    clearRateLimit()
  }

  // ---------------------------------------------------------------- 6
  section('6. WAL(Redis) ↔ DB 이중 기록 (R7)')
  const walKey = `imlate:wal:${TODAY}`
  const dbRows6 = Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`))
  check('DB 등록 12건', dbRows6 === 12, `db=${dbRows6}`)

  // WAL 은 중복 선행 조회보다 **먼저** 기록된다(R7 새 순서). 그래서 같은 사람이 재제출하면
  // 그때마다 walId 가 하나씩 더 쌓여 원시 항목 수(HLEN)가 DB 행 수보다 많아진다. **이는 정상이다.**
  // 맞춰 봐야 하는 것은 personKey 기준 고유 인원 수이며, 대사도 그 기준으로 센다.
  const wal6 = walEntries(TODAY)
  const hlen6 = Number(redis('hlen', walKey))
  check('WAL 파싱 가능한 항목 수 = HLEN', wal6.length === hlen6, `parsed=${wal6.length} hlen=${hlen6}`)
  check('WAL 원시 항목 수 ≥ DB 행 수 (중복 재제출분이 더 쌓일 수 있음)',
    hlen6 >= dbRows6, `hlen=${hlen6} db=${dbRows6}`)
  const persons6 = walPersonKeys(wal6)
  check('WAL 고유 인원(personKey) 수 = DB 행 수 12',
    persons6.size === dbRows6 && persons6.size === 12, `distinct=${persons6.size} db=${dbRows6}`)
  info(`WAL 원시 ${hlen6}건 / 고유 인원 ${persons6.size}명 — 차이 ${hlen6 - persons6.size}건은 중복 재제출분입니다(정상).`)

  const ttl = Number(redis('ttl', walKey))
  check('WAL 키에 TTL 설정됨', ttl > 0, `ttl=${ttl}s`)
  // 중복 재제출분도 "DB 에 그 사람이 이미 있다"가 확인된 항목이므로 COMMITTED 로 정리된다.
  const notCommitted6 = wal6.filter((e) => e.status !== 'COMMITTED')
  check('WAL 전 건 COMMITTED (중복 재제출분 포함)', notCommitted6.length === 0,
    JSON.stringify(notCommitted6.map((e) => `${e.studentName}:${e.status}`)))
  check('WAL 에 walId 가 DB 와 연결됨',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}' AND wal_id IS NOT NULL`)) === 12)

  // ---------------------------------------------------------------- 7
  section('7. DB 행 유실 → WAL 복구 (R8) · 통계 재집계 없음')
  const statsBefore = await adminStats()
  const victim = mysql(`SELECT CONCAT(class_name,'/',student_name,'/',room_number) FROM return_registration WHERE registration_date='${TODAY}' ORDER BY id DESC LIMIT 1`)
  mysql(`DELETE FROM return_registration WHERE registration_date='${TODAY}' ORDER BY id DESC LIMIT 1`)
  info(`DB 에서 강제 삭제: ${victim} (WAL 상태는 COMMITTED)`)
  check('삭제 직후 DB 11건',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 11)

  const recovered = await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)
  check('대사 포함 발송 200', recovered.status === 200, `status=${recovered.status} ${recovered.text.slice(0, 160)}`)
  check('WAL 에서 DB 로 자동 복구 (12건)',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 12)
  const statsAfter = await adminStats()
  check('COMMITTED 복구는 등록 통계를 재집계하지 않음',
    statsAfter?.todayRegistrations === statsBefore?.todayRegistrations,
    `before=${statsBefore?.todayRegistrations} after=${statsAfter?.todayRegistrations}`)

  // ---------------------------------------------------------------- 8
  section('8. DB 쓰기 실패분(WAL PENDING) 복구 · 통계 집계됨')
  const pendingId = 'pending-wal-0000-0000-000000000001'
  const pendingEntry = JSON.stringify({
    walId: pendingId, registrationDate: TODAY, className: '5', studentName: '유실복구',
    roomNumber: '701', registeredAt: `${TODAY}T21:00:00`, status: 'PENDING', clientIp: '127.0.0.1',
  })
  redis('hset', walKey, pendingId, pendingEntry)
  info('WAL 에만 존재하는 PENDING 항목을 주입했습니다(= 최초 DB INSERT 가 실패한 상황).')
  const beforePending = (await adminStats())?.todayRegistrations

  const rec2 = await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)
  check('대사 재실행 200', rec2.status === 200)
  check('PENDING 항목이 DB 로 복구됨',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE wal_id='${pendingId}'`)) === 1)
  const afterPending = (await adminStats())?.todayRegistrations
  check('PENDING 복구는 등록 통계에 집계됨', afterPending === beforePending + 1,
    `before=${beforePending} after=${afterPending}`)
  check('총 13명', Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 13)

  // ---------------------------------------------------------------- 9
  section('9. 사감 발송 (R3, R9)')
  mysql(`DELETE FROM notification_dispatch`)
  const dispatch = (await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)).json
  check('발송 대상 13명', dispatch?.targetCount === 13, JSON.stringify(dispatch?.targetCount))
  check('SMS 성공 = 사감 수', dispatch?.smsSuccess >= 1, JSON.stringify(dispatch?.smsSuccess))
  check('이메일 성공 = 사감 수', dispatch?.emailSuccess >= 1, JSON.stringify(dispatch?.emailSuccess))
  check('실패 0건', dispatch?.smsFailed === 0 && dispatch?.emailFailed === 0, JSON.stringify(dispatch))
  const rows = Number(mysql(`SELECT COUNT(*) FROM notification_dispatch WHERE dispatch_date='${TODAY}' AND status='SUCCESS'`))
  check('발송 이력이 채널별로 남음 (사감수 × 2)', rows === (dispatch.smsSuccess + dispatch.emailSuccess), `rows=${rows}`)

  const again = (await admin(`/admin/notifications/dispatch?date=${TODAY}`)).json
  check('force 없이 재발송하면 중복 방지로 skip', again?.skipped === true, JSON.stringify(again))
  check('skip 사유 = ALREADY_SENT', again?.skipReason === 'ALREADY_SENT', String(again?.skipReason))

  const emptyDay = '2020-01-01'
  const empty = (await admin(`/admin/notifications/dispatch?date=${emptyDay}&force=true`)).json
  check('0명이면 발송하지 않음', empty?.skipped === true && empty?.skipReason === 'NO_REGISTRATION',
    JSON.stringify(empty))
  check('0명일 때 이력도 남지 않음',
    Number(mysql(`SELECT COUNT(*) FROM notification_dispatch WHERE dispatch_date='${emptyDay}' AND status='SUCCESS'`)) === 0)

  // ---------------------------------------------------------------- 10
  section('10. 발송 문구 검수 (R4)')
  const preview = (await admin(`/admin/notifications/preview?date=${TODAY}`)).json
  const sms = String(preview?.smsBody ?? '')
  const mailText = String(preview?.emailText ?? '')
  const mailHtml = String(preview?.emailHtml ?? '')
  check('문자에 총원 표기', /13명/.test(sms), sms.slice(0, 60))
  check('문자에 23:30 안내', sms.includes('23:30'))
  check('문자에 22:30 잠김 안내', sms.includes('22:30'))
  check('문자에 조회 URL', /https?:\/\/\S+lookup\?date=/.test(sms + mailText))
  check('이메일에 반·이름·호수 모두 포함',
    mailText.includes('1') && mailText.includes('김하늘') && mailText.includes('301'))
  // 피드백 2번: 검증·통계는 사감에게 보여주지 않는다. 다시 들어가면 회귀다.
  check('문자에 검증(대사) 문구 없음', !/검증|WAL|대사/.test(sms), sms.match(/검증.*/)?.[0] ?? '')
  check('문자에 통계 문구 없음', !/통계|방문자/.test(sms), sms.match(/통계.*/)?.[0] ?? '')
  check('이메일에 검증 섹션 없음', !/검증 결과|WAL/.test(mailText))
  check('이메일에 통계 섹션 없음', !/\[통계\]|방문자/.test(mailText))
  check('이메일 HTML 에 UTF-8 명시', /utf-8/i.test(mailHtml))
  check('한글이 깨지지 않음(치환문자 없음)', !/�/.test(sms + mailText + mailHtml))

  // 10-2) ★ 운영자 요청 문구 — 수신 전용(답장 불가) 안내 + 문의처.
  //       사감님이 문자에 답장해도 아무도 읽지 않으므로, 두 채널 모두에 반드시 들어가야 한다.
  //       문의처는 설정값(imlate.notification.contact-*)이므로 여기서도 하드코딩하지 않고 읽어서 비교한다.
  const contactEmail = (await envValue('imlate.notification.contact-email')) ?? 'khdev07@naver.com'
  const contactName = (await envValue('imlate.notification.contact-name')) ?? 'SKALA 운영진'
  const noReplyNotice = /수신\s*전용|발신\s*전용|답장|회신/
  info(`문의처 설정: ${contactName} / ${contactEmail}`)

  check('★ 문자에 수신 전용(답장 불가) 안내', noReplyNotice.test(sms),
    '발신번호가 수신 전용이라 답장이 불가하다는 문구가 없다.')
  check(`★ 문자에 문의처 이메일(${contactEmail})`, sms.includes(contactEmail), sms.slice(-200))
  check(`★ 문자에 문의처 이름(${contactName})`, sms.includes(contactName), sms.slice(-200))

  check('★ 이메일 본문에 수신 전용(답장 불가) 안내', noReplyNotice.test(mailText),
    mailText.slice(-300))
  check(`★ 이메일 본문에 문의처 이메일(${contactEmail})`, mailText.includes(contactEmail), mailText.slice(-300))
  check(`★ 이메일 본문에 문의처 이름(${contactName})`, mailText.includes(contactName), mailText.slice(-300))
  check('★ 이메일 HTML 에도 수신 전용 안내 + 문의처', noReplyNotice.test(mailHtml) && mailHtml.includes(contactEmail),
    '텍스트 파트에만 넣고 HTML 파트를 빠뜨리는 실수가 잦다.')

  const urlMatch = (sms + '\n' + mailText).match(/https?:\/\/[^\s"'<>]+lookup\?date=[^\s"'<>]+/)
  const token = urlMatch ? new URL(urlMatch[0]).searchParams.get('token') : null
  check('조회 링크에서 토큰 추출', !!token)

  info('─── 사감님이 받는 문자 원문 ───')
  sms.split('\n').forEach((l) => console.log(`        │ ${l}`))

  // ---------------------------------------------------------------- 11
  section('11. 조회 페이지 데이터 + 토큰 보안 (R8)')
  const lookup = await req('GET', `/lookup?date=${TODAY}&token=${encodeURIComponent(token ?? '')}`)
  check('정상 토큰 → 200', lookup.status === 200, `status=${lookup.status}`)
  check('명단 13건', lookup.json?.items?.length === 13, `len=${lookup.json?.items?.length}`)
  check('항목에 반/이름/호수 존재',
    !!lookup.json?.items?.[0]?.className && !!lookup.json?.items?.[0]?.studentName && !!lookup.json?.items?.[0]?.roomNumber)
  // 피드백 2번: 조회 응답에서 검증·통계가 빠졌는지 확인(다시 들어오면 회귀)
  check('조회 응답에 verification 없음', lookup.json?.verification === undefined,
    JSON.stringify(lookup.json?.verification))
  check('조회 응답에 stats 없음', lookup.json?.stats === undefined, JSON.stringify(lookup.json?.stats))

  // 대사 자체는 계속 수행되어야 한다 — 관리자 전용 엔드포인트로 확인
  const adminRec = await adminReconciliation(TODAY)
  check('관리자 대사 조회 정상', ['CONSISTENT', 'RECOVERED'].includes(adminRec?.status),
    JSON.stringify(adminRec?.status))
  check('DB/WAL 카운트 일치(관리자 조회)', adminRec?.dbCount === adminRec?.walCount,
    JSON.stringify({ db: adminRec?.dbCount, wal: adminRec?.walCount }))
  check('반별 집계 존재', (lookup.json?.byClass?.length ?? 0) >= 4)
  check('명단 항목에 등록시각 포함', !!lookup.json?.items?.[0]?.registeredAt)

  check('위조 토큰 → 403', (await req('GET', `/lookup?date=${TODAY}&token=forged`)).status === 403)
  check('토큰 없음 → 400/403', [400, 403].includes((await req('GET', `/lookup?date=${TODAY}`)).status))
  check('다른 날짜 토큰 재사용 → 403',
    (await req('GET', `/lookup?date=2020-01-01&token=${encodeURIComponent(token ?? '')}`)).status === 403)

  // ---------------------------------------------------------------- 12
  section('12. 통계 (R15)')
  check('통계는 인증 없이 접근 불가(피드백 2)',
    [401, 403].includes((await req('GET', '/stats/summary')).status))
  const stats = await adminStats()
  check('오늘 등록 수 = 13', stats?.todayRegistrations === 13, JSON.stringify(stats))
  check('방문자 수 집계됨', (stats?.totalVisitors ?? 0) >= 1, String(stats?.totalVisitors))
  check('페이지뷰 집계됨', (stats?.totalPageViews ?? 0) > 10, String(stats?.totalPageViews))
  check('Redis HLL 일자별 방문자', Number(redis('pfcount', `imlate:stats:uv:${TODAY}`)) >= 1)
  check('일자별 통계는 관리자 키 필요',
    [401, 403].includes((await req('GET', `/stats/daily?from=${TODAY}&to=${TODAY}`)).status))
  check('관리자 키로는 일자별 통계 조회 가능',
    (await admin(`/stats/daily?from=${TODAY}&to=${TODAY}`, 'GET')).status === 200)

  // ---------------------------------------------------------------- 13
  section('13. 관리 API 보호')
  check('키 없음 → 401', (await req('POST', `/admin/notifications/dispatch?date=${TODAY}`)).status === 401)
  check('잘못된 키 → 401',
    (await req('POST', `/admin/notifications/dispatch?date=${TODAY}`, { headers: { 'X-Admin-Key': 'wrong' } })).status === 401)
  check('이력 조회는 키가 있어야 200', (await admin(`/admin/notifications?date=${TODAY}`, 'GET')).status === 200)

  // ---------------------------------------------------------------- 13-1
  //
  // 취소는 **남의 등록을 지울 수 있는 유일한 경로**다. 명단에서 빠진 교육생은 22:30 에 문이 잠기면
  // 밖에서 밤을 새게 되므로, "잘못 취소되는 것"이 "취소가 안 되는 것"보다 훨씬 나쁜 실패다.
  // 그래서 여기서는 성공 경로보다 **막히는 경로**를 더 촘촘히 본다.
  section('13-1. 등록 취소 (비밀번호 본인 확인)')
  clearRateLimit()

  const CANCEL_TARGET = ['9', '취소테스트', '901']
  const beforeCancelCount = Number(mysql(
    `SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}' AND cancelled_at IS NULL`))

  check('취소 시험용 등록 생성', (await register(...CANCEL_TARGET)).status === 201)

  // --- 막혀야 하는 경로들 ---
  const wrongPassword = await cancel(...CANCEL_TARGET, '0000')
  check('비밀번호가 틀리면 취소되지 않는다 (400 CANCEL_REJECTED)',
    wrongPassword.status === 400 && wrongPassword.json?.code === 'CANCEL_REJECTED',
    `status=${wrongPassword.status} code=${wrongPassword.json?.code}`)
  check('비밀번호가 틀린 뒤에도 등록은 그대로 살아 있다',
    mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ` AND student_name='${CANCEL_TARGET[1]}' AND cancelled_at IS NULL`) === '1')

  const noSuchPerson = await cancel('9', '존재하지않는사람', '999')
  check('없는 등록을 취소해도 같은 코드로 답한다 (등록 여부 비노출)',
    noSuchPerson.status === 400 && noSuchPerson.json?.code === 'CANCEL_REJECTED',
    `status=${noSuchPerson.status} code=${noSuchPerson.json?.code}`)
  check('없는 등록과 비밀번호 오류의 응답 문구가 완전히 같다',
    noSuchPerson.json?.message === wrongPassword.json?.message,
    `없음="${noSuchPerson.json?.message}" / 틀림="${wrongPassword.json?.message}"`)

  const badFormat = await cancel(...CANCEL_TARGET, '12')
  check('비밀번호 형식이 틀리면 VALIDATION_FAILED (대입 시도로 세지 않는다)',
    badFormat.status === 400 && badFormat.json?.code === 'VALIDATION_FAILED',
    `status=${badFormat.status} code=${badFormat.json?.code}`)

  // --- 통과해야 하는 경로 ---
  const cancelled = await cancel(...CANCEL_TARGET)
  check('네 값이 모두 맞으면 취소된다 (200)', cancelled.status === 200, `status=${cancelled.status}`)
  check('취소 응답에 이름·호수를 되돌려주지 않는다',
    cancelled.json?.studentName === undefined && cancelled.json?.roomNumber === undefined,
    JSON.stringify(cancelled.json))

  // 소프트 삭제 — 행은 남고 cancelled_at 만 채워진다.
  check('행을 지우지 않고 cancelled_at 만 채운다 (소프트 삭제)',
    mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ` AND student_name='${CANCEL_TARGET[1]}' AND cancelled_at IS NOT NULL`) === '1')
  check('취소분은 명단 인원 수에서 빠진다',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ' AND cancelled_at IS NULL')) === beforeCancelCount,
    `취소 전 ${beforeCancelCount}명`)

  const cancelAgain = await cancel(...CANCEL_TARGET)
  check('이미 취소된 등록을 다시 취소해도 200 + alreadyCancelled=true (멱등)',
    cancelAgain.status === 200 && cancelAgain.json?.alreadyCancelled === true,
    `status=${cancelAgain.status} alreadyCancelled=${cancelAgain.json?.alreadyCancelled}`)

  // --- 되살리기: 취소한 사람이 다시 등록할 수 있어야 한다 ---
  //     유니크 제약 (일자,반,이름,호수) 이 취소된 행에도 걸려 있어, 되살리기가 없으면
  //     "이미 등록됨"으로 막혀 영영 재등록을 못 한다.
  const reRegister = await register(...CANCEL_TARGET, { cancelPassword: '1357' })
  check('취소한 사람이 다시 등록하면 201 (되살리기)', reRegister.status === 201, `status=${reRegister.status}`)
  check('되살아난 등록은 다시 명단에 잡힌다',
    mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ` AND student_name='${CANCEL_TARGET[1]}' AND cancelled_at IS NULL`) === '1')
  check('행이 새로 생기지 않고 기존 행이 되살아난다 (유니크 제약 유지)',
    mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ` AND student_name='${CANCEL_TARGET[1]}'`) === '1')

  const oldPassword = await cancel(...CANCEL_TARGET, CANCEL_PASSWORD)
  check('되살릴 때 비밀번호가 교체되어 예전 비밀번호로는 취소되지 않는다',
    oldPassword.status === 400, `status=${oldPassword.status}`)
  check('새 비밀번호로는 취소된다', (await cancel(...CANCEL_TARGET, '1357')).status === 200)

  // --- 대사가 취소분을 되살리지 않는가 (가장 조용히 깨지는 지점) ---
  //     취소는 소프트 삭제라 DB 행이 남지만 Redis WAL 항목도 그대로 남아 있다.
  //     둘을 그냥 비교하면 "WAL 에만 있다"고 오판해 취소한 등록을 명단에 다시 올린다.
  const walStillHasTarget = redis('HLEN', `imlate:wal:${TODAY}`)
  info(`대사 직전 WAL 항목 수 = ${walStillHasTarget} (취소한 사람의 WAL 기록도 아직 남아 있다)`)
  await admin(`/admin/reconciliation?date=${TODAY}`, 'POST').catch(() => null)
  const afterReconcile = await adminReconciliation(TODAY)
  check('대사 후에도 취소한 등록이 되살아나지 않는다',
    mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`
      + ` AND student_name='${CANCEL_TARGET[1]}' AND cancelled_at IS NOT NULL`) === '1',
    JSON.stringify(afterReconcile))
  check('취소분을 뺀 DB/WAL 수가 서로 맞는다 (가짜 불일치가 생기지 않는다)',
    afterReconcile?.dbCount === afterReconcile?.walCount,
    `db=${afterReconcile?.dbCount} wal=${afterReconcile?.walCount} status=${afterReconcile?.status}`)

  // 뒷정리 — 실패 횟수만 지우고 **등록 행은 취소 상태 그대로 남긴다.**
  //
  // 여기서 DELETE 를 하면 안 된다. WAL 에는 이 사람의 기록이 그대로 남아 있으므로,
  // DB 행만 지우면 뒤이어 실행되는 대사가 "WAL 에만 있다"고 보고 되살려 버린다.
  // (실제로 처음엔 DELETE 를 했다가 15절의 행 수 단언이 +2 로 어긋나 발견했다 —
  //  이 기능이 막으려는 그 시나리오를 테스트가 스스로 만들어 낸 셈이다.)
  //
  // 취소된 행은 명단·통계에서 이미 제외되므로 남겨 두어도 이후 절의 계산을 흐리지 않는다.
  redisDelPattern(`imlate:cancel:fail:${TODAY}:*`)

  // ---------------------------------------------------------------- 14 (선택)
  if (RUN_DRILLS) {
    section('14. 장애 훈련 — Redis 정지 중에도 등록이 되는가 (가용성 우선 설계)')
    clearRateLimit()
    info(`Redis 컨테이너(${REDIS_CONTAINER})를 정지합니다…`)
    execFileSync('docker', ['stop', REDIS_CONTAINER], { stdio: 'ignore' })
    await sleep(2000)

    const duringOutage = await register('6', '레디스장애중', '801')
    check('Redis 가 죽어도 등록은 성공한다', duringOutage.status === 201,
      `status=${duringOutage.status} ${duringOutage.text.slice(0, 160)}`)
    check('DB 에는 정상 저장됨',
      Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE room_number='801'`)) === 1)

    const healthDuring = await fetch(`${BASE}/actuator/health/alb`).then((r) => r.json()).catch(() => null)
    check('ALB 헬스 그룹은 Redis 장애와 무관하게 UP', healthDuring?.status === 'UP', JSON.stringify(healthDuring))

    info('Redis 를 다시 시작합니다…')
    execFileSync('docker', ['start', REDIS_CONTAINER], { stdio: 'ignore' })
    for (let i = 0; i < 30; i++) {
      try {
        if (redis('ping') === 'PONG') break
      } catch { /* 기동 대기 */ }
      await sleep(1000)
    }
    await sleep(2000)
    check('Redis 복구됨', redis('ping') === 'PONG')

    // Redis 장애 중 등록분은 WAL 기록이 없다. 대사가 이를 "DB 에만 있음"으로 정확히 잡아내야 한다.
    const previewAfter = (await admin(`/admin/notifications/preview?date=${TODAY}`)).json
    const urlAfter = `${previewAfter?.smsBody ?? ''}\n${previewAfter?.emailText ?? ''}`
      .match(/https?:\/\/[^\s"'<>]+lookup\?date=[^\s"'<>]+/)
    const tokenAfter = urlAfter ? new URL(urlAfter[0]).searchParams.get('token') : token
    const afterOutage = (await req('GET', `/lookup?date=${TODAY}&token=${encodeURIComponent(tokenAfter ?? '')}`)).json

    // 대사 결과는 /lookup 응답에서 빠졌으므로 관리자 전용 엔드포인트로 확인한다.
    const recAfterOutage = await adminReconciliation(TODAY)
    check('대사가 WAL 누락을 MISMATCH 로 보고', recAfterOutage?.status === 'MISMATCH',
      JSON.stringify(recAfterOutage?.status))
    check('장애 중 등록 건이 dbOnly 목록에 정확히 나타남',
      (recAfterOutage?.dbOnly ?? []).some((s) => s.includes('레디스장애중')),
      JSON.stringify(recAfterOutage?.dbOnly))
    check('그래도 명단 자체에는 포함되어 사감에게 전달됨',
      (afterOutage?.items ?? []).some((i) => i.studentName === '레디스장애중'),
      `items=${afterOutage?.items?.length}`)
    info('※ WAL 이 없어도 DB 기준 명단은 온전하므로 교육생이 누락되지 않습니다(정상 동작).')

    // ---------------------------------------------------------------- 15
    section('15. 장애 훈련 — MySQL 정지 중 등록 의도가 WAL 에 남는가 (R7 새 쓰기 순서)')
    clearRateLimit()

    const DOWN_CLASS = '7'
    const DOWN_NAME = '디비장애중'
    const DOWN_ROOM = '901'

    // DB 가 죽으면 SELECT 도 못 하므로, 비교 기준값은 **정지 전에** 미리 읽어 둔다.
    const rowsBeforeDown = Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`))
    const statsBeforeDown = (await adminStats())?.todayRegistrations
    info(`기준값: DB ${rowsBeforeDown}건 / 등록 통계 ${statsBeforeDown}건`)

    info(`MySQL 컨테이너(${MYSQL_CONTAINER})를 정지합니다…`)
    execFileSync('docker', ['stop', MYSQL_CONTAINER], { stdio: 'ignore' })
    await sleep(2000)

    // Hikari connection-timeout 이 5초라 응답까지 몇 초 걸릴 수 있다(멈춘 것이 아니다).
    info('DB 가 죽은 상태에서 등록을 시도합니다… (응답까지 최대 5초)')
    const duringDbOutage = await register(DOWN_CLASS, DOWN_NAME, DOWN_ROOM)
    check('MySQL 이 죽으면 등록은 실패한다 (500)', duringDbOutage.status === 500,
      `status=${duringDbOutage.status} ${duringDbOutage.text.slice(0, 160)}`)
    check('사용자 응답은 기존 그대로 INTERNAL_ERROR', duringDbOutage.json?.code === 'INTERNAL_ERROR',
      duringDbOutage.text.slice(0, 160))

    // ★ 이번 변경의 핵심 증거 ★
    // WAL append 가 중복 선행 조회(DB READ)보다 앞에 있으므로, DB 가 완전히 죽어 500 이 나더라도
    // "이 사람이 등록하려 했다"는 사실은 Redis WAL 에 PENDING 으로 남는다.
    // 예전 순서(선행 조회 → WAL)에서는 여기서 아무 흔적도 남지 않아 21:50 대사로도 복구할 수 없었다.
    const walDuringDbOutage = walEntries(TODAY)
    const pendingDown = walDuringDbOutage.find(
      (e) => e.studentName === DOWN_NAME && e.roomNumber === DOWN_ROOM)
    check('DB 장애 중에도 등록 의도가 WAL 에 남는다', !!pendingDown,
      `WAL ${walDuringDbOutage.length}건 중 ${DOWN_NAME} 없음`)
    check('그 WAL 항목의 상태가 PENDING (대사 복구 대상)', pendingDown?.status === 'PENDING',
      `status=${pendingDown?.status} — FAILED 면 DB 접근 실패 경로에서 상태를 덮어쓴 것이다`)
    check('WAL 항목에 반/이름/호수가 온전히 담겨 있다',
      pendingDown?.className === DOWN_CLASS && pendingDown?.studentName === DOWN_NAME
        && pendingDown?.roomNumber === DOWN_ROOM,
      JSON.stringify(pendingDown))

    const healthDbDown = await fetch(`${BASE}/actuator/health/alb`).then((r) => r.json()).catch(() => null)
    check('ALB 헬스 그룹은 DB 장애 시 DOWN (alb 그룹에 db 포함 — 의도된 동작)',
      healthDbDown?.status === 'DOWN', JSON.stringify(healthDbDown))

    info('MySQL 을 다시 시작하고 응답할 때까지 기다립니다(최대 60초)…')
    execFileSync('docker', ['start', MYSQL_CONTAINER], { stdio: 'ignore' })
    let mysqlAlive = false
    for (let i = 0; i < 60; i++) {
      try {
        if (docker(MYSQL_CONTAINER,
          ['mysqladmin', 'ping', '-h', '127.0.0.1', `-u${DB_USER}`, `-p${DB_PASS}`]).includes('alive')) {
          mysqlAlive = true
          break
        }
      } catch { /* 기동 대기 */ }
      await sleep(1000)
    }
    check('MySQL 복구됨 (mysqladmin ping)', mysqlAlive)

    // 앱의 Hikari 풀이 죽은 커넥션을 버리고 새로 맺을 때까지 기다린다.
    let albBackUp = false
    for (let i = 0; i < 30; i++) {
      const h = await fetch(`${BASE}/actuator/health/alb`).then((r) => r.json()).catch(() => null)
      if (h?.status === 'UP') {
        albBackUp = true
        break
      }
      await sleep(1000)
    }
    check('앱의 DB 커넥션 풀도 복구됨 (/actuator/health/alb UP)', albBackUp)

    check('장애 중 등록분은 아직 DB 에 없다 (WAL 에만 존재)',
      Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE student_name='${DOWN_NAME}'`)) === 0)

    // 21:50 대사가 WAL PENDING 을 주워 DB 로 복구해야 한다.
    const dbRecovered = await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)
    check('대사 포함 발송 200', dbRecovered.status === 200,
      `status=${dbRecovered.status} ${dbRecovered.text.slice(0, 160)}`)
    check('★ DB 장애 중 등록분이 WAL → DB 로 복구됨',
      Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE student_name='${DOWN_NAME}' AND room_number='${DOWN_ROOM}'`)) === 1)
    const recoveredWalId = mysql(`SELECT wal_id FROM return_registration WHERE student_name='${DOWN_NAME}'`)
    check('복구된 행의 wal_id 가 장애 중 남긴 walId 와 동일',
      !!pendingDown && recoveredWalId === pendingDown.walId,
      `db=${recoveredWalId} wal=${pendingDown?.walId}`)
    check('DB 행 수가 1 증가',
      Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === rowsBeforeDown + 1,
      `before=${rowsBeforeDown}`)

    // 복구분이 실제로 사감 손에 들어가는지 — 명단에 들어가야 의미가 있다.
    const previewDown = (await admin(`/admin/notifications/preview?date=${TODAY}`)).json
    const urlDown = `${previewDown?.smsBody ?? ''}\n${previewDown?.emailText ?? ''}`
      .match(/https?:\/\/[^\s"'<>]+lookup\?date=[^\s"'<>]+/)
    const tokenDown = urlDown ? new URL(urlDown[0]).searchParams.get('token') : token
    const lookupDown = (await req('GET', `/lookup?date=${TODAY}&token=${encodeURIComponent(tokenDown ?? '')}`)).json
    check('복구분이 사감 명단(lookup items)에 포함됨',
      (lookupDown?.items ?? []).some((i) => i.studentName === DOWN_NAME),
      `items=${lookupDown?.items?.length}`)
    check('복구 후에는 walOnly 목록에 남지 않음',
      !((await adminReconciliation(TODAY))?.walOnly ?? []).some((s) => s.includes(DOWN_NAME)),
      'walOnly 에 남아 있으면 복구되지 않은 것')

    // PENDING 복구 = 최초 INSERT 가 실패해 통계에 잡히지 않았던 건이므로 이번에 집계되어야 한다.
    const statsAfterDown = (await adminStats())?.todayRegistrations
    check('PENDING 복구는 등록 통계에 1 증가로 반영됨', statsAfterDown === statsBeforeDown + 1,
      `before=${statsBeforeDown} after=${statsAfterDown}`)

    info('※ DB 가 완전히 죽어도 등록 의도가 WAL 에 남아 21:50 대사에서 복구됩니다.')
    info('   사용자에게는 여전히 500 이 나가므로 재시도를 안내하지만, 재시도해도 personKey 멱등이라 중복 행은 생기지 않습니다.')
  } else {
    skipped.push('14·15. 장애 훈련 — Redis / MySQL 정지 (--drills 로 실행)')
  }

  // ── 정리 ──────────────────────────────────────────────────────────────────
  if (!KEEP_DATA) {
    mysql('DELETE FROM return_registration; DELETE FROM notification_dispatch; DELETE FROM daily_stat;')
    redis('flushall')
    info('\n테스트 데이터를 정리했습니다. (--keep 으로 남길 수 있습니다)')
  }

  // ── 결과 ──────────────────────────────────────────────────────────────────
  console.log(`\n${'═'.repeat(64)}`)
  console.log(`  결과: ${pass}건 통과, ${fail}건 실패`)
  if (skipped.length) skipped.forEach((s) => console.log(`  건너뜀: ${s}`))
  if (fail) {
    console.log('\n  실패 목록:')
    failures.forEach((f) => console.log(`   - ${f}`))
  }
  console.log('═'.repeat(64))
  process.exit(fail ? 1 : 0)
}

run().catch((e) => {
  console.error('\n통합 테스트 중 예외:', e)
  process.exit(2)
})
