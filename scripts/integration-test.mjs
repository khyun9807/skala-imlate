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
const flag = (name) => argv.includes(`--${name}`)

const BASE = arg('base-url', 'http://localhost:8080').replace(/\/$/, '')
const API = `${BASE}/api/v1`
const ADMIN_KEY = arg('admin-key', 'local-dev-admin-key')
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

const redisDelPattern = (pattern) => {
  const keys = redis('--scan', '--pattern', pattern).split('\n').filter(Boolean)
  if (keys.length) redis('del', ...keys)
  return keys.length
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

/** register 버킷을 비워 rate limit 에 걸리지 않고 다음 등록을 진행한다. */
const clearRateLimit = () => redisDelPattern('imlate:rl:*')

const admin = (path, method = 'POST') => req(method, path, { headers: { 'X-Admin-Key': ADMIN_KEY } })

// ── 테스트 데이터 ────────────────────────────────────────────────────────────
const STUDENTS = [
  ['1반', '김하늘', '301'], ['1반', '박서준', '302'], ['1반', '이도윤', '303'],
  ['2반', '최지우', '401'], ['2반', '정민서', '402'], ['2반', '강하준', '403'],
  ['3반', '윤서아', '501'], ['3반', '임채원', '502'], ['3반', '한지훈', '503'],
  ['4반', '오유진', '601'], ['4반', 'Alice Kim', '602'], ['4반', '남궁민수', 'B-101'],
]

async function register(className, studentName, roomNumber, opts = {}) {
  return req('POST', '/registrations', { body: { className, studentName, roomNumber }, ...opts })
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
  info(`대상일 = ${TODAY}, 마감 = ${win.closesAt}`)

  if (!win.open) {
    console.error('\n등록 창이 닫혀 있어 이후 시험을 진행할 수 없습니다(22:00 이후).')
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
  section('3. Rate limiting (R14) — register 규칙 8회/분')
  const over = await register('9반', '과다요청', '999')
  check('한도 초과 요청 429 차단', over.status === 429, `status=${over.status}`)
  check('code = RATE_LIMITED', over.json?.code === 'RATE_LIMITED', over.text.slice(0, 100))
  check('Retry-After 헤더', !!over.headers.get('retry-after'))
  check('X-RateLimit-Limit 헤더', !!over.headers.get('x-ratelimit-limit'))
  check('X-RateLimit-Remaining 헤더', over.headers.get('x-ratelimit-remaining') !== null)
  info(`차단된 요청은 DB 에 저장되지 않아야 합니다.`)
  check('차단된 요청은 저장되지 않음',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE student_name='과다요청'`)) === 0)
  clearRateLimit()

  // ---------------------------------------------------------------- 4
  section('4. 입력 검증')
  const cases = [
    ['빈 반', { className: '', studentName: '홍길동', roomNumber: '101' }],
    ['빈 이름', { className: '1반', studentName: '   ', roomNumber: '101' }],
    ['스크립트 태그', { className: '1반', studentName: '<script>x</script>', roomNumber: '101' }],
    ['20자 초과', { className: '1반', studentName: '가'.repeat(21), roomNumber: '101' }],
    ['개행 문자', { className: '1반', studentName: '홍길동\n관리자', roomNumber: '101' }],
  ]
  for (const [label, body] of cases) {
    const res = await req('POST', '/registrations', { body })
    check(`${label} → 400 거부`, res.status === 400, `status=${res.status}`)
    clearRateLimit()
  }

  // WAL append 는 정규화·검증을 **통과한 뒤에만** 일어난다(R7 쓰기 순서 2→3단계).
  // 잘못된 입력이나 429 로 막힌 요청이 WAL 을 오염시키면 대사가 유령 인원을 복구하게 된다.
  const walAfterInvalid = Number(redis('hlen', `imlate:wal:${TODAY}`))
  check('검증 실패·429 요청은 WAL 에 남지 않음 (정상 등록 8건 그대로)',
    walAfterInvalid === 8, `hlen=${walAfterInvalid}`)

  // ---------------------------------------------------------------- 5
  section('5. 멱등성 (중복 등록 방지)')
  const dup = await register(...STUDENTS[0])
  check('중복 등록 → 200 + duplicate=true', dup.status === 200 && dup.json?.duplicate === true,
    `status=${dup.status} ${dup.text.slice(0, 120)}`)
  check('중복 등록이 행을 늘리지 않음',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 8)
  clearRateLimit()

  // 공백 정규화도 같은 사람으로 취급되는지
  const normalized = await register(' 1반 ', '김하늘', '301')
  check('앞뒤 공백은 정규화되어 같은 사람으로 인식', normalized.json?.duplicate === true,
    JSON.stringify(normalized.json))
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
  const statsBefore = (await req('GET', '/stats/summary')).json
  const victim = mysql(`SELECT CONCAT(class_name,'/',student_name,'/',room_number) FROM return_registration WHERE registration_date='${TODAY}' ORDER BY id DESC LIMIT 1`)
  mysql(`DELETE FROM return_registration WHERE registration_date='${TODAY}' ORDER BY id DESC LIMIT 1`)
  info(`DB 에서 강제 삭제: ${victim} (WAL 상태는 COMMITTED)`)
  check('삭제 직후 DB 11건',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 11)

  const recovered = await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)
  check('대사 포함 발송 200', recovered.status === 200, `status=${recovered.status} ${recovered.text.slice(0, 160)}`)
  check('WAL 에서 DB 로 자동 복구 (12건)',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`)) === 12)
  const statsAfter = (await req('GET', '/stats/summary')).json
  check('COMMITTED 복구는 등록 통계를 재집계하지 않음',
    statsAfter?.todayRegistrations === statsBefore?.todayRegistrations,
    `before=${statsBefore?.todayRegistrations} after=${statsAfter?.todayRegistrations}`)

  // ---------------------------------------------------------------- 8
  section('8. DB 쓰기 실패분(WAL PENDING) 복구 · 통계 집계됨')
  const pendingId = 'pending-wal-0000-0000-000000000001'
  const pendingEntry = JSON.stringify({
    walId: pendingId, registrationDate: TODAY, className: '5반', studentName: '유실복구',
    roomNumber: '701', registeredAt: `${TODAY}T21:00:00`, status: 'PENDING', clientIp: '127.0.0.1',
  })
  redis('hset', walKey, pendingId, pendingEntry)
  info('WAL 에만 존재하는 PENDING 항목을 주입했습니다(= 최초 DB INSERT 가 실패한 상황).')
  const beforePending = (await req('GET', '/stats/summary')).json?.todayRegistrations

  const rec2 = await admin(`/admin/notifications/dispatch?date=${TODAY}&force=true`)
  check('대사 재실행 200', rec2.status === 200)
  check('PENDING 항목이 DB 로 복구됨',
    Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE wal_id='${pendingId}'`)) === 1)
  const afterPending = (await req('GET', '/stats/summary')).json?.todayRegistrations
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
    mailText.includes('1반') && mailText.includes('김하늘') && mailText.includes('301'))
  check('이메일에 검증(대사) 결과 포함', /검증|일치|CONSISTENT|RECOVERED/.test(mailText))
  check('이메일 HTML 에 UTF-8 명시', /utf-8/i.test(mailHtml))
  check('한글이 깨지지 않음(치환문자 없음)', !/�/.test(sms + mailText + mailHtml))

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
  check('대사 상태 정상', ['CONSISTENT', 'RECOVERED'].includes(lookup.json?.verification?.status),
    JSON.stringify(lookup.json?.verification?.status))
  check('DB/WAL 카운트 일치',
    lookup.json?.verification?.dbCount === lookup.json?.verification?.walCount,
    JSON.stringify(lookup.json?.verification))
  check('반별 집계 존재', (lookup.json?.byClass?.length ?? 0) >= 4)
  check('통계 포함', typeof lookup.json?.stats?.todayRegistrations === 'number')

  check('위조 토큰 → 403', (await req('GET', `/lookup?date=${TODAY}&token=forged`)).status === 403)
  check('토큰 없음 → 400/403', [400, 403].includes((await req('GET', `/lookup?date=${TODAY}`)).status))
  check('다른 날짜 토큰 재사용 → 403',
    (await req('GET', `/lookup?date=2020-01-01&token=${encodeURIComponent(token ?? '')}`)).status === 403)

  // ---------------------------------------------------------------- 12
  section('12. 통계 (R15)')
  const stats = (await req('GET', '/stats/summary')).json
  check('오늘 등록 수 = 13', stats?.todayRegistrations === 13, JSON.stringify(stats))
  check('방문자 수 집계됨', (stats?.totalVisitors ?? 0) >= 1, String(stats?.totalVisitors))
  check('페이지뷰 집계됨', (stats?.totalPageViews ?? 0) > 10, String(stats?.totalPageViews))
  check('Redis HLL 일자별 방문자', Number(redis('pfcount', `imlate:stats:uv:${TODAY}`)) >= 1)
  check('일자별 통계는 토큰 필요',
    [400, 403].includes((await req('GET', `/stats/daily?from=${TODAY}&to=${TODAY}`)).status))
  check('토큰 있으면 일자별 통계 조회 가능',
    (await req('GET', `/stats/daily?from=${TODAY}&to=${TODAY}&token=${encodeURIComponent(token ?? '')}`)).status === 200)

  // ---------------------------------------------------------------- 13
  section('13. 관리 API 보호')
  check('키 없음 → 401', (await req('POST', `/admin/notifications/dispatch?date=${TODAY}`)).status === 401)
  check('잘못된 키 → 401',
    (await req('POST', `/admin/notifications/dispatch?date=${TODAY}`, { headers: { 'X-Admin-Key': 'wrong' } })).status === 401)
  check('이력 조회는 키가 있어야 200', (await admin(`/admin/notifications?date=${TODAY}`, 'GET')).status === 200)

  // ---------------------------------------------------------------- 14 (선택)
  if (RUN_DRILLS) {
    section('14. 장애 훈련 — Redis 정지 중에도 등록이 되는가 (가용성 우선 설계)')
    clearRateLimit()
    info(`Redis 컨테이너(${REDIS_CONTAINER})를 정지합니다…`)
    execFileSync('docker', ['stop', REDIS_CONTAINER], { stdio: 'ignore' })
    await sleep(2000)

    const duringOutage = await register('6반', '레디스장애중', '801')
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

    check('대사가 WAL 누락을 MISMATCH 로 보고', afterOutage?.verification?.status === 'MISMATCH',
      JSON.stringify(afterOutage?.verification?.status))
    check('장애 중 등록 건이 dbOnly 목록에 정확히 나타남',
      (afterOutage?.verification?.dbOnly ?? []).some((s) => s.includes('레디스장애중')),
      JSON.stringify(afterOutage?.verification?.dbOnly))
    check('그래도 명단 자체에는 포함되어 사감에게 전달됨',
      (afterOutage?.items ?? []).some((i) => i.studentName === '레디스장애중'),
      `items=${afterOutage?.items?.length}`)
    info('※ WAL 이 없어도 DB 기준 명단은 온전하므로 교육생이 누락되지 않습니다(정상 동작).')

    // ---------------------------------------------------------------- 15
    section('15. 장애 훈련 — MySQL 정지 중 등록 의도가 WAL 에 남는가 (R7 새 쓰기 순서)')
    clearRateLimit()

    const DOWN_CLASS = '7반'
    const DOWN_NAME = 'DB장애중'
    const DOWN_ROOM = '901'

    // DB 가 죽으면 SELECT 도 못 하므로, 비교 기준값은 **정지 전에** 미리 읽어 둔다.
    const rowsBeforeDown = Number(mysql(`SELECT COUNT(*) FROM return_registration WHERE registration_date='${TODAY}'`))
    const statsBeforeDown = (await req('GET', '/stats/summary')).json?.todayRegistrations
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
    // 예전 순서(선행 조회 → WAL)에서는 여기서 아무 흔적도 남지 않아 22:10 대사로도 복구할 수 없었다.
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

    // 22:10 대사가 WAL PENDING 을 주워 DB 로 복구해야 한다.
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
      !(lookupDown?.verification?.walOnly ?? []).some((s) => s.includes(DOWN_NAME)),
      JSON.stringify(lookupDown?.verification?.walOnly))

    // PENDING 복구 = 최초 INSERT 가 실패해 통계에 잡히지 않았던 건이므로 이번에 집계되어야 한다.
    const statsAfterDown = (await req('GET', '/stats/summary')).json?.todayRegistrations
    check('PENDING 복구는 등록 통계에 1 증가로 반영됨', statsAfterDown === statsBeforeDown + 1,
      `before=${statsBeforeDown} after=${statsAfterDown}`)

    info('※ DB 가 완전히 죽어도 등록 의도가 WAL 에 남아 22:10 대사에서 복구됩니다.')
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
