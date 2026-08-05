/**
 * 입력 규칙 — 반·기숙사 호수는 숫자만, 이름은 글자만.
 *
 * 숫자 칸은 **입력 순간에** 숫자가 아닌 글자를 걸러낸다(오류 문구를 보여 주기 전에 막는다).
 * 이름 칸은 걸러내지 않는다 — 한글은 ㅎ → 호 → 홍 처럼 조합 중간 상태를 거치므로
 * 입력 중에 필터를 걸면 한글 자체를 칠 수 없게 된다. 이름은 blur/제출 시점에만 검사한다.
 */

import { expect, test, type Page } from '@playwright/test'

import { installApiMocks, type ApiMock } from './helpers/mockApi'

const LABEL = { className: '반', studentName: '이름', roomNumber: '기숙사 호수', password: '취소 비밀번호' } as const

async function openRegister(page: Page): Promise<ApiMock> {
  const mock = await installApiMocks(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
  return mock
}

test.describe('반·호수는 숫자만', () => {
  test('숫자가 아닌 글자는 입력 자체가 되지 않는다', async ({ page }) => {
    await openRegister(page)

    const cls = page.getByLabel(LABEL.className)
    await cls.fill('1반')
    await expect(cls, '"1반" 에서 "반" 이 걸러져야 한다').toHaveValue('1')

    const room = page.getByLabel(LABEL.roomNumber)
    await room.fill('B-101')
    await expect(room).toHaveValue('101')
    await room.fill('302호')
    await expect(room).toHaveValue('302')
  })

  test('모바일에서 숫자 자판이 뜨도록 inputmode=numeric 이다', async ({ page }) => {
    await openRegister(page)

    await expect(page.getByLabel(LABEL.className)).toHaveAttribute('inputmode', 'numeric')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveAttribute('inputmode', 'numeric')
  })

  test('예시(placeholder)가 새 규칙과 맞는다', async ({ page }) => {
    await openRegister(page)

    // 예전 예시는 "1반" 이었다 — 그대로 두면 규칙과 정면으로 모순된다.
    await expect(page.getByLabel(LABEL.className)).toHaveAttribute('placeholder', '예: 1')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveAttribute('placeholder', '예: 302')
  })

  test('숫자만 넣으면 정상 등록된다', async ({ page }) => {
    const mock = await openRegister(page)

    await page.getByLabel(LABEL.className).fill('10')
    await page.getByLabel(LABEL.studentName).fill('홍길동')
    await page.getByLabel(LABEL.roomNumber).fill('1204')
    await page.getByLabel(LABEL.password).fill('1234')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()

    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
    expect(mock.registrationRequests[0]).toMatchObject({ className: '10', roomNumber: '1204' })
  })
})

test.describe('이름은 글자만', () => {
  test('★ 이름 칸은 입력 중에 걸러내지 않는다 (한글 조합이 깨지면 안 된다)', async ({ page }) => {
    await openRegister(page)

    const name = page.getByLabel(LABEL.studentName)
    // 조합 중간 상태(낱자)가 그대로 남아야 한다. 여기서 걸러내면 한글을 칠 수 없다.
    await name.fill('ㅎ')
    await expect(name).toHaveValue('ㅎ')
    await name.fill('호')
    await expect(name).toHaveValue('호')
    await name.fill('홍길동')
    await expect(name).toHaveValue('홍길동')
  })

  test('숫자·기호가 섞인 이름은 제출할 때 걸러진다', async ({ page }) => {
    const mock = await openRegister(page)

    await page.getByLabel(LABEL.className).fill('1')
    await page.getByLabel(LABEL.studentName).fill('홍길동2')
    await page.getByLabel(LABEL.roomNumber).fill('302')
    await page.getByLabel(LABEL.password).fill('1234')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()

    await expect(page.getByText('이름에는 한글·영문만 사용할 수 있습니다.')).toBeVisible()
    expect(mock.registrationRequests, '검증에 걸렸는데 요청이 나갔다').toHaveLength(0)
  })

  test('띄어 쓰는 영문 이름은 통과한다', async ({ page }) => {
    const mock = await openRegister(page)

    await page.getByLabel(LABEL.className).fill('4')
    await page.getByLabel(LABEL.studentName).fill('Alice Kim')
    await page.getByLabel(LABEL.roomNumber).fill('602')
    await page.getByLabel(LABEL.password).fill('1234')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()

    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
    expect(mock.registrationRequests[0]).toMatchObject({ studentName: 'Alice Kim' })
  })
})

test.describe('취소 화면도 같은 규칙', () => {
  test('취소 화면의 반·호수도 숫자만 받는다', async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/cancel')
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록 취소' })).toBeVisible()

    // 등록과 취소 규칙이 어긋나면 "등록은 됐는데 취소가 안 되는" 상태에 갇힌다.
    const cls = page.getByLabel(LABEL.className)
    await cls.fill('2반')
    await expect(cls).toHaveValue('2')

    const room = page.getByLabel(LABEL.roomNumber)
    await room.fill('410호')
    await expect(room).toHaveValue('410')
  })
})

test.describe('저장된 예전 입력값', () => {
  test('★ "1반" 이 저장돼 있어도 자동 채움이 오류를 내지 않는다', async ({ page }) => {
    await installApiMocks(page)

    // 규칙이 바뀌기 전에 저장된 형태를 그대로 심어 둔다.
    await page.addInitScript(() => {
      window.localStorage.setItem(
        'imlate.lastInput',
        JSON.stringify({ className: '1반', studentName: '홍길동', roomNumber: '302호' }),
      )
    })
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()

    // 숫자만 뽑아 채워야 한다. 그대로 채우면 사용자가 건드리지도 않은 칸에서 오류를 보게 된다.
    await expect(page.getByLabel(LABEL.className)).toHaveValue('1')
    await expect(page.getByLabel(LABEL.roomNumber)).toHaveValue('302')
    await expect(page.getByLabel(LABEL.studentName)).toHaveValue('홍길동')

    // 오류 문구가 떠 있으면 안 된다.
    await expect(page.getByText('반은 숫자만 입력해 주세요.')).toHaveCount(0)
  })
})
