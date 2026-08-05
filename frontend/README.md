# imlate 프론트엔드

기숙사 야간 복귀(23:30) 등록 시스템의 웹 화면입니다.
**Vue 3.5 + Vite 6 + TypeScript + vue-router 4** 로 구성했고, 상태관리 라이브러리와 UI 프레임워크는 쓰지 않습니다(순수 CSS).

## 빠른 시작

```bash
npm install
npm run dev        # http://localhost:5173 (그 중 /api 요청은 http://localhost:8080 으로 프록시)
```

백엔드(`backend/`)를 8080 포트로 함께 띄우면 실제 데이터로 동작합니다.

## 스크립트

| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버(5173, `/api` → `localhost:8080` 프록시) |
| `npm run build` | 타입 검사(`vue-tsc -b`) 후 `dist/` 로 빌드 |
| `npm run preview` | 빌드 결과 미리보기(4173) |
| `npm run typecheck` | 타입 검사만 수행 |
| `npm run test:e2e` | Playwright E2E (테스트는 `tests/` 담당 모듈에서 작성) |

## 환경변수

`.env.example` 을 `.env.local` 로 복사해 사용합니다.

| 이름 | 기본값 | 설명 |
|---|---|---|
| `VITE_API_BASE` | `/api/v1` | API 베이스 경로. 프론트/백엔드 도메인이 다르면 전체 URL을 넣습니다. |

## 화면

| 경로 | 화면 | 설명 |
|---|---|---|
| `/` | `RegisterView` | 반·이름·호수 등록. 서버 시간 기준 마감 카운트다운, 이전 입력값 자동 채움 |
| `/lookup?date=&token=` | `LookupView` | 사감용 명단·검증 결과·통계·인쇄 |
| 그 외 | `NotFoundView` | 안내 후 등록 화면으로 이동 |

## 디렉터리

```
src/
├─ api/          client.ts(fetch 래퍼·ApiError), types.ts(서버 응답 타입)
├─ components/   AppHeader, FormField, CountdownBadge, ResultCard,
│                StatusBadge, RegistrationTable, StatCard
├─ composables/  useServerClock(서버 시간 카운트다운), useLastInput(이전 입력 기억)
├─ router/       라우트 정의
├─ styles/       tokens.css(디자인 토큰), base.css(전역·공통 UI·인쇄)
├─ utils/        format.ts(날짜/시간/숫자), storage.ts(안전한 localStorage)
└─ views/        RegisterView, LookupView, NotFoundView
```

## 설계 메모

### 서버 시간 기준 카운트다운
마운트 시 `GET /registrations/window` 의 `serverTime` 과 클라이언트 시각의 차이를 **오차(offset)** 로 저장하고,
1초마다 `closesAt` 과 비교해 남은 시간을 계산합니다. 사용자 PC 시계가 틀려도 마감 시각이 어긋나지 않습니다.
탭이 다시 활성화되면 자동으로 서버 시각을 재조회하고, 남은 시간이 0이 되는 순간에도 한 번 재확인합니다.

### 이전 입력값 기억 (R6)
- `imlate.lastInput` — 마지막 성공 입력 1건, 다음 방문 시 자동 채움
- `imlate.recentInputs` — 최근 3건, 각 입력칸 `datalist` 제안
- 개인정보 배려를 위해 **"저장된 정보 지우기"** 버튼으로 언제든 삭제 가능
- localStorage 접근이 막힌 환경(사파리 프라이빗 등)에서는 메모리 폴백으로 조용히 동작

### 통계 헤더
모든 API 요청에 `X-Visitor-Id`(localStorage `imlate.visitorId`) 를 붙입니다.
`crypto.randomUUID()` → `crypto.getRandomValues()` → `Math.random()` 순으로 폴백합니다.

### 오류 처리
서버 `ErrorResponse`(`code`/`message`/`errors`)를 `ApiError`(code, status, message, fieldErrors, retryAfterSeconds)로 정규화합니다.
`errors[].field` 는 해당 입력칸 아래에 표시하고 포커스를 옮깁니다.
네트워크 단절·10초 타임아웃(AbortController)도 사용자 문구로 안내합니다.

## 반응형·접근성 체크리스트

- 320px ~ 2560px 전 구간 **가로 스크롤 0**(`html, body { overflow-x: hidden }` + 컨테이너 `clamp()` 패딩)
- 컨테이너 최대 폭: 등록 720px / 조회 1100px
- 명단은 601px 이상 표, **600px 이하 카드 리스트**, 표는 `overflow-x: auto` 래퍼 안
- 입력 요소 `font-size: max(16px, 1rem)` — iOS 자동 확대 방지
- 버튼·링크·`summary` 터치 타겟 44px 이상, `:focus-visible` 아웃라인 명시
- 한글 줄바꿈 `word-break: keep-all; overflow-wrap: anywhere;`
- `100dvh`(폴백 `100vh`), `env(safe-area-inset-*)` 로 노치 대응
- `prefers-color-scheme: dark` 전면 대응(토큰 CSS 변수), `prefers-reduced-motion` 존중
- 라벨 `for`/`id` 연결, 오류는 `aria-live="polite"`, 상태 변화는 `role="status"` 로 안내
- 명암비 WCAG AA 이상(라이트/다크 모두)
- 인쇄용 스타일: 불필요 UI(`.no-print`) 숨김, 표는 흑백 테두리, 항상 표 형태로 출력

## 배포

`npm run build` → `dist/` 를 Nginx 또는 Spring 정적 서빙에 올립니다.
SPA 이므로 서버에서 **알 수 없는 경로는 `index.html` 로 폴백**하도록 설정해야 `/lookup` 직접 접속이 동작합니다.
