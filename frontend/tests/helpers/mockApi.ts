/**
 * 백엔드 없이 E2E 를 돌리기 위한 `/api/v1/**` 목(mock) 계층.
 *
 * 화면이 실제로 쓰는 엔드포인트만 흉내 낸다.
 * - `GET  /registrations/window`  등록 창(열림 / 마감)
 * - `POST /registrations`         신규 / 중복 / 마감(409) / 과다요청(429) / 검증실패(400) / 서버오류(500)
 * - `GET  /lookup`                사감용 명단 12명 / 빈 명단 / 권한 없음(403)
 *
 * `/registrations/summary`(인원 수)와 `/stats/summary`(통계)는 **화면에서 호출하지 않기로 했으므로**
 * 일부러 목킹하지 않는다. 실수로 다시 호출하면 404 로 떨어지고 `handledPaths` 에 남아 테스트가 잡아낸다.
 * (`FORBIDDEN_PATHS` 참고)
 *
 * 시간에 의존하는 화면(카운트다운·날짜 문구)을 고정하기 위해 `page.clock.setFixedTime()` 도 함께 건다.
 */

import type { Page, Route } from '@playwright/test'

/** 테스트에서 사용하는 고정 시각(KST 2026-08-05 21:00) */
export const FIXED_TIME = '2026-08-05T21:00:00+09:00'

/** 테스트 대상일 (2026-08-05 는 수요일) */
export const TEST_DATE = '2026-08-05'

/** 화면에 표시되어야 하는 날짜 문구 */
export const TEST_DATE_LABEL = '2026년 8월 5일 (수)'

/** 조회 페이지 토큰 (실제 서명 검증은 백엔드 몫이므로 형태만 흉내 낸다) */
export const TEST_TOKEN = 'MTc3MDI5MzYwMA.ZTJlLXRlc3QtdG9rZW4'

/** 등록 마감 시각 라벨 (운영 요청으로 22:00 → 21:45) */
export const CLOSE_TIME_LABEL = '21:45'

/** 등록 시작 시각 라벨 (자정) */
export const OPEN_TIME_LABEL = '00:00'

/** 복귀 시각 라벨 */
export const RETURN_TIME_LABEL = '23:30'

/** 통금(문 잠김) 시각 라벨 */
export const CURFEW_TIME_LABEL = '22:30'

/**
 * 마감 후 화면이 안내해야 하는 "다음 등록 시작" 라벨.
 *
 * `opensAt` 은 오늘분(이미 지난 00:00)이므로 화면은 하루를 더해 **내일 00:00** 로 표기해야 한다.
 * 이번 요구의 핵심이라 상수로 못 박아 둔다.
 */
export const NEXT_OPEN_LABEL = `내일 ${OPEN_TIME_LABEL}`

/** 열림 시나리오에서 카운트다운에 보이는 남은 시간 (21:00 → 21:45) */
export const OPEN_COUNTDOWN_LABEL = '45분 00초'

/** 마감 임박 시나리오에서 카운트다운에 보이는 남은 시간 (21:37 → 21:45) */
export const CLOSING_SOON_COUNTDOWN_LABEL = '8분 00초'

/** 서버가 409 로 돌려주는 마감 안내 문구 */
export const REGISTRATION_CLOSED_MESSAGE = `등록 마감 시간(${CLOSE_TIME_LABEL})이 지났습니다.`

/** 토큰이 포함된 조회 페이지 경로 */
export const LOOKUP_PATH = `/lookup?date=${TEST_DATE}&token=${encodeURIComponent(TEST_TOKEN)}`

/**
 * 등록 창 시나리오.
 * - `open`        평시(남은 시간 45분)
 * - `closingSoon` 마감 10분 이내(남은 시간 8분) — 임박 강조 검증용
 * - `closed`      마감 후
 */
export type WindowScenario = 'open' | 'closingSoon' | 'closed'

/** `POST /registrations` 응답 시나리오 */
export type RegisterScenario =
  | 'created'
  | 'duplicate'
  | 'closed'
  | 'rateLimited'
  | 'validationError'
  | 'serverError'

/** `GET /lookup` 응답 시나리오 */
export type LookupScenario = 'ok' | 'empty' | 'forbidden' | 'serverError'

/**
 * 화면이 **절대 호출하면 안 되는** API 경로.
 * 검증(대사)·통계는 뒤에서 기록만 하고 사용자에게 보여 주지 않기로 했다.
 */
export const FORBIDDEN_PATHS = ['/registrations/summary', '/stats/summary'] as const

/** 목킹 옵션 */
export interface MockOptions {
  /** 등록 창 상태 (기본 open) */
  window?: WindowScenario
  /** 등록 응답 (기본 created) */
  register?: RegisterScenario
  /** 조회 응답 (기본 ok) */
  lookup?: LookupScenario
  /** 명단 데이터 종류. `long` 은 모든 값이 최대 길이(20자)인 최악 케이스 */
  roster?: 'default' | 'long'
}

/** 등록 요청 바디 */
export interface RegistrationPayload {
  className: string
  studentName: string
  roomNumber: string
}

/** 설치된 목 컨트롤러 */
export interface ApiMock {
  /** 실제로 서버까지 전달된 등록 요청 바디 목록 */
  readonly registrationRequests: RegistrationPayload[]
  /** 목이 처리한 모든 API 경로 (`/api/v1/...`) */
  readonly handledPaths: string[]
  /** 요청마다 실린 `X-Visitor-Id` 헤더 값 (SPEC §7.1). 없으면 null */
  readonly visitorIds: Array<string | null>
  /** 테스트 도중 등록 응답 시나리오를 바꾼다. */
  setRegisterScenario(scenario: RegisterScenario): void
  /** 테스트 도중 등록 창 시나리오를 바꾼다. */
  setWindowScenario(scenario: WindowScenario): void
}

/** 조회 명단 한 줄 */
export interface RosterEntry {
  no: number
  className: string
  studentName: string
  roomNumber: string
  registeredAt: string
}

/** 12명 고정 명단 (1반 5명 / 2반 4명 / 3반 3명) */
export const TEST_ROSTER: RosterEntry[] = [
  { no: 1, className: '1반', studentName: '홍길동', roomNumber: '302', registeredAt: `${TEST_DATE}T19:41:07` },
  { no: 2, className: '1반', studentName: '김철수', roomNumber: '305', registeredAt: `${TEST_DATE}T19:52:18` },
  { no: 3, className: '1반', studentName: '박민수', roomNumber: '302', registeredAt: `${TEST_DATE}T20:03:44` },
  { no: 4, className: '1반', studentName: '이영희', roomNumber: '301', registeredAt: `${TEST_DATE}T20:11:02` },
  { no: 5, className: '1반', studentName: '최지우', roomNumber: '307', registeredAt: `${TEST_DATE}T20:19:35` },
  { no: 6, className: '2반', studentName: '강현우', roomNumber: '401', registeredAt: `${TEST_DATE}T20:24:51` },
  { no: 7, className: '2반', studentName: '윤서연', roomNumber: '401', registeredAt: `${TEST_DATE}T20:33:12` },
  { no: 8, className: '2반', studentName: '임도현', roomNumber: '404', registeredAt: `${TEST_DATE}T20:41:29` },
  { no: 9, className: '2반', studentName: '정다은', roomNumber: '409', registeredAt: `${TEST_DATE}T20:47:03` },
  { no: 10, className: '3반', studentName: '오세훈', roomNumber: '502', registeredAt: `${TEST_DATE}T20:52:40` },
  { no: 11, className: '3반', studentName: '한지민', roomNumber: '506', registeredAt: `${TEST_DATE}T20:55:16` },
  { no: 12, className: '3반', studentName: '서준호', roomNumber: '503', registeredAt: `${TEST_DATE}T20:58:59` },
]

/** 서버 검증 규칙상 각 필드의 최대 길이 (SPEC §5.5) */
export const MAX_FIELD_LENGTH = 20

/**
 * 최악 케이스 명단: 반·이름·호수가 모두 정확히 20자.
 * "긴 값이 들어와도 화면을 벗어나거나 글자가 깨지지 않는다" 를 증명하기 위한 데이터다.
 */
export const LONG_ROSTER: RosterEntry[] = TEST_ROSTER.map((entry) => ({
  ...entry,
  className: padToMax(`${entry.className} 스칼라`),
  studentName: padToMax(`${entry.studentName} 가나다`),
  roomNumber: padToMax(`${entry.roomNumber}동 1204호`),
}))

/** 반별 인원 요약 (기본 명단에서 자동 계산) */
export const TEST_BY_CLASS = summarizeByClass(TEST_ROSTER)

/** 호수별 인원 요약 (기본 명단에서 자동 계산) */
export const TEST_BY_ROOM = summarizeByRoom(TEST_ROSTER)

/** 429 응답이 알려주는 재시도 대기 초 */
export const RETRY_AFTER_SECONDS = 30

/**
 * `/api/v1/**` 전체를 가로채고 브라우저 시각을 고정한다.
 * 반드시 `page.goto()` **이전에** 호출한다.
 */
export async function installApiMocks(page: Page, options: MockOptions = {}): Promise<ApiMock> {
  const registrationRequests: RegistrationPayload[] = []
  const handledPaths: string[] = []
  const visitorIds: Array<string | null> = []

  let windowScenario: WindowScenario = options.window ?? 'open'
  let registerScenario: RegisterScenario = options.register ?? 'created'
  const lookupScenario: LookupScenario = options.lookup ?? 'ok'
  const roster = options.roster === 'long' ? LONG_ROSTER : TEST_ROSTER

  // 시계 고정: 카운트다운·날짜 문구가 실행 시각과 무관하게 항상 같은 값이 되도록 한다.
  await page.clock.setFixedTime(new Date(FIXED_TIME))

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    handledPaths.push(path)
    visitorIds.push(request.headers()['x-visitor-id'] ?? null)

    // --- 등록 창 -------------------------------------------------------
    if (path.endsWith('/registrations/window')) {
      await json(route, 200, windowPayload(windowScenario))
      return
    }

    // --- 등록 ----------------------------------------------------------
    if (path.endsWith('/registrations') && request.method() === 'POST') {
      const payload = readPayload(request.postData())
      registrationRequests.push(payload)
      await respondRegister(route, registerScenario, payload, path)
      return
    }

    // --- 사감 조회 ------------------------------------------------------
    if (path.endsWith('/lookup')) {
      await respondLookup(route, lookupScenario, roster, path)
      return
    }

    // 목이 준비되지 않은 경로는 조용히 넘어가지 않고 404 로 드러낸다.
    // (`/registrations/summary`, `/stats/summary` 도 여기로 떨어진다 — 의도된 동작이다.)
    await json(route, 404, errorBody('NOT_FOUND', `목킹되지 않은 API 경로입니다: ${path}`, path))
  })

  return {
    registrationRequests,
    handledPaths,
    visitorIds,
    setRegisterScenario(scenario: RegisterScenario): void {
      registerScenario = scenario
    },
    setWindowScenario(scenario: WindowScenario): void {
      windowScenario = scenario
    },
  }
}

// ------------------------------------------------------------------ 응답 생성

/**
 * `GET /registrations/window` 응답 본문.
 *
 * 시나리오마다 `serverTime` 만 옮겨 준다. 프론트가 `serverTime` 으로 시계 오차를 보정하므로
 * 브라우저 고정 시각(21:00)과 달라도 화면은 서버 기준으로 계산된다.
 * **발송 시각 필드는 계약에 없다** — 있는 척 넣지 않는다(화면도 시각을 단정하지 않아야 한다).
 */
export function windowPayload(scenario: WindowScenario): Record<string, unknown> {
  const base = {
    date: TEST_DATE,
    opensAt: `${TEST_DATE}T${OPEN_TIME_LABEL}:00+09:00`,
    closesAt: `${TEST_DATE}T${CLOSE_TIME_LABEL}:00+09:00`,
    returnTime: RETURN_TIME_LABEL,
    curfewTime: CURFEW_TIME_LABEL,
  }
  if (scenario === 'closed') {
    // 마감(21:45)과 통금(22:30) 사이. 프론트가 클라이언트 시계와 무관하게 마감으로 판정한다.
    return { ...base, open: false, serverTime: `${TEST_DATE}T22:00:00+09:00`, secondsUntilClose: 0 }
  }
  if (scenario === 'closingSoon') {
    // 21:37 → 마감까지 8분. 화면은 10분 이하를 "마감 임박"으로 강조해야 한다.
    return { ...base, open: true, serverTime: `${TEST_DATE}T21:37:00+09:00`, secondsUntilClose: 480 }
  }
  return { ...base, open: true, serverTime: FIXED_TIME, secondsUntilClose: 2700 }
}

async function respondRegister(
  route: Route,
  scenario: RegisterScenario,
  payload: RegistrationPayload,
  path: string,
): Promise<void> {
  switch (scenario) {
    case 'created':
      await json(route, 201, {
        id: 12,
        registrationDate: TEST_DATE,
        className: payload.className,
        studentName: payload.studentName,
        roomNumber: payload.roomNumber,
        registeredAt: `${TEST_DATE}T21:00:00`,
        duplicate: false,
        returnTime: RETURN_TIME_LABEL,
      })
      return
    case 'duplicate':
      await json(route, 200, {
        id: 7,
        registrationDate: TEST_DATE,
        className: payload.className,
        studentName: payload.studentName,
        roomNumber: payload.roomNumber,
        registeredAt: `${TEST_DATE}T20:11:42`,
        duplicate: true,
        returnTime: RETURN_TIME_LABEL,
      })
      return
    case 'closed':
      await json(route, 409, errorBody('REGISTRATION_CLOSED', REGISTRATION_CLOSED_MESSAGE, path))
      return
    case 'rateLimited':
      await json(
        route,
        429,
        errorBody('RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.', path),
        {
          'Retry-After': String(RETRY_AFTER_SECONDS),
          'X-RateLimit-Limit': '8',
          'X-RateLimit-Remaining': '0',
          'X-RateLimit-Reset': '1770294000',
        },
      )
      return
    case 'validationError':
      await json(route, 400, {
        ...errorBody('VALIDATION_FAILED', '입력값을 다시 확인해 주세요.', path),
        errors: [{ field: 'studentName', message: '이름은 20자 이하로 입력해 주세요.' }],
      })
      return
    case 'serverError':
    default:
      await json(route, 500, errorBody('INTERNAL_ERROR', '서버에 일시적인 문제가 발생했습니다.', path))
  }
}

async function respondLookup(
  route: Route,
  scenario: LookupScenario,
  roster: RosterEntry[],
  path: string,
): Promise<void> {
  if (scenario === 'forbidden') {
    await json(route, 403, errorBody('FORBIDDEN', '조회 권한이 없습니다. 링크가 만료되었을 수 있습니다.', path))
    return
  }
  if (scenario === 'serverError') {
    await json(route, 500, errorBody('INTERNAL_ERROR', '서버에 일시적인 문제가 발생했습니다.', path))
    return
  }

  const items = scenario === 'empty' ? [] : roster
  // 새 계약: verification / stats 필드는 응답에 없다.
  await json(route, 200, {
    date: TEST_DATE,
    // 사감 발송 시각이 21:50 으로 앞당겨졌다. 명단 생성 시각도 이에 맞춘다.
    generatedAt: `${TEST_DATE}T21:50:00+09:00`,
    totalCount: items.length,
    returnTime: RETURN_TIME_LABEL,
    curfewTime: CURFEW_TIME_LABEL,
    items,
    byClass: summarizeByClass(items),
    byRoom: summarizeByRoom(items),
  })
}

// ------------------------------------------------------------------ 내부 유틸

function errorBody(code: string, message: string, path: string): Record<string, unknown> {
  return { code, message, path, timestamp: FIXED_TIME, errors: [] }
}

async function json(
  route: Route,
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json; charset=utf-8',
    headers,
    body: JSON.stringify(body),
  })
}

function readPayload(raw: string | null): RegistrationPayload {
  if (!raw) {
    return { className: '', studentName: '', roomNumber: '' }
  }
  try {
    const parsed = JSON.parse(raw) as Partial<RegistrationPayload>
    return {
      className: parsed.className ?? '',
      studentName: parsed.studentName ?? '',
      roomNumber: parsed.roomNumber ?? '',
    }
  } catch {
    return { className: '', studentName: '', roomNumber: '' }
  }
}

/** 반별 인원 요약 */
function summarizeByClass(items: RosterEntry[]): Array<{ className: string; count: number }> {
  return countBy(items, (entry) => entry.className).map(([className, count]) => ({ className, count }))
}

/** 호수별 인원 요약 */
function summarizeByRoom(items: RosterEntry[]): Array<{ roomNumber: string; count: number }> {
  return countBy(items, (entry) => entry.roomNumber).map(([roomNumber, count]) => ({ roomNumber, count }))
}

/** 서버 허용 문자만 사용해 정확히 20자로 맞춘다. */
function padToMax(value: string): string {
  const filler = '가나다라마바사아자차카타파하'
  let result = value
  let index = 0
  while (result.length < MAX_FIELD_LENGTH) {
    result += filler[index % filler.length]
    index += 1
  }
  return result.slice(0, MAX_FIELD_LENGTH)
}

function countBy<T>(items: T[], key: (item: T) => string): Array<[string, number]> {
  const counts = new Map<string, number>()
  for (const item of items) {
    const value = key(item)
    counts.set(value, (counts.get(value) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0], 'ko-KR'))
}
