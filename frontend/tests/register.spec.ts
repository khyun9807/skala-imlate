/**
 * 등록 화면(`/`) 기능 E2E.
 *
 * 검증 항목
 * - R6 이전 입력값 기억 → 재방문 시 자동 채움
 * - 중복 등록 안내 (200 + duplicate=true)
 * - 마감(window.open=false) 시 폼 비활성 + 마감 안내
 * - 과다 요청(429) 안내
 * - 클라이언트 검증 실패 시 제출 차단
 * - 서버 마감 응답(409) 안내
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

import {
  CLOSE_TIME_LABEL,
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
} as const

/** 등록 버튼 이름 (열림 상태) */
const SUBMIT_NAME = `${RETURN_TIME_LABEL} 복귀 등록하기`

/** 목을 설치하고 등록 화면을 연다. */
async function openRegister(page: Page, options: MockOptions = {}): Promise<ApiMock> {
  const mock = await installApiMocks(page, options)
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
  return mock
}

/** 반·이름·호수를 채운다. */
async function fillForm(page: Page, className: string, studentName: string, roomNumber: string): Promise<void> {
  await page.getByLabel(LABEL.className).fill(className)
  await page.getByLabel(LABEL.studentName).fill(studentName)
  await page.getByLabel(LABEL.roomNumber).fill(roomNumber)
}

test.describe('등록 화면 기본 표시', () => {
  test('서버 시간 기준 카운트다운과 안내 문구가 보인다', async ({ page }) => {
    await openRegister(page)

    await expect(page.getByText(TEST_DATE_LABEL)).toBeVisible()
    await expect(page.getByRole('timer')).toContainText(`오늘 ${CLOSE_TIME_LABEL} 마감까지 남은 시간`)
    await expect(page.getByRole('timer')).toContainText('1시간 00분 00초')
    await expect(page.getByText(`${RETURN_TIME_LABEL} 일괄 복귀`)).toBeVisible()
    await expect(page.getByRole('button', { name: SUBMIT_NAME })).toBeEnabled()
    await expect(page.getByText('오늘 등록 인원')).toContainText('12명')
  })

  test('모든 API 요청에 X-Visitor-Id 헤더가 실린다 (SPEC §7.1)', async ({ page }) => {
    const mock = await openRegister(page)
    await expect(page.getByText('오늘 등록 인원')).toBeVisible()

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

test.describe('R6 이전 입력값 기억', () => {
  test('등록 성공 후 새로고침하면 입력칸이 자동으로 채워진다', async ({ page }) => {
    const mock = await openRegister(page)

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
    expect(mock.registrationRequests).toEqual([{ className: '1반', studentName: '홍길동', roomNumber: '302' }])

    // localStorage 에 실제로 저장되었는지 확인한다.
    const saved = await page.evaluate(() => window.localStorage.getItem('imlate.lastInput'))
    expect(saved && (JSON.parse(saved) as Record<string, string>)).toEqual({
      className: '1반',
      studentName: '홍길동',
      roomNumber: '302',
    })

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
    await expect(page.getByRole('timer')).toContainText(`오늘 등록은 마감되었습니다 (${CLOSE_TIME_LABEL})`)

    await expect(page.getByLabel(LABEL.className)).toBeDisabled()
    await expect(page.getByLabel(LABEL.studentName)).toBeDisabled()
    await expect(page.getByLabel(LABEL.roomNumber)).toBeDisabled()

    const submit = page.getByRole('button', { name: '등록 마감' })
    await expect(submit).toBeVisible()
    await expect(submit).toBeDisabled()

    await expect(page.getByText('마감 이후에는 등록할 수 없습니다.')).toBeVisible()
    expect(mock.registrationRequests).toHaveLength(0)
  })

  test('서버가 409 REGISTRATION_CLOSED 를 주면 마감 문구를 안내한다', async ({ page }) => {
    await openRegister(page, { register: 'closed' })

    await fillForm(page, '1반', '홍길동', '302')
    await page.getByRole('button', { name: SUBMIT_NAME }).click()

    await expect(page.getByRole('alert')).toContainText('등록 마감 시간(22:00)이 지났습니다.')
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
