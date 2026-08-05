<script setup lang="ts">
/** WAL ↔ DB 대사 결과 배지. CONSISTENT 초록 / RECOVERED 파랑 / MISMATCH 주황 / WAL_UNAVAILABLE 회색. */

import { computed } from 'vue'

import type { VerificationStatus } from '../api/types'

interface Props {
  status: VerificationStatus | string
}

const props = defineProps<Props>()

const LABELS: Record<string, string | undefined> = {
  CONSISTENT: '검증 일치',
  RECOVERED: '누락 복구 완료',
  MISMATCH: '차이 확인 필요',
  WAL_UNAVAILABLE: '검증 불가',
}

const TONES: Record<string, string | undefined> = {
  CONSISTENT: 'badge--success',
  RECOVERED: 'badge--info',
  MISMATCH: 'badge--warn',
  WAL_UNAVAILABLE: 'badge--neutral',
}

const label = computed(() => LABELS[props.status] ?? props.status)
const tone = computed(() => TONES[props.status] ?? 'badge--neutral')
</script>

<template>
  <span class="badge" :class="tone">{{ label }}</span>
</template>
