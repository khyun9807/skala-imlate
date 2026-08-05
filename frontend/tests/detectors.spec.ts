/**
 * 반응형 검사기(`helpers/viewports.ts`) 자가 검증.
 *
 * responsive.spec.ts 가 "통과"하는 것이 의미를 가지려면, 검사기가 실제 결함을 잡아낼 수 있어야 한다.
 * 여기서는 일부러 깨진 요소를 주입해 각 검사기가 그것을 정확히 집어내는지 확인한다.
 * (검사기가 무력화되면 이 파일이 먼저 실패한다.)
 */

import { expect, test } from '@playwright/test'

import { installApiMocks } from './helpers/mockApi'
import {
  MIN_TAP_SIZE,
  findClippedText,
  findOverflowingElements,
  findSmallTapTargets,
  measureDocumentOverflow,
} from './helpers/viewports'

test.describe('반응형 검사기 자가 검증', () => {
  test.use({ viewport: { width: 375, height: 667 } })

  test.beforeEach(async ({ page }) => {
    await installApiMocks(page)
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: '야간 복귀 등록' })).toBeVisible()
  })

  test('화면 밖으로 나간 요소를 잡아낸다', async ({ page }) => {
    expect(await findOverflowingElements(page)).toEqual([])

    await page.evaluate(() => {
      const broken = document.createElement('div')
      broken.id = 'e2e-overflow-probe'
      broken.style.cssText = 'width:3000px;height:24px;background:red'
      document.body.appendChild(broken)
    })

    const violations = await findOverflowingElements(page)
    expect(violations.map((item) => item.selector)).toContain('div#e2e-overflow-probe')

    await page.evaluate(() => document.getElementById('e2e-overflow-probe')?.remove())
    expect(await findOverflowingElements(page)).toEqual([])
  })

  test('글자가 잘린 요소를 잡아낸다', async ({ page }) => {
    expect(await findClippedText(page)).toEqual([])

    await page.evaluate(() => {
      const broken = document.createElement('div')
      broken.id = 'e2e-clip-probe'
      broken.style.cssText = 'width:40px;overflow:hidden;white-space:nowrap'
      broken.textContent = '화면을 벗어나 잘려 버리는 아주 긴 안내 문구입니다'
      document.body.appendChild(broken)
    })

    const violations = await findClippedText(page)
    expect(violations.map((item) => item.selector)).toContain('div#e2e-clip-probe')

    await page.evaluate(() => document.getElementById('e2e-clip-probe')?.remove())
    expect(await findClippedText(page)).toEqual([])
  })

  test('작은 터치 타겟을 잡아낸다', async ({ page }) => {
    expect(await findSmallTapTargets(page, MIN_TAP_SIZE)).toEqual([])

    await page.evaluate(() => {
      const broken = document.createElement('button')
      broken.id = 'e2e-tap-probe'
      broken.type = 'button'
      broken.style.cssText = 'height:20px;min-height:0;padding:0;border:0'
      broken.textContent = '작은 버튼'
      document.body.appendChild(broken)
    })

    const violations = await findSmallTapTargets(page, MIN_TAP_SIZE)
    expect(violations.map((item) => item.selector)).toContain('button#e2e-tap-probe')

    await page.evaluate(() => document.getElementById('e2e-tap-probe')?.remove())
    expect(await findSmallTapTargets(page, MIN_TAP_SIZE)).toEqual([])
  })

  test('문서 가로 넘침 수치가 실제 넘침에 반응한다', async ({ page }) => {
    const before = await measureDocumentOverflow(page)
    expect(before.scrollWidth).toBeLessThanOrEqual(before.clientWidth + 1)

    // `html { overflow-x: hidden }` 때문에 문서 단위 수치만으로는 넘침이 감춰질 수 있다.
    // 그래서 responsive.spec.ts 는 요소 단위 경계 검사(findOverflowingElements)를 함께 수행한다.
    await page.evaluate(() => {
      const broken = document.createElement('div')
      broken.id = 'e2e-doc-overflow-probe'
      broken.style.cssText = 'width:3000px;height:24px'
      document.body.appendChild(broken)
    })

    const overflowing = await findOverflowingElements(page)
    expect(overflowing.length).toBeGreaterThan(0)

    await page.evaluate(() => document.getElementById('e2e-doc-overflow-probe')?.remove())
  })
})
