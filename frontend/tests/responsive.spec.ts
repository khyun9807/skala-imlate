/**
 * 반응형 검증 스위트 (요구사항 R5 / SPEC §12).
 *
 * "아이폰, 갤럭시, 태블릿, 윈도우 PC, 맥 PC에서 모두 가시성 있게 잘 보여야 한다.
 *  직관적이고 화면을 벗어나거나 글자가 깨지지 않고 완벽하게 검증까지 되어 있어야 한다."
 * → 320px ~ 2560px 뷰포트 10종 × 화면 2종에 대해 아래 4가지를 기계적으로 증명한다.
 *   1) 문서 가로 넘침 없음
 *   2) 모든 보이는 요소가 뷰포트 좌우 경계를 넘지 않음
 *   3) 글자 잘림 없음 (`scrollWidth <= clientWidth + 1`)
 *   4) 모바일 터치 타겟 ≥ 44px
 * 여기에 라이트/다크 두 테마 렌더링 검증을 더한다.
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

import {
  CURFEW_TIME_LABEL,
  LONG_ROSTER,
  LOOKUP_PATH,
  RETURN_TIME_LABEL,
  TEST_DATE_LABEL,
  TEST_ROSTER,
  installApiMocks,
} from './helpers/mockApi'
import type { MockOptions } from './helpers/mockApi'

/** 최대 길이(20자) 입력 — 결과 카드 최악 케이스 */
const LONG_INPUT = {
  className: LONG_ROSTER[0].className,
  studentName: LONG_ROSTER[0].studentName,
  roomNumber: LONG_ROSTER[0].roomNumber,
} as const
import {
  MIN_TAP_SIZE,
  MOBILE_MAX_WIDTH,
  VIEWPORTS,
  findClippedText,
  findOverflowingElements,
  findSmallTapTargets,
  formatViolations,
  measureDocumentOverflow,
} from './helpers/viewports'

/** 등록 화면을 열고 비동기 콘텐츠까지 모두 그려질 때까지 기다린다. */
async function openRegisterPage(page: Page, options: MockOptions = {}): Promise<void> {
  await installApiMocks(page, options)
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
  await expect(page.getByRole('timer')).toContainText('1시간 00분 00초')
  await expect(page.getByText('오늘 등록 인원')).toBeVisible()
  await settle(page)
}

/** 조회 화면을 열고 명단이 그려질 때까지 기다린다. */
async function openLookupPage(page: Page, options: MockOptions = {}): Promise<void> {
  await installApiMocks(page, options)
  await page.goto(LOOKUP_PATH)
  await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 명단' })).toBeVisible()
  await expect(page.getByText(TEST_ROSTER[11].studentName).filter({ visible: true })).toHaveCount(1)
  await settle(page)
}

/** 폰트 로딩과 한 프레임 렌더를 기다려 측정값이 흔들리지 않게 한다. */
async function settle(page: Page): Promise<void> {
  await page.evaluate(() => document.fonts.ready.then(() => true))
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  )
}

/** 네 가지 반응형 규칙을 한 번에 검사한다. */
async function assertResponsive(page: Page, viewportWidth: number): Promise<void> {
  // 1) 문서 자체가 가로로 넘치지 않는다.
  const doc = await measureDocumentOverflow(page)
  expect(
    doc.scrollWidth,
    `가로 스크롤 발생: scrollWidth=${doc.scrollWidth}, innerWidth=${doc.innerWidth}`,
  ).toBeLessThanOrEqual(doc.innerWidth + 1)
  expect(
    doc.scrollWidth,
    `가로 스크롤 발생: scrollWidth=${doc.scrollWidth}, clientWidth=${doc.clientWidth}`,
  ).toBeLessThanOrEqual(doc.clientWidth + 1)

  // 2) 보이는 요소가 화면 좌우 경계를 벗어나지 않는다.
  const overflowing = await findOverflowingElements(page)
  expect(overflowing, `화면 밖으로 나간 요소:${formatViolations(overflowing)}`).toEqual([])

  // 3) 글자가 잘리지 않는다.
  const clipped = await findClippedText(page)
  expect(clipped, `글자가 잘린 요소:${formatViolations(clipped)}`).toEqual([])

  // 4) 모바일에서는 터치 타겟이 44px 이상이다.
  if (viewportWidth <= MOBILE_MAX_WIDTH) {
    const smallTargets = await findSmallTapTargets(page, MIN_TAP_SIZE)
    expect(smallTargets, `터치 타겟이 ${MIN_TAP_SIZE}px 보다 작은 요소:${formatViolations(smallTargets)}`).toEqual([])
  }
}

test.describe('등록 화면(/) 반응형', () => {
  for (const viewport of VIEWPORTS) {
    test(`${viewport.name} 에서 넘침·잘림 없이 표시된다`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await openRegisterPage(page)

      // 핵심 문구가 실제로 보인다.
      await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
      await expect(page.getByText(TEST_DATE_LABEL)).toBeVisible()
      await expect(page.getByRole('heading', { name: '복귀 정보 입력' })).toBeVisible()
      await expect(page.getByLabel('반')).toBeVisible()
      await expect(page.getByLabel('이름')).toBeVisible()
      await expect(page.getByLabel('기숙사 호수')).toBeVisible()
      await expect(page.getByRole('button', { name: `${RETURN_TIME_LABEL} 복귀 등록하기` })).toBeVisible()
      await expect(page.getByText(`${CURFEW_TIME_LABEL} 문 잠김`)).toBeVisible()

      await assertResponsive(page, viewport.width)
    })
  }
})

test.describe('조회 화면(/lookup) 반응형', () => {
  for (const viewport of VIEWPORTS) {
    test(`${viewport.name} 에서 넘침·잘림 없이 표시된다`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await openLookupPage(page)

      await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 명단' })).toBeVisible()
      await expect(page.getByText(TEST_DATE_LABEL)).toBeVisible()
      await expect(page.getByText(`총 ${TEST_ROSTER.length}명`)).toBeVisible()
      await expect(page.getByRole('heading', { name: '검증 결과' })).toBeVisible()
      await expect(page.getByRole('heading', { name: '반별 인원' })).toBeVisible()
      await expect(page.getByRole('heading', { name: '복귀 명단', exact: true })).toBeVisible()
      await expect(page.getByRole('searchbox', { name: '이름, 반, 호수로 검색' })).toBeVisible()

      await assertResponsive(page, viewport.width)
    })
  }
})

test.describe('상태 화면 반응형', () => {
  const stateViewports = VIEWPORTS.filter((viewport) =>
    [320, 390, 768, 1280, 1920].includes(viewport.width),
  )

  for (const viewport of stateViewports) {
    test(`마감 안내 화면 — ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await installApiMocks(page, { window: 'closed' })
      await page.goto('/')
      await expect(page.getByRole('timer')).toContainText('등록 마감')
      await expect(page.getByText('오늘 등록 인원')).toBeVisible()
      await settle(page)

      await assertResponsive(page, viewport.width)
    })

    test(`명단 12명 + 검증 차이 화면 — ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await openLookupPage(page, { verification: 'MISMATCH' })

      await expect(page.getByText('차이 확인 필요')).toBeVisible()
      await assertResponsive(page, viewport.width)
    })

    test(`등록 결과 카드 화면 — ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await openRegisterPage(page)

      await page.getByLabel('반').fill(LONG_INPUT.className)
      await page.getByLabel('이름').fill(LONG_INPUT.studentName)
      await page.getByLabel('기숙사 호수').fill(LONG_INPUT.roomNumber)
      await page.getByRole('button', { name: `${RETURN_TIME_LABEL} 복귀 등록하기` }).click()

      await expect(page.getByRole('heading', { name: '등록이 완료되었습니다' })).toBeVisible()
      await settle(page)

      // 20자짜리 최악 케이스 입력도 결과 카드를 넘치게 하지 않는다.
      await assertResponsive(page, viewport.width)
    })

    test(`오류 안내(429) 화면 — ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await openRegisterPage(page, { register: 'rateLimited' })

      await page.getByLabel('반').fill('1반')
      await page.getByLabel('이름').fill('홍길동')
      await page.getByLabel('기숙사 호수').fill('302')
      await page.getByRole('button', { name: `${RETURN_TIME_LABEL} 복귀 등록하기` }).click()

      await expect(page.getByRole('alert')).toContainText('요청이 너무 많습니다.')
      await settle(page)

      await assertResponsive(page, viewport.width)
    })

    test(`20자 최대 길이 명단 — ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await installApiMocks(page, { roster: 'long' })
      await page.goto(LOOKUP_PATH)

      await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 명단' })).toBeVisible()
      await expect(page.getByText(LONG_ROSTER[0].studentName).filter({ visible: true })).toHaveCount(1)
      await settle(page)

      await assertResponsive(page, viewport.width)
    })
  }
})

test.describe('테마 렌더링', () => {
  const themeViewports = VIEWPORTS.filter((viewport) =>
    [320, 390, 768, 1280, 1920].includes(viewport.width),
  )

  /** 배경색 토큰: 라이트 `#f4f6f9`, 다크 `#14171b` */
  const BACKGROUNDS = {
    light: 'rgb(244, 246, 249)',
    dark: 'rgb(20, 23, 27)',
  } as const

  test.describe('라이트 모드', () => {
    test.use({ colorScheme: 'light' })

    for (const viewport of themeViewports) {
      test(`${viewport.name} 라이트 모드 렌더링`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await openRegisterPage(page)

        const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
        expect(background).toBe(BACKGROUNDS.light)
        await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
        await assertResponsive(page, viewport.width)
      })
    }
  })

  test.describe('다크 모드', () => {
    test.use({ colorScheme: 'dark' })

    for (const viewport of themeViewports) {
      test(`${viewport.name} 다크 모드 렌더링`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await openRegisterPage(page)

        const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
        expect(background).toBe(BACKGROUNDS.dark)
        await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
        await assertResponsive(page, viewport.width)
      })
    }

    test('다크 모드 조회 화면도 넘침이 없다', async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await openLookupPage(page)

      const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
      expect(background).toBe(BACKGROUNDS.dark)
      await assertResponsive(page, 390)
    })
  })
})
