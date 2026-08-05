/**
 * 등록 화면(`/`) 기능 E2E.
 *
 * 검증 항목
 * - 시간 안내(요구사항 3): 열림 / 마감 임박 / 마감 세 상태에서 각각 무엇을 안내하는가
 *   · 열림   — 오늘 몇 시까지 등록 가능한지 + 남은 시간 + 명단이 언제 전달되는지
 *   · 임박   — 10분 이하일 때 시각적 강조
 *   · 마감   — **내일 00:00 부터 다음 날 밤 복귀 등록이 열린다** (이번 요구의 핵심)
 * - R6 이전 입력값 기억 → 재방문 시 자동 채움
 * - 중복 등록 안내 (200 + duplicate=true)
 * - 마감(window.open=false) 시 폼 비활성 + 마감 안내
 * - 과다 요청(429) 안내
 * - 클라이언트 검증 실패 시 제출 차단
 * - 서버 마감 응답(409) 안내
 * - 오늘 등록 인원(카운트)·통계는 **화면에 보이지 않는다** (사용자 피드백 1·2번, 회귀 방지)
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

import {
  CLOSE_TIME_LABEL,
  CLOSING_SOON_COUNTDOWN_LABEL,
  CURFEW_TIME_LABEL,
  FORBIDDEN_PATHS,
  NEXT_OPEN_LABEL,
  OPEN_COUNTDOWN_LABEL,
  REGISTRATION_CLOSED_MESSAGE,
  RETRY_AFTER_SECONDS,
  RETURN_TIME_LABEL,
  TEST_DATE_LABEL,
  installApiMocks,
} from './helpers/mockApi'
import type { ApiMock, MockOptions } from './helpers/mockApi'

/** 등록 폼 라벨 */
const LABEL = {
  className: '반',
  studentName: '이름',
  roomNumber: '기숙사 호수',
  cancelPassword: '취소 비밀번호',
} as const

/** 테스트에서 쓰는 취소 비밀번호(숫자 4자리) */
const CANCEL_PASSWORD = '1234'

/** 등록 버튼 이름 (열림 상태) */
const SUBMIT_NAME = `${RETURN_TIME_LABEL} 복귀 등록하기`

/** 목을 설치하고 등록 화면을 연다. */
async function openRegister(page: Page, options: MockOptions = {}): Promise<ApiMock> {
  const mock = await installApiMocks(page, options)
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
  return mock
}

/** 반·이름·호수와 취소 비밀번호를 채운다. 네 칸이 모두 차야 제출된다. */
async function fillForm(
  page: Page,
  className: string,
  studentName: string,
  roomNumber: string,
  cancelPassword: string = CANCEL_PASSWORD,
): Promise<void> {
  await page.getByLabel(LABEL.className).fill(className)
  await page.getByLabel(LABEL.studentName).fill(studentName)
  await page.getByLabel(LABEL.roomNumber).fill(roomNumber)
  await page.getByLabel(LABEL.cancelPassword).fill(cancelPassword)
}

test.describe('등록 화면 기본 표시', () => {
  test('서버 시간 기준 카운트다운과 안내 문구가 보인다', async ({ page }) => {
    await openRegister(page)

    await expect(page.getByText(TEST_DATE_LABEL)).toBeVisible()
    await expect(page.getByRole('timer')).toContainText(OPEN_COUNTDOWN_LABEL)
    await expect(page.getByText(`${RETURN_TIME_LABEL} 일괄 복귀`)).toBeVisible()
    await expect(page.getByRole('button', { name: SUBMIT_NAME })).toBeEnabled()
  })

  test('모든 API 요청에 X-Visitor-Id 헤더가 실린다 (SPEC §7.1)', async ({ page }) => {
    const mock = await openRegister(page)
    await expect(page.getByRole('timer')).toContainText(OPEN_COUNTDOWN_LABEL)

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    expect(mock.handledPaths.length).toBeGreaterThan(0)
    expect(mock.visitorIds).toHaveLength(mock.handledPaths.length)
    for (const visitorId of mock.visitorIds) {
      expect(visitorId, `X-Visitor-Id 누락: ${mock.handledPaths.join(', ')}`).toBeTruthy()
      expect(visitorId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
    }
    // 방문자 식별자는 세션 내내 동일해야 통계(UV)가 정확하다.
    expect(new Set(mock.visitorIds).size).toBe(1)
  })
})

test.describe('시간 안내 (요구사항 3)', () => {
  /** 마감 임박 안내 문구 */
  const CLOSING_SOON_FLAG = '마감 임박'
  /** 명단 전달 시점 안내. window 응답에 발송 시각이 없으므로 시각을 단정하지 않는다. */
  const DISPATCH_NOTICE = '지금 등록하면 마감 직후 사감 선생님께 명단이 전달됩니다.'

  test('열림 — 몇 시까지 등록 가능한지·남은 시간·명단 전달 시점이 한눈에 보인다', async ({ page }) => {
    await openRegister(page)

    const timer = page.getByRole('timer')
    // 1) 오늘 몇 시까지 등록할 수 있는지
    await expect(timer).toContainText(`오늘 ${CLOSE_TIME_LABEL}까지 등록할 수 있어요`)
    // 2) 마감까지 남은 시간 (기존 카운트다운 유지)
    await expect(timer).toContainText(OPEN_COUNTDOWN_LABEL)
    await expect(timer).toContainText('마감까지 남은 시간')
    // 3) 등록하면 명단이 언제 전달되는지
    await expect(page.getByText(DISPATCH_NOTICE)).toBeVisible()
    // 4) 마감 후 언제 다시 열리는지 (마감 전에도 미리 알려 준다)
    await expect(
      page.getByText(
        `${CLOSE_TIME_LABEL} 이후에는 오늘 등록이 닫히고, ${NEXT_OPEN_LABEL}에 다음 날 밤 복귀 등록이 열립니다.`,
      ),
    ).toBeVisible()

    // 평시에는 임박 강조가 붙지 않는다.
    await expect(timer).toHaveClass(/countdown--open/)
    await expect(page.getByText(CLOSING_SOON_FLAG)).toHaveCount(0)
  })

  test('마감 임박 — 10분 이하로 남으면 시각적으로 강조한다', async ({ page }) => {
    await openRegister(page, { window: 'closingSoon' })

    const timer = page.getByRole('timer')
    await expect(timer).toContainText(CLOSING_SOON_COUNTDOWN_LABEL)
    await expect(timer).toContainText(CLOSING_SOON_FLAG)
    await expect(timer).toHaveClass(/countdown--warn/)

    // 임박이지 마감이 아니다 — 아직 등록할 수 있어야 한다.
    await expect(timer).toContainText(`오늘 ${CLOSE_TIME_LABEL}까지 등록할 수 있어요`)
    await expect(page.getByRole('button', { name: SUBMIT_NAME })).toBeEnabled()
    await expect(page.getByLabel(LABEL.studentName)).toBeEnabled()
  })

  test('마감 — 내일 00:00 부터 다시 열린다는 안내가 보인다', async ({ page }) => {
    await openRegister(page, { window: 'closed' })

    const timer = page.getByRole('timer')
    await expect(timer).toContainText('등록 마감')
    await expect(timer).toContainText(`오늘 밤 복귀 등록은 마감되었습니다 (${CLOSE_TIME_LABEL})`)
    // 이번 요구의 핵심: 마감 화면이 "언제 다시 되는지"를 알려 준다.
    await expect(timer).toContainText(`다음 등록 시작: ${NEXT_OPEN_LABEL}`)
    await expect(page.getByText(`${NEXT_OPEN_LABEL}부터 다음 날 밤 복귀 등록을 받습니다.`)).toBeVisible()

    // 통금 전에 복귀해야 한다는 안내
    await expect(
      page.getByText(`${CURFEW_TIME_LABEL} 이후에는 기숙사 문이 잠기니 그 전에 복귀해 주세요.`),
    ).toBeVisible()

    // 마감 후에는 "지금 등록하면…" 안내가 남아 있으면 안 된다.
    await expect(page.getByText(DISPATCH_NOTICE)).toHaveCount(0)
  })

  test('마감 안내는 오늘 날짜의 opensAt 을 "오늘"로 잘못 표기하지 않는다', async ({ page }) => {
    await openRegister(page, { window: 'closed' })

    await expect(page.getByRole('timer')).toContainText(`다음 등록 시작: ${NEXT_OPEN_LABEL}`)
    // opensAt(오늘 00:00)은 이미 지난 시각이다. "오늘 00:00 부터"로 새면 안내가 정반대가 된다.
    await expect(page.getByText('오늘 00:00')).toHaveCount(0)
  })

  test('화면에 마감 시각이 하드코딩되어 있지 않다 (서버 값 22:00 이면 22:00 으로 표시)', async ({ page }) => {
    // 서버가 다른 마감 시각을 주면 화면도 따라가야 한다.
    await installApiMocks(page)
    await page.route('**/api/v1/registrations/window', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({
          date: '2026-08-05',
          open: true,
          serverTime: '2026-08-05T21:00:00+09:00',
          opensAt: '2026-08-05T00:30:00+09:00',
          closesAt: '2026-08-05T22:00:00+09:00',
          returnTime: '23:30',
          curfewTime: '22:30',
          secondsUntilClose: 3600,
        }),
      })
    })
    await page.goto('/')

    const timer = page.getByRole('timer')
    await expect(timer).toContainText('오늘 22:00까지 등록할 수 있어요')
    await expect(timer).toContainText('1시간 00분 00초')
    await expect(page.getByText('22:00 이후에는 오늘 등록이 닫히고, 내일 00:30에 다음 날 밤 복귀 등록이 열립니다.')).toBeVisible()
    await expect(page.getByText(`오늘 ${CLOSE_TIME_LABEL}까지 등록할 수 있어요`)).toHaveCount(0)
  })
})

test.describe('인원 수·통계 비노출 (회귀 방지)', () => {
  /**
   * 사용자 피드백 1·2번: 등록 화면에는 "필요한 정보만" 보이고, 집계·통계는 보이지 않는다.
   * 백엔드도 `/registrations/summary` 에서 count 를 빼고 `/stats/summary` 를 관리자 전용으로 바꿨다.
   */
  const FORBIDDEN_TEXTS = ['오늘 등록 인원', '등록 인원', '방문자', '통계', '검증 결과', 'WAL', '복구 처리']

  test('등록 화면에는 인원 수·통계 문구가 없다', async ({ page }) => {
    await openRegister(page)
    await expect(page.getByRole('timer')).toContainText(OPEN_COUNTDOWN_LABEL)

    for (const text of FORBIDDEN_TEXTS) {
      await expect(page.getByText(text), `화면에 남아 있으면 안 되는 문구: ${text}`).toHaveCount(0)
    }
  })

  test('등록 전후 어느 시점에도 요약·통계 API 를 호출하지 않는다', async ({ page }) => {
    const mock = await openRegister(page)

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    for (const forbidden of FORBIDDEN_PATHS) {
      expect(
        mock.handledPaths.some((path) => path.endsWith(forbidden)),
        `호출되면 안 되는 API: ${forbidden} (실제 호출: ${mock.handledPaths.join(', ')})`,
      ).toBe(false)
    }
    // 화면이 실제로 쓰는 엔드포인트는 등록 창과 등록뿐이다.
    expect(new Set(mock.handledPaths.map((path) => path.replace('/api/v1', '')))).toEqual(
      new Set(['/registrations/window', '/registrations']),
    )
  })
})

test.describe('R6 이전 입력값 기억', () => {
  test('등록 성공 후 새로고침하면 입력칸이 자동으로 채워진다', async ({ page }) => {
    const mock = await openRegister(page)

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
    expect(mock.registrationRequests).toHaveLength(1)
    expect(mock.registrationRequests[0]).toMatchObject({
      className: '1반',
      studentName: '홍길동',
      roomNumber: '302',
      // 취소 비밀번호는 서버로 <b>보내지되</b>, 아래에서 보듯 저장되지는 않는다.
      cancelPassword: CANCEL_PASSWORD,
    })

    // localStorage 에는 반·이름·호수만 저장한다.
    // 비밀번호가 여기 섞이면 공용 기기에서 다음 사람이 남의 등록을 취소할 수 있다.
    const saved = await page.evaluate(() => window.localStorage.getItem('imlate.lastInput'))
    expect(saved && (JSON.parse(saved) as Record<string, string>)).toEqual({
      className: '1반',
      studentName: '홍길동',
      roomNumber: '302',
    })
    expect(saved ?? '').not.toContain(CANCEL_PASSWORD)

    // 재방문(새로고침) — 자동 채움
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
    await expect(page.getByLabel(LABEL.className)).toHaveValue('1반')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('홍길동')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('302')
    await expect(page.getByRole('button', { name: '저장된 정보 지우기' })).toBeVisible()

    // 새로고침만으로 다시 등록되지는 않는다.
    expect(mock.registrationRequests).toHaveLength(1)
  })

  test('저장된 정보 지우기를 누르면 입력칸이 비워진다', async ({ page }) => {
    await openRegister(page)

    await fillForm(page, '2반', '김철수', '305')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    await page.reload()
    await expect(page.getByLabel(LABEL.className)).toHaveValue('2반')

    await page.getByRole('button', { name: '저장된 정보 지우기' }).click()
    await expect(page.getByLabel(LABEL.className)).toHaveValue('')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('')

    const saved = await page.evaluate(() => window.localStorage.getItem('imlate.lastInput'))
    expect(saved).toBeNull()
  })

  test('다른 인원 이어서 등록하기는 반만 남기고 이름·호수를 비운다', async ({ page }) => {
    await openRegister(page)

    await fillForm(page, '3반', '이영희', '301')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    await page.getByRole('button', { name: '다른 인원 이어서 등록하기' }).click()

    await expect(page.getByLabel(LABEL.className)).toHaveValue('3반')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('')
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toHaveCount(0)
  })
})

test.describe('중복 등록', () => {
  test('이미 등록된 사람이면 "이미 등록" 안내를 보여 준다', async ({ page }) => {
    await openRegister(page, { register: 'duplicate' })

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByRole('heading', { name: '이미 등록되어 있습니다' })).toBeVisible()
    await expect(page.getByText('중복 등록')).toBeVisible()
    await expect(page.getByText('아래 내용으로 이미 접수되어 있어 새로 등록하지 않았습니다.')).toBeVisible()

    // 결과 카드에 반·이름·호수·복귀시각이 모두 보인다.
    const result = page.getByRole('region', { name: '이미 등록되어 있습니다' })
    await expect(result).toContainText('1반')
    await expect(result).toContainText('홍길동')
    await expect(result).toContainText('302')
    await expect(result).toContainText(RETURN_TIME_LABEL)
  })
})

test.describe('등록 마감', () => {
  test('마감 상태에서는 폼이 잠기고 마감 안내가 보인다', async ({ page }) => {
    const mock = await openRegister(page, { window: 'closed' })

    await expect(page.getByRole('timer')).toContainText('등록 마감')
    await expect(page.getByRole('timer')).toContainText(`오늘 밤 복귀 등록은 마감되었습니다 (${CLOSE_TIME_LABEL})`)

    await expect(page.getByLabel(LABEL.className)).toBeDisabled()
    await expect(page.getByLabel(LABEL.studentName)).toBeDisabled()
    await expect(page.getByLabel(LABEL.roomNumber)).toBeDisabled()

    const submit = page.getByRole('button', { name: '등록 마감' })
    await expect(submit).toBeVisible()
    await expect(submit).toBeDisabled()

    await expect(page.getByText('오늘 등록하지 못했다면 사감 선생님께 직접 문의해 주세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '등록 시간 다시 확인' })).toBeVisible()
    expect(mock.registrationRequests).toHaveLength(0)
  })

  test('서버가 409 REGISTRATION_CLOSED 를 주면 마감 문구를 안내한다', async ({ page }) => {
    await openRegister(page, { register: 'closed' })

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByRole('alert')).toContainText(REGISTRATION_CLOSED_MESSAGE)
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toHaveCount(0)
  })
})

test.describe('과다 요청(429)', () => {
  test('429 응답이면 재시도 안내 문구를 보여 준다', async ({ page }) => {
    await openRegister(page, { register: 'rateLimited' })

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    const alert = page.getByRole('alert')
    await expect(alert).toContainText('요청이 너무 많습니다.')
    await expect(alert).toContainText(`약 ${RETRY_AFTER_SECONDS}초 후 다시 시도해 주세요.`)
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toHaveCount(0)
  })
})

test.describe('입력 검증', () => {
  test('빈 값으로 제출하면 요청을 보내지 않고 오류를 표시한다', async ({ page }) => {
    const mock = await openRegister(page)

    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByText('반을 입력해 주세요.')).toBeVisible()
    await expect(page.getByText('이름을 입력해 주세요.')).toBeVisible()
    await expect(page.getByText('기숙사 호수를 입력해 주세요.')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('입력한 내용을 다시 확인해 주세요.')
    await expect(page.getByLabel(LABEL.className)).toBeFocused()

    expect(mock.registrationRequests).toHaveLength(0)
  })

  test('허용되지 않는 문자가 있으면 제출을 막는다', async ({ page }) => {
    const mock = await openRegister(page)

    await fillForm(page, '1반!!', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByText('한글·영문·숫자와 공백, 괄호( ), 하이픈(-)만 사용할 수 있습니다.')).toBeVisible()
    expect(mock.registrationRequests).toHaveLength(0)
  })

  test('서버가 알려준 필드 오류를 해당 입력칸에 표시한다', async ({ page }) => {
    await openRegister(page, { register: 'validationError' })

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByText('이름은 20자 이하로 입력해 주세요.')).toBeVisible()
    await expect(page.getByLabel(LABEL.studentName)).toHaveAttribute('aria-invalid', 'true')
  })
})
