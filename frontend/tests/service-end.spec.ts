/**
 * 서비스 종료 안내 (`ServiceEndNotice`).
 *
 * 종료 공지는 **세 화면이 같은 날짜와 같은 이유를 말해야** 한다. 어디서는 13일,
 * 어디서는 14일이라고 하면 그 순간 안내 전체를 믿을 수 없게 된다.
 * 그래서 날짜가 한 곳(config/serviceEnd.ts)에서만 오는지를 화면으로 확인한다.
 *
 * 남은 일수는 브라우저 시계에 의존하므로 `page.clock` 으로 고정해서 본다.
 */

import { expect, test, type Page } from '@playwright/test'

import { installApiMocks, LOOKUP_PATH } from './helpers/mockApi'

/** config/serviceEnd.ts 와 같은 값이어야 한다. */
const END_DATE_LABEL = '2026년 9월 7일'
const STOP_DATE_LABEL = '2026년 9월 8일'

const NOTICE_TITLE = '서비스 종료 안내'

/**
 * 브라우저 시계를 특정 시각으로 고정한다.
 *
 * **반드시 `installApiMocks` 뒤에 불러야 한다.** 그쪽도 내부에서 시계를 고정하므로
 * 먼저 부르면 조용히 덮어써진다(남은 시간이 엉뚱하게 나온다).
 */
async function fixTime(page: Page, iso: string): Promise<void> {
  await page.clock.setFixedTime(new Date(iso))
}

test.describe('서비스 종료 안내', () => {
  test('등록 화면 맨 위에 종료일과 중지일이 함께 보인다', async ({ page }) => {
    await installApiMocks(page)
    await fixTime(page, '2026-09-05T12:00:00+09:00')
    await page.goto('/')

    const notice = page.getByRole('region', { name: NOTICE_TITLE })
    await expect(notice).toBeVisible()
    await expect(notice).toContainText(END_DATE_LABEL)
    await expect(notice).toContainText(STOP_DATE_LABEL)
  })

  test('이유를 밝힌다 — 과정을 떠나는 것과 비용 둘 다', async ({ page }) => {
    await installApiMocks(page)
    await fixTime(page, '2026-09-05T12:00:00+09:00')
    await page.goto('/')

    const notice = page.getByRole('region', { name: NOTICE_TITLE })
    await expect(notice).toContainText('과정을 떠나게 되어')
    await expect(notice).toContainText('비용')
    // 개인정보 처리는 반드시 밝힌다(밝힌 대로 실제로 파기해야 한다).
    await expect(notice).toContainText('모두 지워집니다')
  })

  test('취소 화면에도 같은 날짜로 붙는다', async ({ page }) => {
    await installApiMocks(page)
    await fixTime(page, '2026-09-05T12:00:00+09:00')
    await page.goto('/cancel')

    const notice = page.getByRole('region', { name: NOTICE_TITLE })
    await expect(notice).toBeVisible()
    await expect(notice).toContainText(END_DATE_LABEL)
  })

  test('★ 사감 조회 화면에는 "사감님께 말씀하세요"가 뜨지 않는다', async ({ page }) => {
    await installApiMocks(page)
    await fixTime(page, '2026-09-05T12:00:00+09:00')
    await page.goto(LOOKUP_PATH)

    const notice = page.getByRole('region', { name: NOTICE_TITLE })
    await expect(notice).toBeVisible()
    await expect(notice).toContainText(END_DATE_LABEL)
    // 읽는 사람이 사감 본인이다. 본인에게 본인을 찾아가라고 하면 안 된다.
    await expect(notice).not.toContainText('사감 선생님께 직접 말씀해 주세요')
    await expect(notice).toContainText('교육생에게 직접 확인해')
  })

  test('남은 시간이 시간 단위로 보인다', async ({ page }) => {
    await installApiMocks(page)

    // 마지막 등록 마감은 2026-09-07 22:15 KST. 그보다 5시간 전.
    await fixTime(page, '2026-09-07T17:15:00+09:00')
    await page.goto('/')
    await expect(page.getByRole('region', { name: NOTICE_TITLE })).toContainText('종료까지 5시간')

    // 이틀 전이면 남은 시간이 하루를 넘어도 그대로 시간으로 센다(요구사항).
    await fixTime(page, '2026-09-05T22:15:00+09:00')
    await page.goto('/')
    await expect(page.getByRole('region', { name: NOTICE_TITLE })).toContainText('종료까지 48시간')
  })

  test('한 시간이 안 남으면 분으로 내려간다 — "0시간"은 아무것도 알려주지 않는다', async ({ page }) => {
    await installApiMocks(page)

    await fixTime(page, '2026-09-07T21:45:00+09:00')
    await page.goto('/')
    await expect(page.getByRole('region', { name: NOTICE_TITLE })).toContainText('종료까지 30분')
  })

  test('종료일이 지나면 과거형으로 말하고 남은 일수 배지는 사라진다', async ({ page }) => {
    await installApiMocks(page)
    await fixTime(page, '2026-09-07T22:16:00+09:00')
    await page.goto('/')

    const notice = page.getByRole('region', { name: NOTICE_TITLE })
    await expect(notice).toContainText('마쳤습니다')
    await expect(notice).not.toContainText('종료까지')
    await expect(notice).not.toContainText('이용하실 수 없어요')
  })
})
