<script setup lang="ts">
/**
 * 서비스 종료 안내 배너. 등록·취소·조회 세 화면 맨 위에 같은 내용으로 붙는다.
 *
 * **화면마다 다른 문구를 쓰지 않는 것이 중요하다.** 종료 같은 공지는 어디서 보든 같은 날짜와
 * 같은 이유를 말해야 신뢰를 잃지 않는다. 날짜는 `config/serviceEnd.ts` 한 곳에서만 온다.
 *
 * 남은 시간은 눈에 띄게 두되 문구는 담담하게 간다 — 겁을 주려는 안내가 아니라
 * 미리 준비하시라는 안내다.
 */

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { SERVICE_END_DATE, SERVICE_STOP_DATE, isAfterServiceEnd, remainingLabel } from '../config/serviceEnd'
import { formatKoreanDate } from '../utils/format'

/**
 * 읽는 사람.
 *
 * 날짜와 이유는 누가 보든 똑같이 말하고, **"이제 어떻게 하시라"는 한 줄만** 상대에 맞춘다.
 * 사감 선생님 화면에 "사감 선생님께 말씀해 주세요"가 뜨면 안 되기 때문이다.
 */
const props = withDefaults(defineProps<{ audience?: 'student' | 'supervisor' }>(), {
  audience: 'student',
})

/**
 * 현재 시각. 30초마다 갱신한다.
 *
 * 갱신하지 않으면 페이지를 열어 둔 채 몇 시간이 지났을 때 "종료까지 5시간"이 그대로 남는다.
 * 시간 단위 표시라 초 단위로 돌 이유는 없다.
 */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (ticker !== undefined) {
    clearInterval(ticker)
    ticker = undefined
  }
})

/** 남은 시간 배지 문구. 이미 지났으면 빈 문자열이라 배지가 사라진다. */
const remaining = computed(() => remainingLabel(now.value))

/** 종료 시점이 지났는지. 문구를 과거형으로 바꾼다. */
const ended = computed(() => isAfterServiceEnd(now.value))

/** 마지막 운영일 (예: `2026년 9월 7일 (월)`) */
const endDateLabel = computed(() => formatKoreanDate(SERVICE_END_DATE))
/** 중지되는 날 (예: `2026년 9월 8일 (화)`) */
const stopDateLabel = computed(() => formatKoreanDate(SERVICE_STOP_DATE))

/** 종료 후 어떻게 하면 되는지 — 대상에 따라 이 한 줄만 달라진다. */
const afterGuide = computed(() =>
  props.audience === 'supervisor'
    ? '이후에는 명단이 문자나 메일로 가지 않아요. 번거로우시겠지만 교육생에게 직접 확인해 주시면 됩니다.'
    : '이후에는 사감 선생님께 직접 말씀해 주세요.',
)
</script>

<template>
  <section class="end-notice" aria-labelledby="service-end-title">
    <div class="end-notice__head">
      <p class="end-notice__eyebrow">안내</p>
      <h2 id="service-end-title" class="end-notice__title">서비스 종료 안내</h2>
      <span v-if="remaining" class="end-notice__badge">{{ remaining }}</span>
    </div>

    <p class="end-notice__lead">
      <template v-if="ended">
        <strong>{{ endDateLabel }}</strong>을 끝으로 야간 복귀 등록 서비스를 마쳤습니다.
      </template>
      <template v-else>
        <strong>{{ endDateLabel }}</strong>을 끝으로 야간 복귀 등록 서비스를 마칩니다.
        <strong>{{ stopDateLabel }}</strong>부터는 서비스가 종료되어 이용하실 수 없어요.
      </template>
    </p>

    <p class="end-notice__body">
      이 서비스는 제가 SKALA 과정에 있는 동안, 기숙사 쓰는 분들이 조금 덜 번거로우면 좋겠다는
      마음으로 직접 만들고 개인 비용으로 운영해 왔어요.
    </p>
    <p class="end-notice__body">
      이제 과정을 떠나게 되어 계속 챙기기가 어렵고, 서버 비용도 생각보다 커져서
      여기서 마무리하려고 합니다. 갑작스럽게 알려 드려 미안한 마음이에요.
    </p>

    <ul class="end-notice__list">
      <li v-if="!ended">{{ endDateLabel }}까지는 평소처럼 등록하고 취소하실 수 있어요.</li>
      <li>{{ afterGuide }}</li>
      <li>그동안 등록된 명단과 개인정보는 서비스 종료와 함께 모두 지워집니다.</li>
    </ul>

    <p class="end-notice__closing">
      그동안 써 주셔서 고마웠습니다. 남은 과정도 잘 마무리하시길 바라요.
    </p>
  </section>
</template>

<style scoped>
.end-notice {
  padding: var(--space-5);
  border: var(--border-width) solid var(--c-border-strong);
  /* 왼쪽 굵은 선 하나로 "공지"임을 알린다. 경고색을 쓰면 장애 안내처럼 보인다. */
  border-left: 4px solid var(--c-accent);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
}

.end-notice__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
}

.end-notice__eyebrow {
  font-size: var(--fs-xs);
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--c-text-muted);
}

.end-notice__title {
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--c-text);
}

.end-notice__badge {
  margin-left: auto;
  padding: 0.25rem 0.625rem;
  border-radius: var(--radius-pill);
  background: var(--c-accent-soft);
  color: var(--c-link);
  font-size: var(--fs-sm);
  font-weight: 700;
  white-space: nowrap;
}

.end-notice__lead {
  margin-top: var(--space-3);
  font-size: var(--fs-md);
  line-height: 1.7;
  color: var(--c-text);
}

.end-notice__body {
  margin-top: var(--space-3);
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--c-text-muted);
}

.end-notice__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-4);
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--c-text);
}

.end-notice__list li {
  position: relative;
  padding-left: 1rem;
}

.end-notice__list li::before {
  content: '·';
  position: absolute;
  left: 0.25rem;
  font-weight: 700;
}

.end-notice__closing {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: var(--border-width) solid var(--c-border);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--c-text-muted);
}

/* 좁은 화면에서 배지가 제목을 밀어내지 않게 한 줄 아래로 내린다. */
@media (max-width: 24rem) {
  .end-notice__badge {
    margin-left: 0;
  }
}
</style>
