<script setup lang="ts">
/**
 * 복귀 명단 목록.
 * 데스크톱/태블릿은 표, 600px 이하 모바일은 카드 리스트로 자동 전환한다.
 * 인쇄 시에는 언제나 표 형태를 사용한다.
 */

import { formatClockTime } from '../utils/format'
import type { LookupItem } from '../api/types'

interface Props {
  items: LookupItem[]
  /** 목록이 비었을 때 문구 */
  emptyMessage?: string
}

withDefaults(defineProps<Props>(), {
  emptyMessage: '표시할 인원이 없습니다.',
})
</script>

<template>
  <div class="reg-list">
    <p v-if="items.length === 0" class="reg-list__empty">{{ emptyMessage }}</p>

    <template v-else>
      <!-- 데스크톱/태블릿: 표 -->
      <div class="reg-list__table-wrap table-wrap">
        <table class="data-table">
          <caption class="sr-only">
            야간 복귀 등록 명단
          </caption>
          <thead>
            <tr>
              <th scope="col" class="reg-list__col-no">번호</th>
              <th scope="col">반</th>
              <th scope="col">이름</th>
              <th scope="col">호수</th>
              <th scope="col">등록시각</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="`${item.no}-${item.className}-${item.studentName}-${item.roomNumber}`">
              <td class="tabular">{{ item.no }}</td>
              <td>{{ item.className }}</td>
              <td class="text-strong">{{ item.studentName }}</td>
              <td class="tabular">{{ item.roomNumber }}</td>
              <td class="tabular">{{ formatClockTime(item.registeredAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 모바일: 카드 리스트 -->
      <ul class="reg-list__cards">
        <li
          v-for="item in items"
          :key="`card-${item.no}-${item.className}-${item.studentName}-${item.roomNumber}`"
          class="reg-card"
        >
          <div class="reg-card__head">
            <span class="reg-card__no tabular">{{ item.no }}</span>
            <span class="reg-card__name">{{ item.studentName }}</span>
            <span class="reg-card__time tabular">{{ formatClockTime(item.registeredAt) }}</span>
          </div>
          <div class="reg-card__meta">
            <span class="chip">
              반 <strong>{{ item.className }}</strong>
            </span>
            <span class="chip">
              호수 <strong>{{ item.roomNumber }}</strong>
            </span>
          </div>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.reg-list {
  min-width: 0;
}

.reg-list__empty {
  padding: var(--space-6) var(--space-4);
  text-align: center;
  color: var(--c-text-muted);
  background: var(--c-surface-alt);
  border: var(--border-width) dashed var(--c-border);
  border-radius: var(--radius-md);
}

.reg-list__col-no {
  width: 4.5rem;
}

.reg-list__cards {
  display: none;
}

.reg-card {
  padding: var(--space-4);
  border: var(--border-width) solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface);
}

.reg-card + .reg-card {
  margin-top: var(--space-3);
}

.reg-card__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  min-width: 0;
}

.reg-card__no {
  flex: 0 0 auto;
  min-width: 1.75rem;
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--c-text-muted);
}

.reg-card__name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: var(--fs-md);
  font-weight: 800;
}

.reg-card__time {
  flex: 0 0 auto;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}

.reg-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

/* 모바일: 표 → 카드 전환 */
@media (max-width: 600px) {
  .reg-list__table-wrap {
    display: none;
  }

  .reg-list__cards {
    display: block;
  }
}

/* 인쇄: 항상 표 */
@media print {
  .reg-list__table-wrap {
    display: block;
  }

  .reg-list__cards {
    display: none;
  }
}
</style>
