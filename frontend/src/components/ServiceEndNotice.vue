<script setup lang="ts">
/**
 * 서비스 종료 안내 배너. 등록·취소·조회 세 화면 맨 위에 같은 내용으로 붙는다.
 *
 * **화면마다 다른 문구를 쓰지 않는 것이 중요하다.** 종료 같은 공지는 어디서 보든 같은 날짜와
 * 같은 이유를 말해야 신뢰를 잃지 않는다. 날짜는 `config/serviceEnd.ts` 한 곳에서만 온다.
 *
 * 남은 일수는 눈에 띄게 두되, 문구 자체는 담담하게 간다 — 겁을 주려는 안내가 아니라
 * 미리 준비하시라는 안내다.
 */

import { computed } from 'vue'

import { SERVICE_END_DATE, SERVICE_STOP_DATE, daysUntilServiceEnd } from '../config/serviceEnd'
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

const remainingDays = computed(() => daysUntilServiceEnd())

/** 종료 후 어떻게 하면 되는지 — 대상에 따라 한 줄만 달라진다. */
const afterGuide = computed(() =>
  props.audience === 'supervisor'
    ? '이후에는 명단이 문자·메일로 전달되지 않습니다. 종전처럼 교육생에게 직접 확인해 주시기를 부탁드립니다.'
    : '종전처럼 사감 선생님께 직접 말씀해 주세요.',
)

/** 마지막 운영일 (예: `2026년 9월 13일 (일)`) */
const endDateLabel = computed(() => formatKoreanDate(SERVICE_END_DATE))
/** 중지되는 날 (예: `2026년 9월 14일 (월)`) */
const stopDateLabel = computed(() => formatKoreanDate(SERVICE_STOP_DATE))

/**
 * 남은 기간 배지 문구.
 *
 * "종료까지 1일"보다 "내일이 마지막 날입니다"가 실수 없이 읽힌다.
 * 이미 지난 경우(음수)는 배지를 감춘다 — 그때는 아래 본문이 과거형으로 말한다.
 */
const remainingLabel = computed(() => {
  const days = remainingDays.value
  if (days > 1) {
    return `종료까지 ${days}일`
  }
  if (days === 1) {
    return '내일이 마지막 날입니다'
  }
  if (days === 0) {
    return '오늘이 마지막 날입니다'
  }
  return ''
})

/** 종료일이 지났는지. 문구를 과거형으로 바꾼다. */
const ended = computed(() => remainingDays.value < 0)
</script>

<template>
  <section class="end-notice" aria-labelledby="service-end-title">
    <div class="end-notice__head">
      <p class="end-notice__eyebrow">안내</p>
      <h2 id="service-end-title" class="end-notice__title">서비스 종료 안내</h2>
      <span v-if="remainingLabel" class="end-notice__badge">{{ remainingLabel }}</span>
    </div>

    <p class="end-notice__lead">
      <template v-if="ended">
        <strong>{{ endDateLabel }}</strong>을 마지막으로 야간 복귀 등록 서비스를 마쳤습니다.
      </template>
      <template v-else>
        <strong>{{ endDateLabel }}</strong>을 마지막으로 야간 복귀 등록 서비스를 마칩니다.
        <strong>{{ stopDateLabel }}</strong>부터는 이 페이지를 이용하실 수 없습니다.
      </template>
    </p>

    <p class="end-notice__body">
      이 서비스는 제가 SKALA 교육과정에 함께 있는 동안, 기숙사를 이용하는 동료들의 번거로움을
      조금이나마 덜어 보고자 개인적으로 만들고 개인 비용으로 운영해 온 것입니다.
    </p>
    <p class="end-notice__body">
      이제 과정을 떠나게 되어 곁에서 살펴 드리기 어려워졌고, 운영에 드는 비용 또한 개인이
      계속 감당하기에는 가볍지 않아 부득이 이쯤에서 마무리하려 합니다.
      갑작스러운 소식이라 송구합니다.
    </p>

    <ul class="end-notice__list">
      <li v-if="!ended">{{ endDateLabel }}까지는 평소와 다름없이 등록하고 취소하실 수 있습니다.</li>
      <li>{{ stopDateLabel }}부터는 {{ afterGuide }}</li>
      <li>그동안 등록된 명단과 개인정보는 서비스 종료와 함께 모두 파기됩니다.</li>
    </ul>

    <p class="end-notice__closing">
      그동안 이용해 주셔서 감사했습니다. 남은 과정도 무탈하시기를 바랍니다.
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
  border-radius: var(--radius-pill, 999px);
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
