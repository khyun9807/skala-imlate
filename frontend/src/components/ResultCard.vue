<script setup lang="ts">
/** 등록 성공 결과 카드. 신규 등록과 중복 등록을 시각적으로 구분한다. */

import { computed } from 'vue'

import { formatClockTime, formatKoreanDate, formatTimeHm } from '../utils/format'
import type { RegistrationResponse } from '../api/types'

interface Props {
  result: RegistrationResponse
}

const props = defineProps<Props>()

const isDuplicate = computed(() => props.result.duplicate === true)
const title = computed(() => (isDuplicate.value ? '이미 등록되어 있습니다' : '등록이 완료되었습니다'))
const description = computed(() =>
  isDuplicate.value
    ? '아래 내용으로 이미 접수되어 있어 새로 등록하지 않았습니다.'
    : '아래 내용으로 접수되었습니다. 화면을 닫아도 됩니다.',
)
const returnTimeLabel = computed(() => formatTimeHm(props.result.returnTime) || '23:30')
</script>

<template>
  <section
    class="result"
    :class="isDuplicate ? 'result--duplicate' : 'result--new'"
    aria-labelledby="result-title"
    tabindex="-1"
  >
    <p class="result__badge">
      <span class="badge" :class="isDuplicate ? 'badge--info' : 'badge--success'">
        {{ isDuplicate ? '중복 등록' : '등록 완료' }}
      </span>
    </p>

    <h2 id="result-title" class="result__title">{{ title }}</h2>
    <p class="result__desc">{{ description }}</p>

    <dl class="result__list">
      <div class="result__row">
        <dt>반</dt>
        <dd>{{ result.className }}</dd>
      </div>
      <div class="result__row">
        <dt>이름</dt>
        <dd>{{ result.studentName }}</dd>
      </div>
      <div class="result__row">
        <dt>기숙사 호수</dt>
        <dd>{{ result.roomNumber }}</dd>
      </div>
      <div class="result__row">
        <dt>복귀 시각</dt>
        <dd class="result__highlight">{{ returnTimeLabel }}</dd>
      </div>
      <div class="result__row">
        <dt>등록 일자</dt>
        <dd>{{ formatKoreanDate(result.registrationDate) }}</dd>
      </div>
      <div class="result__row">
        <dt>등록 시각</dt>
        <dd class="tabular">{{ formatClockTime(result.registeredAt) }}</dd>
      </div>
    </dl>

    <p class="result__note">
      22:30에 문이 잠기며 {{ returnTimeLabel }}에 일괄 개방됩니다. 시간에 맞춰 도착해 주세요.
    </p>
  </section>
</template>

<style scoped>
.result {
  padding: clamp(1rem, 3.5vw, 1.75rem);
  border-radius: var(--radius-lg);
  border: var(--border-width) solid var(--c-border);
  background: var(--c-surface);
  box-shadow: var(--shadow-1);
}

.result--new {
  border-color: var(--c-success-border);
}

.result--duplicate {
  border-color: var(--c-info-border);
}

.result:focus-visible {
  outline: 3px solid var(--c-focus);
  outline-offset: 3px;
}

.result__badge {
  margin-bottom: var(--space-3);
}

.result__title {
  font-size: var(--fs-lg);
  font-weight: 800;
}

.result__desc {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}

.result__list {
  margin: var(--space-4) 0 0;
  display: grid;
  gap: var(--space-1);
}

.result__row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: var(--border-width) solid var(--c-border);
}

.result__row:last-child {
  border-bottom: 0;
}

.result__row dt {
  flex: 0 0 auto;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text-muted);
}

.result__row dd {
  margin: 0;
  min-width: 0;
  flex: 1 1 auto;
  text-align: right;
  font-weight: 700;
}

.result__highlight {
  color: var(--c-link);
  font-size: var(--fs-lg);
}

.result__note {
  margin-top: var(--space-4);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--c-surface-alt);
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}
</style>
