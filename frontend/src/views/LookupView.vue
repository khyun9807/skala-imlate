<script setup lang="ts">
/**
 * 사감용 명단 조회 화면 (`/lookup?date=&token=`).
 *
 * - 검증(대사) 결과 배지 + DB/WAL 카운트
 * - 명단 표(모바일은 카드), 반별 요약, 검색/필터, 통계, 인쇄
 */

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppHeader from '../components/AppHeader.vue'
import RegistrationTable from '../components/RegistrationTable.vue'
import StatCard from '../components/StatCard.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { fetchLookup, toApiError } from '../api/client'
import type { LookupResponse } from '../api/types'
import { formatClockTime, formatKoreanDate, formatNumber, formatTimeHm, todayIsoLocal } from '../utils/format'

const route = useRoute()

const data = ref<LookupResponse | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const errorCode = ref('')
const keyword = ref('')
const selectedClass = ref('')

/** 조회 토큰 (문자·이메일로 발송된 링크에 포함) */
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token.trim() : ''))
/** 조회 날짜. 없으면 서버가 오늘로 처리한다. */
const dateParam = computed(() => (typeof route.query.date === 'string' ? route.query.date.trim() : ''))

const hasToken = computed(() => token.value.length > 0)
const isForbidden = computed(() => errorCode.value === 'FORBIDDEN' || errorCode.value === 'UNAUTHORIZED')

const dateLabel = computed(() => formatKoreanDate(data.value?.date || dateParam.value || todayIsoLocal()))
const returnTimeLabel = computed(() => formatTimeHm(data.value?.returnTime) || '23:30')
const curfewTimeLabel = computed(() => formatTimeHm(data.value?.curfewTime) || '22:30')
const generatedAtLabel = computed(() => formatClockTime(data.value?.generatedAt))

const verification = computed(() => data.value?.verification ?? null)
const stats = computed(() => data.value?.stats ?? null)
const byClass = computed(() => data.value?.byClass ?? [])
const byRoom = computed(() => data.value?.byRoom ?? [])

const filteredItems = computed(() => {
  const items = data.value?.items ?? []
  const query = keyword.value.trim().toLowerCase()
  const classFilter = selectedClass.value
  return items.filter((item) => {
    if (classFilter && item.className !== classFilter) {
      return false
    }
    if (!query) {
      return true
    }
    return `${item.className} ${item.studentName} ${item.roomNumber}`.toLowerCase().includes(query)
  })
})

const isFiltered = computed(() => keyword.value.trim().length > 0 || selectedClass.value.length > 0)

onMounted(() => {
  void load()
})

watch([token, dateParam], () => {
  void load()
})

async function load(): Promise<void> {
  if (!hasToken.value) {
    data.value = null
    errorMessage.value = ''
    errorCode.value = ''
    return
  }
  loading.value = true
  try {
    data.value = await fetchLookup(dateParam.value || null, token.value)
    errorMessage.value = ''
    errorCode.value = ''
  } catch (error) {
    const apiError = toApiError(error)
    data.value = null
    errorMessage.value = apiError.message
    errorCode.value = apiError.code
  } finally {
    loading.value = false
  }
}

function toggleClass(className: string): void {
  selectedClass.value = selectedClass.value === className ? '' : className
}

function resetFilter(): void {
  keyword.value = ''
  selectedClass.value = ''
}

function printPage(): void {
  window.print()
}
</script>

<template>
  <main id="main" class="app-main">
    <div class="container container--wide stack">
      <AppHeader title="야간 복귀 명단" :subtitle="dateLabel">
        <template #badges>
          <span v-if="data" class="badge badge--info">총 {{ formatNumber(data.totalCount) }}명</span>
          <StatusBadge v-if="verification" :status="verification.status" />
          <span v-if="verification" class="badge badge--neutral tabular">
            DB {{ formatNumber(verification.dbCount) }} · WAL {{ formatNumber(verification.walCount) }}
          </span>
          <span v-if="data" class="badge badge--neutral">{{ returnTimeLabel }} 일괄 복귀</span>
        </template>
        <template #actions>
          <button v-if="data" type="button" class="btn btn--secondary" @click="load">새로고침</button>
          <button v-if="data" type="button" class="btn btn--primary" @click="printPage">인쇄</button>
        </template>
      </AppHeader>

      <!-- 토큰 없음 -->
      <section v-if="!hasToken" class="card">
        <h2 class="card__title">조회 링크가 필요합니다</h2>
        <p class="text-muted">
          이 페이지는 사감 선생님께 발송된 문자·이메일 안의 링크로만 열 수 있습니다. 링크에 포함된 조회 토큰이
          없어 명단을 표시할 수 없습니다.
        </p>
        <p class="text-sm text-muted spaced">
          링크를 복사해 붙여넣었다면 주소가 중간에 잘리지 않았는지 확인해 주세요.
        </p>
      </section>

      <!-- 권한 없음 / 만료 -->
      <section v-else-if="isForbidden" class="card">
        <h2 class="card__title">명단을 볼 수 없습니다</h2>
        <p class="text-muted">{{ errorMessage }}</p>
        <p class="text-sm text-muted spaced">
          조회 링크는 일정 시간이 지나면 만료됩니다. 가장 최근에 받은 문자·이메일의 링크로 다시 접속해 주세요.
        </p>
      </section>

      <!-- 그 밖의 오류 -->
      <section v-else-if="errorMessage" class="alert alert--danger">
        <span class="alert__title">명단을 불러오지 못했습니다</span>
        <span>{{ errorMessage }}</span>
        <span>
          <button type="button" class="btn btn--secondary" @click="load">다시 시도</button>
        </span>
      </section>

      <!-- 로딩 -->
      <section v-else-if="loading && !data" class="card" aria-busy="true">
        <p class="text-muted">명단을 불러오는 중입니다…</p>
      </section>

      <template v-else-if="data">
        <!-- 검증 결과 -->
        <section v-if="verification" class="card">
          <div class="row-between">
            <h2 class="card__title">검증 결과</h2>
            <span class="text-sm text-muted">확인 시각 {{ formatClockTime(verification.checkedAt) }}</span>
          </div>
          <div class="verify-grid">
            <StatCard label="DB 기록" :value="verification.dbCount" unit="건" />
            <StatCard label="WAL(Redis) 기록" :value="verification.walCount" unit="건" />
            <StatCard label="복구 처리" :value="verification.recoveredCount" unit="건" />
          </div>
          <details v-if="verification.walOnly.length > 0 || verification.dbOnly.length > 0" class="detail-block">
            <summary>차이 항목 자세히 보기</summary>
            <div class="detail-block__body">
              <div v-if="verification.walOnly.length > 0">
                <h3>WAL 에만 있던 항목 ({{ verification.walOnly.length }}건)</h3>
                <ul class="plain-list">
                  <li v-for="entry in verification.walOnly" :key="`wal-${entry}`">{{ entry }}</li>
                </ul>
              </div>
              <div v-if="verification.dbOnly.length > 0">
                <h3>DB 에만 있는 항목 ({{ verification.dbOnly.length }}건)</h3>
                <ul class="plain-list">
                  <li v-for="entry in verification.dbOnly" :key="`db-${entry}`">{{ entry }}</li>
                </ul>
              </div>
            </div>
          </details>
        </section>

        <!-- 반별 요약 -->
        <section v-if="byClass.length > 0" class="card">
          <h2 class="card__title">반별 인원</h2>
          <div class="chip-list">
            <button
              v-for="entry in byClass"
              :key="entry.className"
              type="button"
              class="chip chip--button"
              :class="{ 'chip--active': selectedClass === entry.className }"
              :aria-pressed="selectedClass === entry.className"
              @click="toggleClass(entry.className)"
            >
              {{ entry.className }}
              <span class="chip__count tabular">{{ entry.count }}명</span>
            </button>
          </div>
          <details v-if="byRoom.length > 0" class="detail-block">
            <summary>호수별 인원 보기</summary>
            <div class="chip-list detail-block__body">
              <span v-for="entry in byRoom" :key="entry.roomNumber" class="chip">
                {{ entry.roomNumber }}
                <span class="chip__count tabular">{{ entry.count }}명</span>
              </span>
            </div>
          </details>
        </section>

        <!-- 명단 -->
        <section class="card">
          <div class="row-between">
            <h2 class="card__title">복귀 명단</h2>
            <span class="text-sm text-muted">
              {{ formatNumber(filteredItems.length) }}명 표시
              <template v-if="isFiltered">/ 전체 {{ formatNumber(data.totalCount) }}명</template>
            </span>
          </div>

          <div class="search-row no-print">
            <label class="sr-only" for="lookup-search">이름, 반, 호수로 검색</label>
            <input
              id="lookup-search"
              v-model="keyword"
              class="search-input"
              type="search"
              inputmode="search"
              enterkeyhint="search"
              placeholder="이름, 반, 호수로 검색"
              autocomplete="off"
            />
            <button v-if="isFiltered" type="button" class="btn btn--secondary" @click="resetFilter">
              필터 해제
            </button>
          </div>

          <RegistrationTable
            :items="filteredItems"
            :empty-message="isFiltered ? '검색 조건에 맞는 인원이 없습니다.' : '오늘 등록된 인원이 없습니다.'"
          />
        </section>

        <!-- 통계 -->
        <section v-if="stats" class="card">
          <h2 class="card__title">통계</h2>
          <div class="stats-grid">
            <StatCard label="오늘 방문자" :value="stats.todayVisitors" unit="명" />
            <StatCard label="총 방문자" :value="stats.totalVisitors" unit="명" />
            <StatCard label="오늘 등록" :value="stats.todayRegistrations" unit="건" />
            <StatCard label="총 등록" :value="stats.totalRegistrations" unit="건" />
          </div>
        </section>

        <!-- 안내 -->
        <section class="card card--flat">
          <h2 class="card__title">안내</h2>
          <ul class="notice-list">
            <li>{{ curfewTimeLabel }} 이후 기숙사 문이 잠기며, {{ returnTimeLabel }}에 일괄 개방됩니다.</li>
            <li v-if="generatedAtLabel">이 명단은 {{ generatedAtLabel }} 기준으로 생성되었습니다.</li>
            <li>명단에는 개인정보가 포함되어 있으니 외부 공유에 주의해 주세요.</li>
          </ul>
        </section>
      </template>
    </div>
  </main>
</template>

<style scoped>
.spaced {
  margin-top: var(--space-3);
}

.verify-grid,
.stats-grid {
  display: grid;
  gap: var(--space-3);
  margin-top: var(--space-4);
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr));
}

.detail-block {
  margin-top: var(--space-4);
  border-top: var(--border-width) solid var(--c-border);
  padding-top: var(--space-3);
}

.detail-block summary {
  display: inline-flex;
  align-items: center;
  min-height: var(--tap-min);
  padding: var(--space-1) 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-link);
  cursor: pointer;
}

.detail-block__body {
  display: grid;
  gap: var(--space-4);
  margin-top: var(--space-2);
}

.detail-block__body h3 {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  margin-bottom: var(--space-2);
}

.plain-list {
  display: grid;
  gap: var(--space-1);
  font-size: var(--fs-sm);
}

.chip--button {
  min-height: var(--tap-min);
  cursor: pointer;
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}

.chip--button:hover {
  border-color: var(--c-border-strong);
}

.chip--active {
  background: var(--c-accent-soft);
  border-color: var(--c-accent);
  color: var(--c-link);
  font-weight: 700;
}

.chip--active .chip__count {
  color: var(--c-link);
}

.search-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: var(--space-4) 0;
}

.search-input {
  flex: 1 1 220px;
  min-width: 0;
  min-height: var(--tap-min);
  padding: 0.625rem 0.875rem;
  border: var(--border-width) solid var(--c-border-strong);
  border-radius: var(--radius-md);
  background: var(--c-surface);
  color: var(--c-text);
}

.search-input:focus {
  outline: none;
  border-color: var(--c-focus);
  box-shadow: 0 0 0 3px var(--c-focus-ring);
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}

.notice-list li {
  position: relative;
  padding-left: 1rem;
}

.notice-list li::before {
  content: '·';
  position: absolute;
  left: 0.25rem;
  font-weight: 700;
}

.chip-list {
  margin-top: var(--space-3);
}

@media print {
  .detail-block {
    display: none;
  }
}
</style>
