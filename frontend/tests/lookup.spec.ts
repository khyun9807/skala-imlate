/**
 * 사감용 조회 화면(`/lookup`) 기능 E2E.
 *
 * 검증 항목
 * - 12명 명단이 모두 렌더링되고 반·이름·호수·등록시각이 보인다
 * - 검증(대사) 배지 상태별 표기
 * - 검색어·반 필터 동작
 * - 모바일(<=600px) 카드 레이아웃 전환
 * - 토큰 없음 / 권한 없음 / 빈 명단 안내
 */

import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

import {
  CURFEW_TIME_LABEL,
  LOOKUP_PATH,
  RETURN_TIME_LABEL,
  TEST_BY_CLASS,
  TEST_DATE_LABEL,
  TEST_ROSTER,
  TEST_STATS,
  VERIFICATION_LABELS,
  installApiMocks,
} from './helpers/mockApi'
import type { MockOptions, VerificationStatus } from './helpers/mockApi'

/** 데스크톱 기준 뷰포트 */
const DESKTOP = { width: 1280, height: 800 }
/** 모바일 기준 뷰포트 (아이폰 SE) */
const MOBILE = { width: 375, height: 667 }

/** `HH:mm` 만 잘라 낸다 (화면 표기와 동일). */
function clock(value: string): string {
  return value.slice(11, 16)
}

/** 목을 설치하고 조회 화면을 연다. */
async function openLookup(page: Page, options: MockOptions = {}, path: string = LOOKUP_PATH): Promise<void> {
  await installApiMocks(page, options)
  await page.goto(path)
}

/** 표(데스크톱) 로케이터 */
function tableRows(page: Page): Locator {
  return page.locator('.data-table tbody tr')
}

/** 카드(모바일) 로케이터 */
function cards(page: Page): Locator {
  return page.locator('.reg-card')
}

/** 검색 입력칸 */
function searchBox(page: Page): Locator {
  return page.getByRole('searchbox', { name: '이름, 반, 호수로 검색' })
}

test.describe('명단 표시 (데스크톱)', () => {
  test.use({ viewport: DESKTOP })

  test('12명 명단이 번호·반·이름·호수·등록시각과 함께 모두 렌더링된다', async ({ page }) => {
    await openLookup(page)

    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 명단' })).toBeVisible()
    await expect(page.getByText(TEST_DATE_LABEL)).toBeVisible()
    await expect(page.getByText(`총 ${TEST_ROSTER.length}명`)).toBeVisible()

    // 표 헤더
    for (const header of ['번호', '반', '이름', '호수', '등록시각']) {
      await expect(page.getByRole('columnheader', { name: header, exact: true })).toBeVisible()
    }

    const rows = tableRows(page)
    await expect(rows).toHaveCount(TEST_ROSTER.length)

    for (const entry of TEST_ROSTER) {
      const row = rows.nth(entry.no - 1)
      await expect(row).toContainText(String(entry.no))
      await expect(row).toContainText(entry.className)
      await expect(row).toContainText(entry.studentName)
      await expect(row).toContainText(entry.roomNumber)
      await expect(row).toContainText(clock(entry.registeredAt))
    }

    // 모바일 카드 리스트는 데스크톱에서 숨겨져 있다.
    await expect(page.locator('.reg-list__cards')).toBeHidden()
    await expect(page.locator('.reg-list__table-wrap')).toBeVisible()
  })

  test('반별 인원과 통계, 안내 문구가 표시된다', async ({ page }) => {
    await openLookup(page)

    await expect(page.getByRole('heading', { name: '반별 인원' })).toBeVisible()
    for (const entry of TEST_BY_CLASS) {
      await expect(page.getByRole('button', { name: `${entry.className} ${entry.count}명` })).toBeVisible()
    }

    await expect(page.getByRole('heading', { name: '통계' })).toBeVisible()
    await expect(page.getByText('오늘 방문자')).toBeVisible()
    await expect(page.getByText(TEST_STATS.totalVisitors.toLocaleString('ko-KR'))).toBeVisible()

    await expect(
      page.getByText(`${CURFEW_TIME_LABEL} 이후 기숙사 문이 잠기며, ${RETURN_TIME_LABEL}에 일괄 개방됩니다.`),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: '인쇄' })).toBeVisible()
  })
})

test.describe('검증(대사) 배지', () => {
  test.use({ viewport: DESKTOP })

  const statuses: VerificationStatus[] = ['CONSISTENT', 'RECOVERED', 'MISMATCH', 'WAL_UNAVAILABLE']

  for (const status of statuses) {
    test(`${status} 상태는 "${VERIFICATION_LABELS[status]}" 로 표시된다`, async ({ page }) => {
      await openLookup(page, { verification: status })

      await expect(page.getByText(VERIFICATION_LABELS[status])).toBeVisible()
      await expect(page.getByRole('heading', { name: '검증 결과' })).toBeVisible()
      await expect(page.getByText('DB 기록')).toBeVisible()
      await expect(page.getByText('WAL(Redis) 기록')).toBeVisible()
    })
  }

  test('MISMATCH 이면 차이 항목을 펼쳐 볼 수 있다', async ({ page }) => {
    await openLookup(page, { verification: 'MISMATCH' })

    const details = page.getByRole('group').filter({ hasText: '차이 항목 자세히 보기' })
    await expect(details).toBeVisible()
    await page.getByText('차이 항목 자세히 보기').click()
    await expect(page.getByText('DB 에만 있는 항목 (1건)')).toBeVisible()
    await expect(page.getByText('3반/서준호/503')).toBeVisible()
  })

  test('WAL_UNAVAILABLE 이면 WAL 카운트가 0 으로 보인다', async ({ page }) => {
    await openLookup(page, { verification: 'WAL_UNAVAILABLE' })

    await expect(page.getByText(`DB ${TEST_ROSTER.length} · WAL 0`)).toBeVisible()
  })
})

test.describe('검색 · 필터', () => {
  test.use({ viewport: DESKTOP })

  test('이름으로 검색하면 해당 인원만 남는다', async ({ page }) => {
    await openLookup(page)
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)

    await searchBox(page).fill('홍길동')
    await expect(tableRows(page)).toHaveCount(1)
    await expect(tableRows(page).first()).toContainText('홍길동')
    await expect(page.getByText(`1명 표시`)).toBeVisible()
    await expect(page.getByText(`전체 ${TEST_ROSTER.length}명`)).toBeVisible()

    await page.getByRole('button', { name: '필터 해제' }).click()
    await expect(searchBox(page)).toHaveValue('')
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)
  })

  test('호수로도 검색된다', async ({ page }) => {
    await openLookup(page)

    await searchBox(page).fill('302')
    await expect(tableRows(page)).toHaveCount(2)
    await expect(tableRows(page).nth(0)).toContainText('홍길동')
    await expect(tableRows(page).nth(1)).toContainText('박민수')
  })

  test('반 칩을 누르면 그 반만 남고, 다시 누르면 해제된다', async ({ page }) => {
    await openLookup(page)

    const chip = page.getByRole('button', { name: '1반 5명' })
    await chip.click()
    await expect(chip).toHaveAttribute('aria-pressed', 'true')
    await expect(tableRows(page)).toHaveCount(5)

    await chip.click()
    await expect(chip).toHaveAttribute('aria-pressed', 'false')
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)
  })

  test('조건에 맞는 인원이 없으면 안내 문구를 보여 준다', async ({ page }) => {
    await openLookup(page)

    await searchBox(page).fill('없는사람')
    await expect(page.getByText('검색 조건에 맞는 인원이 없습니다.')).toBeVisible()
    await expect(tableRows(page)).toHaveCount(0)
  })
})

test.describe('모바일 카드 레이아웃', () => {
  test.use({ viewport: MOBILE })

  test('600px 이하에서는 표 대신 카드로 12명이 표시된다', async ({ page }) => {
    await openLookup(page)

    await expect(page.locator('.reg-list__table-wrap')).toBeHidden()
    await expect(page.locator('.reg-list__cards')).toBeVisible()
    await expect(cards(page)).toHaveCount(TEST_ROSTER.length)

    for (const entry of TEST_ROSTER) {
      const card = cards(page).nth(entry.no - 1)
      await expect(card).toContainText(entry.studentName)
      await expect(card).toContainText(`반 ${entry.className}`)
      await expect(card).toContainText(`호수 ${entry.roomNumber}`)
      await expect(card).toContainText(clock(entry.registeredAt))
    }
  })

  test('모바일에서도 검색 필터가 카드에 적용된다', async ({ page }) => {
    await openLookup(page)

    await searchBox(page).fill('3반')
    await expect(cards(page)).toHaveCount(3)
    await expect(cards(page).first()).toContainText('오세훈')
  })
})

test.describe('접근 안내 화면', () => {
  test.use({ viewport: DESKTOP })

  test('토큰 없이 접근하면 조회 링크 안내를 보여 준다', async ({ page }) => {
    await openLookup(page, {}, '/lookup')

    await expect(page.getByRole('heading', { name: '조회 링크가 필요합니다' })).toBeVisible()
    await expect(
      page.getByText('이 페이지는 사감 선생님께 발송된 문자·이메일 안의 링크로만 열 수 있습니다.'),
    ).toBeVisible()
    await expect(page.locator('.data-table')).toHaveCount(0)
  })

  test('토큰이 만료되면(403) 안내 화면을 보여 준다', async ({ page }) => {
    await openLookup(page, { lookup: 'forbidden' })

    await expect(page.getByRole('heading', { name: '명단을 볼 수 없습니다' })).toBeVisible()
    await expect(page.getByText('조회 권한이 없습니다. 링크가 만료되었을 수 있습니다.')).toBeVisible()
  })

  test('서버 오류면 다시 시도 버튼을 제공한다', async ({ page }) => {
    await openLookup(page, { lookup: 'serverError' })

    await expect(page.getByText('명단을 불러오지 못했습니다')).toBeVisible()
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible()
  })

  test('등록 인원이 0명이면 빈 명단 안내를 보여 준다', async ({ page }) => {
    await openLookup(page, { lookup: 'empty' })

    await expect(page.getByText('오늘 등록된 인원이 없습니다.')).toBeVisible()
    await expect(page.getByText('총 0명')).toBeVisible()
  })
})
