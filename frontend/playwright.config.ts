import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 설정 (반응형 · 기능 E2E 검증).
 *
 * - 백엔드 없이 실행 가능: 모든 `/api/v1/**` 응답은 `tests/helpers/mockApi.ts` 가 `page.route` 로 목킹한다.
 * - 뷰포트는 각 테스트에서 `page.setViewportSize()` 로 바꾸므로 프로젝트는 데스크톱 크로뮴 하나면 충분하다.
 * - 시간·언어를 Asia/Seoul / ko-KR 로 고정해 날짜 표기가 실행 환경에 흔들리지 않게 한다.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  retries: 0,
  forbidOnly: !!process.env.CI,
  workers: process.env.CI ? 2 : undefined,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: 'http://localhost:5173',
    // 첫 실패 시에만 trace 를 남긴다. (Playwright 의 유효 값은 'retain-on-first-failure' 이다)
    trace: 'retain-on-first-failure',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
