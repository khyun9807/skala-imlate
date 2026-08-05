/**
 * 등록 취소 화면(`/cancel`) 시나리오 테스트.
 *
 * 이 화면은 **남의 등록을 지울 수 있는 유일한 경로**다. 명단에서 빠진 교육생은
 * 22:30 에 문이 잠기면 밖에서 밤을 새게 되므로, "잘못 취소되는 것"이 "취소가 안 되는 것"보다
 * 훨씬 나쁜 실패다. 그래서 다음을 못 박는다.
 *
 * - 네 칸(반·이름·호수·비밀번호)이 모두 차야 요청이 나간다.
 * - 실제 취소 전에 확인 단계를 한 번 거친다.
 * - 실패 사유를 "등록 없음 / 비밀번호 틀림"으로 나눠 보여 주지 않는다.
 * - 비밀번호는 화면에서 가려지고, 어디에도 저장되지 않는다.
 */

import { expect, test, type Page } from '@playwright/test'

import {
  CANCEL_LOCKED_MESSAGE,
  CANCEL_REJECTED_MESSAGE,
  CLOSE_TIME_LABEL,
  installApiMocks,
  type ApiMock,
  type MockOptions,
} from './helpers/mockApi'

const LABEL = {
  className: '반',
  studentName: '이름',
  roomNumber: '기숙사 호수',
  password: '취소 비밀번호',
} as const

const CONFIRM_BUTTON = '정말 취소합니다'
const SUBMIT_BUTTON = '등록 취소하기'

async function openCancel(page: Page, options: MockOptions = {}): Promise<ApiMock> {
  const mock = await installApiMocks(page, options)
  await page.goto('/cancel')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록 취소' })).toBeVisible()
  return mock
}

async function fillCancelForm(page: Page, password = '1234'): Promise<void> {
  await page.getByLabel(LABEL.className).fill('1')
  await page.getByLabel(LABEL.studentName).fill('홍길동')
  await page.getByLabel(LABEL.roomNumber).fill('302')
  await page.getByLabel(LABEL.password).fill(password)
}

test.describe('등록 취소 기본 흐름', () => {
  test('네 칸을 채우고 두 번 눌러야 실제로 취소된다', async ({ page }) => {
    const mock = await openCancel(page)
    await fillCancelForm(page)

    // 첫 번째 누름 — 확인 단계로만 넘어가고 아직 요청은 나가지 않는다.
    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await expect(page.getByText('아래 등록을 취소합니다')).toBeVisible()
    await expect(page.getByText('1반 · 홍길동 · 302호')).toBeVisible()
    expect(mock.cancelRequests).toHaveLength(0)

    // 두 번째 누름에서 비로소 취소된다.
    await page.getByRole('button', { name: CONFIRM_BUTTON }).click()
    await expect(page.getByText('취소되었습니다', { exact: false }).first()).toBeVisible()
    expect(mock.cancelRequests).toHaveLength(1)
    expect(mock.cancelRequests[0]).toMatchObject({
      className: '1',
      studentName: '홍길동',
      roomNumber: '302',
      password: '1234',
    })
  })

  test('확인 단계에서 입력을 고치면 확인이 처음으로 되돌아간다', async ({ page }) => {
    const mock = await openCancel(page)
    await fillCancelForm(page)
    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await expect(page.getByText('아래 등록을 취소합니다')).toBeVisible()

    // 확인 문구가 떠 있는 채로 값만 바뀌면 "확인한 내용"과 "보내지는 내용"이 달라진다.
    await page.getByLabel(LABEL.studentName).fill('김철수')
    await expect(page.getByText('아래 등록을 취소합니다')).toBeHidden()
    await expect(page.getByRole('button', { name: SUBMIT_BUTTON })).toBeVisible()
    expect(mock.cancelRequests).toHaveLength(0)
  })

  test('빈 칸이 있으면 요청을 보내지 않고 오류를 표시한다', async ({ page }) => {
    const mock = await openCancel(page)
    await page.getByLabel(LABEL.className).fill('1')
    await page.getByLabel(LABEL.studentName).fill('홍길동')
    await page.getByLabel(LABEL.roomNumber).fill('302')
    // 비밀번호를 비워 둔다.

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()

    await expect(page.getByText('등록할 때 정한 비밀번호 숫자 4자리를 입력해 주세요.')).toBeVisible()
    expect(mock.cancelRequests).toHaveLength(0)
  })

  test('비밀번호 칸은 가려지고 숫자만, 4자리까지만 받는다', async ({ page }) => {
    await openCancel(page)
    const password = page.getByLabel(LABEL.password)

    // 어깨너머로 보이면 남이 그 사람의 등록을 취소할 수 있다.
    await expect(password).toHaveAttribute('type', 'password')
    // 모바일에서 숫자 자판이 바로 뜨도록.
    await expect(password).toHaveAttribute('inputmode', 'numeric')
    await expect(password).toHaveAttribute('maxlength', '4')

    // 4자리를 넘겨 입력해도 앞 4자리만 남는다.
    await password.fill('123456')
    await expect(password).toHaveValue('1234')

    // 숫자가 아닌 글자는 아예 들어가지 않는다.
    // (모바일 자판 오타를 등록 시점에 걸러야 한다 — 취소하려 할 때 알게 되면 되돌릴 방법이 없다)
    await password.fill('12ab')
    await expect(password).toHaveValue('12')
  })
})

test.describe('취소 실패 처리', () => {
  test('등록이 없거나 비밀번호가 틀리면 사유를 구분하지 않고 같은 문구를 보여 준다', async ({ page }) => {
    await openCancel(page, { cancel: 'rejected' })
    await fillCancelForm(page, '9999')

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await page.getByRole('button', { name: CONFIRM_BUTTON }).click()

    await expect(page.getByRole('alert').filter({ hasText: CANCEL_REJECTED_MESSAGE })).toBeVisible()
    // "그런 등록이 없습니다" 같은 존재 여부를 흘리는 문구가 있으면 안 된다.
    await expect(page.getByText('등록을 찾을 수 없습니다')).toBeHidden()
  })

  test('실패하면 비밀번호만 비우고 반·이름·호수는 남긴다', async ({ page }) => {
    await openCancel(page, { cancel: 'rejected' })
    await fillCancelForm(page, '9999')

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await page.getByRole('button', { name: CONFIRM_BUTTON }).click()
    await expect(page.getByRole('alert').filter({ hasText: CANCEL_REJECTED_MESSAGE })).toBeVisible()

    await expect(page.getByLabel(LABEL.className)).toHaveValue('1')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('홍길동')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('302')
    await expect(page.getByLabel(LABEL.password)).toHaveValue('')
  })

  test('시도 횟수를 초과하면 버튼이 잠기고 더 시도할 수 없다', async ({ page }) => {
    const mock = await openCancel(page, { cancel: 'locked' })
    await fillCancelForm(page, '9999')

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await page.getByRole('button', { name: CONFIRM_BUTTON }).click()

    await expect(page.getByRole('alert').filter({ hasText: CANCEL_LOCKED_MESSAGE })).toBeVisible()
    const lockedButton = page.getByRole('button', { name: '오늘은 더 시도할 수 없습니다' })
    await expect(lockedButton).toBeDisabled()

    // 잠긴 뒤에는 추가 요청이 나가지 않는다.
    expect(mock.cancelRequests).toHaveLength(1)
  })

  test('이미 취소된 등록이면 그 사실을 알려 준다', async ({ page }) => {
    await openCancel(page, { cancel: 'alreadyCancelled' })
    await fillCancelForm(page)

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()
    await page.getByRole('button', { name: CONFIRM_BUTTON }).click()

    await expect(page.getByText('이미 취소되어 있었습니다')).toBeVisible()
  })
})

test.describe('마감 이후', () => {
  test('마감되면 취소할 수 없고 그 이유를 안내한다', async ({ page }) => {
    const mock = await openCancel(page, { window: 'closed' })

    await expect(page.getByText('취소 마감 이후 안내')).toBeVisible()
    await expect(page.getByText(`${CLOSE_TIME_LABEL} 이후에는 취소할 수 없습니다.`)).toBeVisible()
    // 왜 안 되는지가 화면에 있어야 사감에게 전화가 가지 않는다.
    await expect(page.getByText('명단은 이미 사감 선생님께 전달되어 시스템에서 되돌릴 수 없습니다.')).toBeVisible()

    await expect(page.getByRole('button', { name: '취소 마감' })).toBeDisabled()
    expect(mock.cancelRequests).toHaveLength(0)
  })
})

test.describe('등록 화면과의 연결', () => {
  test('등록 화면에서 취소 화면으로 갈 수 있다', async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/')

    await page.getByRole('link', { name: '등록 취소하기' }).click()

    await expect(page).toHaveURL(/\/cancel$/)
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록 취소' })).toBeVisible()
  })

  test('등록할 때 저장된 반·이름·호수는 자동으로 채워지고 비밀번호는 비어 있다', async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/')

    // 먼저 등록해서 반·이름·호수를 저장시킨다.
    await page.getByLabel(LABEL.className).fill('2')
    await page.getByLabel(LABEL.studentName).fill('김철수')
    await page.getByLabel(LABEL.roomNumber).fill('305')
    await page.getByLabel(LABEL.password).fill('4321')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    await page.goto('/cancel')

    await expect(page.getByLabel(LABEL.className)).toHaveValue('2')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('김철수')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('305')
    // 비밀번호는 저장하지 않는다 — 공용 기기에 남으면 다음 사람이 남의 등록을 취소할 수 있다.
    await expect(page.getByLabel(LABEL.password)).toHaveValue('')
  })

  test('비밀번호는 브라우저 저장소 어디에도 남지 않는다', async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/')

    await page.getByLabel(LABEL.className).fill('2')
    await page.getByLabel(LABEL.studentName).fill('김철수')
    await page.getByLabel(LABEL.roomNumber).fill('305')
    await page.getByLabel(LABEL.password).fill('4321')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()

    const stored = await page.evaluate(() => {
      const dump: string[] = []
      for (let i = 0; i < localStorage.length; i += 1) {
        const key = localStorage.key(i)
        if (key) {
          dump.push(`${key}=${localStorage.getItem(key) ?? ''}`)
        }
      }
      for (let i = 0; i < sessionStorage.length; i += 1) {
        const key = sessionStorage.key(i)
        if (key) {
          dump.push(`${key}=${sessionStorage.getItem(key) ?? ''}`)
        }
      }
      return dump.join('\n')
    })

    expect(stored).not.toContain('4321')
    expect(stored).not.toContain('cancelPassword')
  })
})
