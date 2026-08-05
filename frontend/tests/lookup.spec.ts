/**
 * 사감용 조회 화면(`/lookup`) 기능 E2E.
 *
 * 검증 항목
 * - 12명 명단이 모두 렌더링되고 반·이름·호수·등록시각이 보인다
 * - 대사(검증) 결과·복구 처리·통계는 **화면에 나오지 않는다** (사용자 피드백 2번, 회귀 방지)
 * - 검색어·반 필터 동작
 * - 모바일(<=600px) 카드 레이아웃 전환
 * - 토큰 없음 / 권한 없음 / 빈 명단 안내
 */

import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

import {
  CURFEW_TIME_LABEL,
  FORBIDDEN_PATHS,
  LOOKUP_PATH,
  RETURN_TIME_LABEL,
  TEST_BY_CLASS,
  TEST_BY_ROOM,
  TEST_DATE_LABEL,
  TEST_ROSTER,
  installApiMocks,
} from './helpers/mockApi'
import type { ApiMock, MockOptions } from './helpers/mockApi'

/** 데스크톱 기준 뷰포트 */
const DESKTOP = { width: 1280, height: 800 }
/** 모바일 기준 뷰포트 (아이폰 SE) */
const MOBILE = { width: 375, height: 667 }

/** `HH:mm` 만 잘라 낸다 (화면 표기와 동일). */
function clock(value: string): string {
  return value.slice(11, 16)
}

/** 목을 설치하고 조회 화면을 연다. */
async function openLookup(page: Page, options: MockOptions = {}, path: string = LOOKUP_PATH): Promise<ApiMock> {
  const mock = await installApiMocks(page, options)
  await page.goto(path)
  return mock
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

  test('반별 인원과 안내 문구가 표시된다', async ({ page }) => {
    await openLookup(page)

    await expect(page.getByRole('heading', { name: '반별 인원' })).toBeVisible()
    // 반은 숫자만 저장되므로 칩에는 "반" 라벨을 붙여 보여 준다("1 5명" 이면 무엇이 반인지 모른다).
    for (const entry of TEST_BY_CLASS) {
      await expect(page.getByRole('button', { name: `${entry.className}반 ${entry.count}명` })).toBeVisible()
    }

    // 호수별 인원은 접혀 있다가 펼치면 보인다.
    await page.getByText('호수별 인원 보기').click()
    for (const entry of TEST_BY_ROOM) {
      await expect(page.getByText(`${entry.roomNumber} ${entry.count}명`)).toBeVisible()
    }

    await expect(
      page.getByText(`${CURFEW_TIME_LABEL} 이후 기숙사 문이 잠기며, ${RETURN_TIME_LABEL}에 일괄 개방됩니다.`),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: '인쇄' })).toBeVisible()
  })
})

test.describe('검증·통계 비노출 (회귀 방지)', () => {
  test.use({ viewport: DESKTOP })

  /**
   * 사용자 피드백 2번: "검증(대사) 결과·복구 처리·통계는 화면에 안 보이게 한다."
   * 아래 문구 중 하나라도 화면에 다시 나타나면 이 테스트가 먼저 실패한다.
   */
  const FORBIDDEN_TEXTS = [
    '검증',
    '대사',
    'WAL',
    'Redis',
    'DB',
    '복구',
    '차이 항목',
    '확인 시각',
    '통계',
    '방문자',
    '페이지뷰',
  ]

  test('명단 화면 어디에도 검증·복구·통계 문구가 없다', async ({ page }) => {
    await openLookup(page)
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)

    for (const text of FORBIDDEN_TEXTS) {
      await expect(page.getByText(text), `화면에 남아 있으면 안 되는 문구: ${text}`).toHaveCount(0)
    }
    await expect(page.getByRole('heading', { name: '검증 결과' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '통계' })).toHaveCount(0)
  })

  test('검증·통계 API 를 호출하지 않는다', async ({ page }) => {
    const mock = await openLookup(page)
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)

    for (const forbidden of FORBIDDEN_PATHS) {
      expect(
        mock.handledPaths.some((path) => path.endsWith(forbidden)),
        `호출되면 안 되는 API: ${forbidden} (실제 호출: ${mock.handledPaths.join(', ')})`,
      ).toBe(false)
    }
  })

  test('응답에 verification·stats 가 없어도 명단이 정상 렌더링된다', async ({ page }) => {
    const lookupResponse = page.waitForResponse((response) => response.url().includes('/api/v1/lookup'))

    await openLookup(page)
    await expect(tableRows(page)).toHaveCount(TEST_ROSTER.length)

    // 목이 새 계약(verification/stats 없음)을 따르는지도 함께 확인한다.
    const body = (await (await lookupResponse).json()) as Record<string, unknown>
    expect(body).not.toHaveProperty('verification')
    expect(body).not.toHaveProperty('stats')
    expect(body.totalCount).toBe(TEST_ROSTER.length)
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

    // 반이 숫자가 된 뒤로는 "3" 같은 한 글자가 호수(302, 301 …)에도 걸려 검색어로 쓸 수 없다.
    // 이 테스트가 보려는 것은 "검색이 카드에도 적용되는가"이므로 이름으로 좁힌다.
    await searchBox(page).fill('오세훈')
    await expect(cards(page)).toHaveCount(1)
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
