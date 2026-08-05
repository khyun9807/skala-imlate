<script setup lang="ts">
/** 통계 숫자 한 칸. */

import { computed } from 'vue'

import { formatNumber } from '../utils/format'

interface Props {
  /** 항목 이름 */
  label: string
  /** 숫자 값 */
  value: number
  /** 값 뒤에 붙는 단위 */
  unit?: string
}

const props = withDefaults(defineProps<Props>(), { unit: '' })

const display = computed(() => formatNumber(props.value))
</script>

<template>
  <div class="stat-card">
    <span class="stat-card__label">{{ label }}</span>
    <span class="stat-card__value tabular">
      {{ display }}<small v-if="unit" class="stat-card__unit">{{ unit }}</small>
    </span>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
  padding: var(--space-4);
  border-radius: var(--radius-md);
  border: var(--border-width) solid var(--c-border);
  background: var(--c-surface-alt);
}

.stat-card__label {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}

.stat-card__value {
  font-size: var(--fs-lg);
  font-weight: 800;
  line-height: var(--lh-tight);
}

.stat-card__unit {
  margin-left: 2px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text-muted);
}
</style>
