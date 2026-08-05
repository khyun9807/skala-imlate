<script setup lang="ts">
/**
 * 등록 창 요약 배너.
 *
 * 한 덩어리 안에서 세 가지를 한눈에 보여 준다.
 * 1. 오늘 몇 시까지 등록할 수 있는지 (`closeTime`)
 * 2. 마감까지 남은 시간 (서버 시간 기준 카운트다운)
 * 3. 마감된 뒤에는 다음 등록이 언제 열리는지 (`nextOpenLabel`)
 *
 * 시각은 모두 부모가 서버 응답(`/registrations/window`)에서 뽑아 넘겨준다. 이 컴포넌트는 시각을 만들지 않는다.
 */

import { computed } from 'vue'

import { formatDuration } from '../utils/format'
import type { WindowState } from '../composables/useServerClock'

/** 마감 임박으로 강조하기 시작하는 남은 시간(초). 10분. */
const CLOSING_SOON_SECONDS = 600

interface Props {
  /** 등록 창 상태 */
  state: WindowState
  /** 마감까지 남은 초 */
  seconds: number
  /** 마감 시각 라벨 `21:45`. 서버 값이 없으면 빈 문자열 */
  closeTime: string
  /** 다음 등록이 열리는 시각 라벨 `내일 00:00`. 서버 값이 없으면 빈 문자열 */
  nextOpenLabel?: string
}

const props = withDefaults(defineProps<Props>(), { nextOpenLabel: '' })

/** 열려 있고 남은 시간이 10분 이하인가 */
const isClosingSoon = computed(() => props.state === 'open' && props.seconds <= CLOSING_SOON_SECONDS)

const toneClass = computed(() => {
  switch (props.state) {
    case 'open':
      return isClosingSoon.value ? 'countdown--warn' : 'countdown--open'
    case 'closed':
      return 'countdown--closed'
    default:
      return 'countdown--neutral'
  }
})

/** 큰 글씨로 표시할 값 */
const headline = computed(() => {
  switch (props.state) {
    case 'open':
      return formatDuration(props.seconds)
    case 'closed':
      return '등록 마감'
    case 'error':
      return '시간 확인 불가'
    default:
      return '확인 중…'
  }
})

/** 큰 글씨 위에 오는 문장 — "오늘 몇 시까지 등록할 수 있는지" */
const caption = computed(() => {
  switch (props.state) {
    case 'open':
      return props.closeTime ? `오늘 ${props.closeTime}까지 등록할 수 있어요` : '지금 등록할 수 있어요'
    case 'closed':
      return props.closeTime
        ? `오늘 밤 복귀 등록은 마감되었습니다 (${props.closeTime})`
        : '오늘 밤 복귀 등록은 마감되었습니다'
    case 'error':
      return '서버 시간을 불러오지 못했습니다'
    default:
      return '서버 시간을 확인하고 있습니다'
  }
})

/** 큰 글씨 아래에 오는 보조 문장 */
const meta = computed(() => {
  switch (props.state) {
    case 'open':
      return '마감까지 남은 시간'
    case 'closed':
      return props.nextOpenLabel ? `다음 등록 시작: ${props.nextOpenLabel}` : ''
    default:
      return ''
  }
})
</script>

<template>
  <div class="countdown" :class="toneClass" role="timer" aria-live="off">
    <span v-if="isClosingSoon" class="countdown__flag">
      <svg class="countdown__icon" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
        <circle cx="8" cy="8" r="6.4" fill="none" stroke="currentColor" stroke-width="1.6" />
        <path d="M8 4.4V8.3l2.4 1.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      마감 임박
    </span>
    <span class="countdown__caption">{{ caption }}</span>
    <strong class="countdown__value tabular">{{ headline }}</strong>
    <span v-if="meta" class="countdown__meta">{{ meta }}</span>
  </div>
</template>

<style scoped>
.countdown {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-4);
  border-radius: var(--radius-lg);
  border: var(--border-width) solid transparent;
  text-align: center;
}

/* 마감 임박 표시. 경고 배경 위에서도 눈에 들어오도록 카드색 알약으로 한 겹 띄운다. */
.countdown__flag {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  margin-bottom: var(--space-1);
  padding: 0.25rem 0.625rem;
  border-radius: var(--radius-pill);
  border: var(--border-width) solid currentColor;
  background: var(--c-surface);
  font-size: var(--fs-sm);
  font-weight: 700;
  line-height: 1.5;
  white-space: nowrap;
}

.countdown__icon {
  flex: 0 0 auto;
}

.countdown__caption {
  font-size: var(--fs-sm);
  font-weight: 600;
}

.countdown__value {
  font-size: var(--fs-xl);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: var(--lh-tight);
}

.countdown__meta {
  font-size: var(--fs-sm);
  font-weight: 600;
  opacity: 0.85;
}

.countdown--open {
  background: var(--c-info-bg);
  color: var(--c-info-text);
  border-color: var(--c-info-border);
}

.countdown--warn {
  background: var(--c-warn-bg);
  color: var(--c-warn-text);
  border-color: var(--c-warn-border);
  /* 테두리 두께를 바꾸면 레이아웃이 흔들리므로 안쪽 그림자로만 한 겹 더한다. */
  box-shadow: inset 0 0 0 1px var(--c-warn-border);
}

.countdown--closed {
  background: var(--c-danger-bg);
  color: var(--c-danger-text);
  border-color: var(--c-danger-border);
}

.countdown--neutral {
  background: var(--c-neutral-bg);
  color: var(--c-neutral-text);
  border-color: var(--c-neutral-border);
}
</style>
