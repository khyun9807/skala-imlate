<script setup lang="ts">
/** 서버 시간 기준 마감 카운트다운 표시. 상태별로 색과 문구가 바뀐다. */

import { computed } from 'vue'

import { formatDuration } from '../utils/format'
import type { WindowState } from '../composables/useServerClock'

interface Props {
  /** 등록 창 상태 */
  state: WindowState
  /** 마감까지 남은 초 */
  seconds: number
  /** 마감 시각 라벨 `22:00` */
  closeTime: string
}

const props = defineProps<Props>()

const toneClass = computed(() => {
  switch (props.state) {
    case 'open':
      return props.seconds <= 600 ? 'countdown--warn' : 'countdown--open'
    case 'closed':
      return 'countdown--closed'
    case 'error':
      return 'countdown--neutral'
    default:
      return 'countdown--neutral'
  }
})

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

const caption = computed(() => {
  switch (props.state) {
    case 'open':
      return `오늘 ${props.closeTime} 마감까지 남은 시간`
    case 'closed':
      return `오늘 등록은 마감되었습니다 (${props.closeTime})`
    case 'error':
      return '서버 시간을 불러오지 못했습니다'
    default:
      return '서버 시간을 확인하고 있습니다'
  }
})
</script>

<template>
  <div class="countdown" :class="toneClass" role="timer" aria-live="off">
    <span class="countdown__caption">{{ caption }}</span>
    <strong class="countdown__value tabular">{{ headline }}</strong>
  </div>
</template>

<style scoped>
.countdown {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-4);
  border-radius: var(--radius-lg);
  border: var(--border-width) solid transparent;
  text-align: center;
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

.countdown--open {
  background: var(--c-info-bg);
  color: var(--c-info-text);
  border-color: var(--c-info-border);
}

.countdown--warn {
  background: var(--c-warn-bg);
  color: var(--c-warn-text);
  border-color: var(--c-warn-border);
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
