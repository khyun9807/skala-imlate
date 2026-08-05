/**
 * 어제 연장 복귀 인원 안내 한 줄.
 *
 * 운영자 요구:
 * - 6개 문구 중 하나를 보여준다 (인원 수를 채워서)
 * - **들어올 때마다 랜덤으로** 하나 선택 (하루 고정이 아니다)
 * - 너무 눈에 띄지 않게, 적당한 곳에
 * - **연장 입실이 아무도 없었으면 표시하지 않는다**
 *
 * 여기에 더해, 이 문구는 어디까지나 곁들이는 정보이므로
 * **불러오지 못해도 등록을 방해해서는 안 된다**는 것까지 확인한다.
 */

import { expect, test, type Page } from '@playwright/test'

import { YESTERDAY_COUNT, YESTERDAY_TEMPLATES, installApiMocks, type MockOptions } from './helpers/mockApi'

/** `{n}` 을 실제 인원 수로 채운 완성 문구 6종 */
const EXPECTED = YESTERDAY_TEMPLATES.map((t) => t.replace('{n}', String(YESTERDAY_COUNT)))

async function open(page: Page, options: MockOptions = {}): Promise<void> {
  await installApiMocks(page, options)
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
}

/** 화면에 실제로 그려진 안내 문구를 돌려준다(없으면 null). */
async function shownMessage(page: Page): Promise<string | null> {
  const locator = page.locator('.yesterday-note')
  if ((await locator.count()) === 0) {
    return null
  }
  return (await locator.first().textContent())?.trim() ?? null
}

test.describe('어제 인원 안내', () => {
  test('6개 문구 중 하나가 인원 수와 함께 보인다', async ({ page }) => {
    await open(page)

    const message = await shownMessage(page)
    expect(message, '안내 문구가 그려지지 않았다').not.toBeNull()
    expect(EXPECTED, `예상 밖 문구: ${message}`).toContain(message)
    expect(message).toContain(`교육생 ${YESTERDAY_COUNT}분`)
  })

  test('★ 어제 아무도 없었으면 아예 표시하지 않는다', async ({ page }) => {
    await open(page, { yesterdayCount: 0 })

    // "어제 교육생 0분이 …" 는 문장으로 성립하지 않는다. 요소 자체가 없어야 한다.
    expect(await shownMessage(page)).toBeNull()
    await expect(page.getByText('교육생 0분')).toHaveCount(0)
  })

  test('인원 수를 불러오지 못해도 문구만 감추고 등록은 정상 동작한다', async ({ page }) => {
    await open(page, { yesterdayCount: null })

    expect(await shownMessage(page)).toBeNull()

    // 곁들이는 정보 하나가 실패했다고 등록이 막히면 안 된다.
    await page.getByLabel('반').fill('1')
    await page.getByLabel('이름').fill('홍길동')
    await page.getByLabel('기숙사 호수').fill('302')
    await page.getByLabel('취소 비밀번호').fill('1234')
    await page.getByRole('button', { name: /복귀 등록하기$/ }).click()
    await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
  })

  test('★ 들어올 때마다 다시 뽑는다 — 여러 번 열면 문구가 달라진다', async ({ page }) => {
    // 무작위라 "매번 다르다"고 단정할 수는 없다. 대신 충분히 여러 번 열어
    // **두 종류 이상**이 나오는지로 "하루 고정이 아님"을 확인한다.
    // 6개 중 하나를 12번 뽑아 전부 같을 확률은 (1/6)^11 ≈ 3e-9 로 사실상 0이다.
    const seen = new Set<string>()
    for (let i = 0; i < 12; i += 1) {
      await open(page)
      const message = await shownMessage(page)
      if (message) {
        seen.add(message)
      }
      if (seen.size > 1) {
        break
      }
    }
    expect(seen.size, `같은 문구만 반복됐다: ${[...seen]}`).toBeGreaterThan(1)
  })

  test('읽히는 자리에 있다 — 카운트다운 아래, 입력 폼 위', async ({ page }) => {
    await open(page)

    const note = page.locator('.yesterday-note')
    await expect(note).toBeVisible()

    const noteBox = (await note.boundingBox())!
    const timerBox = (await page.getByRole('timer').boundingBox())!
    const formBox = (await page.getByRole('heading', { name: '복귀 정보 입력' }).boundingBox())!

    // 마감 카운트다운이 이 화면의 주인공이므로 그 위로 올라가면 안 된다.
    expect(noteBox.y).toBeGreaterThan(timerBox.y)
    // 스크롤을 끝까지 내려야 보이면 "너무 눈에 안 띈다" — 입력 폼보다는 위에 있어야 한다.
    expect(noteBox.y).toBeLessThan(formBox.y)
  })

  test('제목·배지로 승격되지는 않는다 (곁들이는 문구다)', async ({ page }) => {
    await open(page)

    await expect(page.getByRole('heading', { name: /어제 교육생 .*분/ })).toHaveCount(0)
    // 화면 낭독기가 경보처럼 읽어서도 안 된다.
    await expect(page.locator('.yesterday-note[role="alert"]')).toHaveCount(0)
  })

  test('취소 화면에는 표시하지 않는다 (등록 화면에만 곁들인다)', async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/cancel')
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록 취소' })).toBeVisible()

    expect(await shownMessage(page)).toBeNull()
  })
})
