/**
 * 반응형 검증에 사용하는 기기 뷰포트 매트릭스와 DOM 측정 헬퍼.
 *
 * 요구사항(request.md): "아이폰, 갤럭시, 태블릿, 윈도우 PC, 맥 PC에서 모두 가시성 있게 잘 보여야 한다.
 * 직관적이고 화면을 벗어나거나 글자가 깨지지 않아야 한다." → 아래 검사들이 이 문장을 자동으로 증명한다.
 */

import type { Page } from '@playwright/test'

/** 한 개의 뷰포트 정의 */
export interface ViewportCase {
  /** 테스트 이름에 표시할 기기 설명 */
  name: string
  width: number
  height: number
}

/** 모바일(카드 레이아웃 · 터치 타겟) 판정 기준 폭. `@media (max-width: 600px)` 와 동일하다. */
export const MOBILE_MAX_WIDTH = 600

/** 터치 타겟 최소 높이(px). SPEC §11 "터치 타겟 ≥ 44px" */
export const MIN_TAP_SIZE = 44

/** SPEC §12 뷰포트 매트릭스 (320px ~ 2560px) */
export const VIEWPORTS: ViewportCase[] = [
  { name: '320x568 소형 안드로이드', width: 320, height: 568 },
  { name: '360x800 갤럭시 S', width: 360, height: 800 },
  { name: '375x667 아이폰 SE', width: 375, height: 667 },
  { name: '390x844 아이폰 14', width: 390, height: 844 },
  { name: '430x932 아이폰 Pro Max', width: 430, height: 932 },
  { name: '768x1024 아이패드 세로', width: 768, height: 1024 },
  { name: '1024x768 아이패드 가로', width: 1024, height: 768 },
  { name: '1280x800 맥북', width: 1280, height: 800 },
  { name: '1920x1080 윈도우 PC', width: 1920, height: 1080 },
  { name: '2560x1440 와이드 모니터', width: 2560, height: 1440 },
]

/** 문서 전체 가로 넘침 측정 결과 */
export interface DocumentOverflow {
  /** `document.documentElement.scrollWidth` */
  scrollWidth: number
  /** `document.documentElement.clientWidth` (세로 스크롤바 제외한 실제 콘텐츠 폭) */
  clientWidth: number
  /** `window.innerWidth` (스크롤바 포함) */
  innerWidth: number
}

/** 뷰포트 경계를 벗어난 요소 1건 */
export interface OverflowViolation {
  selector: string
  text: string
  left: number
  right: number
  limit: number
}

/** 글자가 잘린(넘친) 요소 1건 */
export interface ClippedTextViolation {
  selector: string
  text: string
  scrollWidth: number
  clientWidth: number
}

/** 터치 타겟이 작은 요소 1건 */
export interface TapTargetViolation {
  selector: string
  text: string
  width: number
  height: number
}

/**
 * 브라우저 컨텍스트에서 공통으로 쓰는 헬퍼 정의.
 * `page.evaluate` 안에서만 실행되므로 문자열로 주입하지 않고 각 함수 안에서 중복 정의한다.
 */
const DOM_HELPERS = `
  function describe(el) {
    const id = el.id ? '#' + el.id : '';
    const cls = typeof el.className === 'string' && el.className.trim().length > 0
      ? '.' + el.className.trim().split(/\\s+/).slice(0, 3).join('.')
      : '';
    return el.tagName.toLowerCase() + id + cls;
  }
  function shortText(el) {
    return (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60);
  }
  function isRendered(el) {
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || style.contentVisibility === 'hidden') {
      return false;
    }
    if (Number(style.opacity) === 0) {
      return false;
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 || rect.height > 0;
  }
  // 가로 잘림 검사이므로 "가로" 스크롤을 의도적으로 허용한 컨테이너만 예외로 둔다.
  // (overflow-y 는 보지 않는다. overflow-x:hidden 을 준 요소는 overflow-y 의 used value 가
  //  auto 로 바뀌므로, overflow-y 까지 보면 body 이하 전체가 예외가 되어 검사가 무력화된다.)
  function isScrollable(el) {
    const overflowX = getComputedStyle(el).overflowX;
    return overflowX === 'auto' || overflowX === 'scroll';
  }
  function hasScrollableAncestor(el) {
    let node = el.parentElement;
    while (node && node !== document.body && node !== document.documentElement) {
      if (isScrollable(node)) {
        return true;
      }
      node = node.parentElement;
    }
    return false;
  }
  function hasDirectText(el) {
    for (const node of el.childNodes) {
      if (node.nodeType === 3 && (node.textContent || '').trim().length > 0) {
        return true;
      }
    }
    return false;
  }
`

/** 문서 전체의 가로 넘침 수치를 읽는다. */
export function measureDocumentOverflow(page: Page): Promise<DocumentOverflow> {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    innerWidth: window.innerWidth,
  }))
}

/**
 * 화면 우측(또는 좌측) 경계를 벗어난 요소를 모두 찾는다.
 * 오차 1px 을 허용한다(서브픽셀 렌더링).
 */
export function findOverflowingElements(page: Page): Promise<OverflowViolation[]> {
  return page.evaluate(`(() => {
    ${DOM_HELPERS}
    const limit = document.documentElement.clientWidth;
    const violations = [];
    for (const el of document.querySelectorAll('body *')) {
      if (el.closest('.sr-only') || el.classList.contains('skip-link')) {
        continue;
      }
      if (!isRendered(el)) {
        continue;
      }
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) {
        continue;
      }
      if (rect.right > limit + 1 || rect.left < -1) {
        violations.push({
          selector: describe(el),
          text: shortText(el),
          left: Math.round(rect.left * 100) / 100,
          right: Math.round(rect.right * 100) / 100,
          limit,
        });
      }
    }
    return violations.slice(0, 20);
  })()`) as Promise<OverflowViolation[]>
}

/**
 * 글자가 컨테이너 밖으로 잘린 요소를 찾는다(`scrollWidth > clientWidth + 1`).
 * 의도적으로 가로 스크롤을 허용한 컨테이너(`.table-wrap` 등)와 그 자손은 제외한다.
 */
export function findClippedText(page: Page): Promise<ClippedTextViolation[]> {
  return page.evaluate(`(() => {
    ${DOM_HELPERS}
    const violations = [];
    for (const el of document.querySelectorAll('body *')) {
      if (el.closest('.sr-only') || el.classList.contains('skip-link')) {
        continue;
      }
      if (!hasDirectText(el) || !isRendered(el)) {
        continue;
      }
      if (isScrollable(el) || hasScrollableAncestor(el)) {
        continue;
      }
      if (el.scrollWidth > el.clientWidth + 1) {
        violations.push({
          selector: describe(el),
          text: shortText(el),
          scrollWidth: el.scrollWidth,
          clientWidth: el.clientWidth,
        });
      }
    }
    return violations.slice(0, 20);
  })()`) as Promise<ClippedTextViolation[]>
}

/**
 * 터치 타겟(버튼·입력칸·링크 버튼)이 최소 크기보다 작은 요소를 찾는다.
 * 화면 밖으로 숨겨 둔 건너뛰기 링크는 제외한다.
 */
export function findSmallTapTargets(page: Page, minSize: number = MIN_TAP_SIZE): Promise<TapTargetViolation[]> {
  return page.evaluate(
    `(() => {
    ${DOM_HELPERS}
    const minSize = ${minSize};
    const selector = 'button, input:not([type=hidden]), select, textarea, a.btn, summary';
    const violations = [];
    for (const el of document.querySelectorAll(selector)) {
      if (el.closest('.sr-only') || el.classList.contains('skip-link')) {
        continue;
      }
      if (!isRendered(el)) {
        continue;
      }
      const rect = el.getBoundingClientRect();
      if (rect.height + 0.5 < minSize) {
        violations.push({
          selector: describe(el),
          text: shortText(el),
          width: Math.round(rect.width * 100) / 100,
          height: Math.round(rect.height * 100) / 100,
        });
      }
    }
    return violations;
  })()`,
  ) as Promise<TapTargetViolation[]>
}

/** 위반 목록을 assertion 메시지로 쓰기 좋은 문자열로 만든다. */
export function formatViolations(items: Array<Record<string, unknown>>): string {
  if (items.length === 0) {
    return '없음'
  }
  return `\n${items.map((item) => `  - ${JSON.stringify(item)}`).join('\n')}`
}
