<script setup lang="ts">
/**
 * 야간 복귀 등록 화면 (`/`).
 *
 * - 서버 시간 기준 마감 카운트다운 (클라이언트 시계 오차 보정)
 * - 반 / 이름 / 기숙사 호수 입력 + 서버와 동일한 규칙의 클라이언트 검증
 * - 이전 입력값 자동 채움(R6), 최근 3건 datalist 제안, 저장 정보 삭제 버튼
 *
 * **화면에 시각을 하드코딩하지 않는다.** 마감·복귀·통금·등록 시작 시각은 전부
 * `/registrations/window` 응답(`closesAt` / `returnTime` / `curfewTime` / `opensAt`)에서 뽑아 쓰고,
 * 값을 못 받았으면 그 문구를 아예 보여 주지 않는다(임의의 시각을 단정하지 않는다).
 */

import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import AppHeader from '../components/AppHeader.vue'
import CountdownBadge from '../components/CountdownBadge.vue'
import FormField from '../components/FormField.vue'
import ResultCard from '../components/ResultCard.vue'
import { ApiError, createRegistration } from '../api/client'
import type { RegistrationResponse } from '../api/types'
import { useLastInput } from '../composables/useLastInput'
import { useServerClock } from '../composables/useServerClock'
import {
  addDaysIso,
  formatClockTime,
  formatKoreanDate,
  formatTimeHm,
  relativeDayLabel,
  toDatePart,
  todayIsoLocal,
} from '../utils/format'

/** 서버 검증 규칙과 동일한 허용 문자 (SPEC §5.5) */
const ALLOWED_PATTERN = /^[가-힣A-Za-z0-9 ()\-]{1,20}$/
const MAX_LENGTH = 20

type FieldKey = 'className' | 'studentName' | 'roomNumber'

const REQUIRED_MESSAGES: Record<FieldKey, string> = {
  className: '반을 입력해 주세요.',
  studentName: '이름을 입력해 주세요.',
  roomNumber: '기숙사 호수를 입력해 주세요.',
}

const TOO_LONG_MESSAGES: Record<FieldKey, string> = {
  className: `반은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  studentName: `이름은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  roomNumber: `기숙사 호수는 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
}

const PATTERN_MESSAGE = '한글·영문·숫자와 공백, 괄호( ), 하이픈(-)만 사용할 수 있습니다.'

const FIELD_KEYS: FieldKey[] = ['className', 'studentName', 'roomNumber']

const {
  windowInfo,
  state: windowState,
  errorMessage: windowError,
  secondsUntilClose,
  serverNowMs,
  targetDate,
  refresh: refreshWindow,
} = useServerClock()

const {
  lastInput,
  hasSaved,
  classSuggestions,
  nameSuggestions,
  roomSuggestions,
  remember,
  clearSaved,
} = useLastInput()

const form = reactive<Record<FieldKey, string>>({ className: '', studentName: '', roomNumber: '' })
const errors = reactive<Record<FieldKey, string>>({ className: '', studentName: '', roomNumber: '' })
const touched = reactive<Record<FieldKey, boolean>>({ className: false, studentName: false, roomNumber: false })

const submitting = ref(false)
const formError = ref('')
const statusMessage = ref('')
const result = ref<RegistrationResponse | null>(null)

const classFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const nameFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const roomFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const resultRef = ref<InstanceType<typeof ResultCard> | null>(null)

/** 마감 시각 라벨 (`21:45`). 서버 `closesAt` 에서 유도한다. */
const closeTimeLabel = computed(() => formatClockTime(windowInfo.value?.closesAt))

/** 복귀 시각 라벨 (`23:30`) */
const returnTimeLabel = computed(() => formatTimeHm(windowInfo.value?.returnTime))

/** 통금 시각 라벨 (`22:30`) */
const curfewTimeLabel = computed(() => formatTimeHm(windowInfo.value?.curfewTime))

/**
 * 다음 등록이 열리는 시각 라벨 (`내일 00:00`).
 *
 * 서버가 주는 `opensAt` 은 **오늘분** 등록 시작 시각이라 마감 뒤에는 이미 지나간 값이다.
 * 그대로 보여 주면 "오늘 00:00부터"가 되어 정반대로 읽히므로,
 * 서버 기준 현재 시각과 비교해 이미 지났으면 하루를 더해 "내일"로 표기한다.
 */
const nextOpenLabel = computed(() => {
  const info = windowInfo.value
  if (!info) {
    return ''
  }
  const openTime = formatClockTime(info.opensAt)
  const openDate = toDatePart(info.opensAt)
  if (!openTime || !openDate) {
    return ''
  }
  const opensAtMs = Date.parse(info.opensAt)
  const alreadyPassed = !Number.isFinite(opensAtMs) || opensAtMs <= serverNowMs.value
  const targetDay = alreadyPassed ? addDaysIso(openDate, 1) : openDate
  const dayLabel = relativeDayLabel(info.date || openDate, targetDay)
  return dayLabel ? `${dayLabel} ${openTime}` : openTime
})

/** 헤더에 표시할 날짜 문구 */
const dateLabel = computed(() => formatKoreanDate(targetDate.value || todayIsoLocal()))

/** 마감되어 입력이 잠긴 상태인지 (시간 정보를 못 받았다면 잠그지 않는다) */
const isClosed = computed(() => windowState.value === 'closed')

/** 제출 가능 여부 */
const canSubmit = computed(() => !submitting.value && !isClosed.value)

/** 제출 버튼 문구 */
const submitLabel = computed(() => {
  if (submitting.value) {
    return '등록 중…'
  }
  if (isClosed.value) {
    return '등록 마감'
  }
  return returnTimeLabel.value ? `${returnTimeLabel.value} 복귀 등록하기` : '복귀 등록하기'
})

onMounted(() => {
  const last = lastInput.value
  if (last) {
    form.className = last.className
    form.studentName = last.studentName
    form.roomNumber = last.roomNumber
    statusMessage.value = '이전에 입력한 정보를 불러왔습니다.'
  }
})

// 마감 상태로 바뀌면 화면 낭독기용 안내 문구를 갱신한다.
watch(isClosed, (closed) => {
  if (!closed) {
    return
  }
  const closedAt = closeTimeLabel.value ? ` (마감 ${closeTimeLabel.value})` : ''
  const nextOpen = nextOpenLabel.value ? ` ${nextOpenLabel.value}부터 다시 등록할 수 있습니다.` : ''
  statusMessage.value = `오늘 밤 복귀 등록은 마감되었습니다.${closedAt}${nextOpen}`
})

function normalize(value: string): string {
  return value.trim().replace(/\s+/g, ' ')
}

function validateField(key: FieldKey): string {
  const value = normalize(form[key])
  if (value.length === 0) {
    return REQUIRED_MESSAGES[key]
  }
  if (value.length > MAX_LENGTH) {
    return TOO_LONG_MESSAGES[key]
  }
  if (!ALLOWED_PATTERN.test(value)) {
    return PATTERN_MESSAGE
  }
  return ''
}

function onFieldBlur(key: FieldKey): void {
  touched[key] = true
  errors[key] = validateField(key)
}

function onFieldInput(key: FieldKey, value: string): void {
  form[key] = value
  if (touched[key]) {
    errors[key] = validateField(key)
  }
  formError.value = ''
}

function focusField(key: FieldKey): void {
  if (key === 'className') {
    classFieldRef.value?.focus()
    return
  }
  if (key === 'studentName') {
    nameFieldRef.value?.focus()
    return
  }
  roomFieldRef.value?.focus()
}

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) {
    return
  }

  let firstInvalid: FieldKey | null = null
  for (const key of FIELD_KEYS) {
    touched[key] = true
    errors[key] = validateField(key)
    if (errors[key] && firstInvalid === null) {
      firstInvalid = key
    }
  }

  if (firstInvalid !== null) {
    formError.value = '입력한 내용을 다시 확인해 주세요.'
    statusMessage.value = formError.value
    focusField(firstInvalid)
    return
  }

  const payload = {
    className: normalize(form.className),
    studentName: normalize(form.studentName),
    roomNumber: normalize(form.roomNumber),
  }

  submitting.value = true
  formError.value = ''
  let focusTarget: FieldKey | null = null
  try {
    const response = await createRegistration(payload)
    result.value = response
    remember(payload)
    statusMessage.value = response.duplicate
      ? '이미 등록되어 있습니다.'
      : `등록이 완료되었습니다. ${returnTimeLabel.value}에 복귀해 주세요.`
    await nextTick()
    focusResult()
  } catch (error) {
    focusTarget = handleSubmitError(error)
  } finally {
    submitting.value = false
  }

  // 입력칸이 disabled 상태에서 풀린 뒤에 포커스를 옮겨야 실제로 이동한다.
  if (focusTarget !== null) {
    await nextTick()
    focusField(focusTarget)
  }
}

function focusResult(): void {
  const element = resultRef.value?.$el as HTMLElement | undefined
  if (element && typeof element.focus === 'function') {
    element.focus()
  }
}

/** 등록 실패를 화면에 반영하고, 포커스를 옮길 입력칸 키를 돌려준다. */
function handleSubmitError(error: unknown): FieldKey | null {
  if (!(error instanceof ApiError)) {
    formError.value = '알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
    statusMessage.value = formError.value
    return null
  }

  // 서버가 알려준 필드 오류를 각 입력칸에 반영한다.
  let firstInvalid: FieldKey | null = null
  for (const key of FIELD_KEYS) {
    const message = error.fieldErrors[key]
    if (message) {
      touched[key] = true
      errors[key] = message
      if (firstInvalid === null) {
        firstInvalid = key
      }
    }
  }

  formError.value =
    error.code === 'RATE_LIMITED' && error.retryAfterSeconds !== null
      ? `${error.message} (약 ${error.retryAfterSeconds}초 후 다시 시도해 주세요.)`
      : error.message
  statusMessage.value = formError.value

  if (error.code === 'REGISTRATION_CLOSED' || error.code === 'REGISTRATION_NOT_OPEN') {
    void refreshWindow()
  }
  return firstInvalid
}

/** 다른 인원을 이어서 등록할 수 있도록 이름/호수를 비운다. */
function registerAnother(): void {
  result.value = null
  form.studentName = ''
  form.roomNumber = ''
  errors.studentName = ''
  errors.roomNumber = ''
  touched.studentName = false
  touched.roomNumber = false
  statusMessage.value = '새로 입력할 수 있습니다.'
  void nextTick(() => focusField('studentName'))
}

/** 저장된 이전 입력값을 모두 삭제한다. */
function clearSavedInput(): void {
  clearSaved()
  for (const key of FIELD_KEYS) {
    form[key] = ''
    errors[key] = ''
    touched[key] = false
  }
  statusMessage.value = '저장된 입력 정보를 삭제했습니다.'
}
</script>

<template>
  <main id="main" class="app-main">
    <div class="container stack">
      <AppHeader title="야간 복귀 등록" :subtitle="dateLabel">
        <template #badges>
          <span v-if="returnTimeLabel" class="badge badge--info">{{ returnTimeLabel }} 일괄 복귀</span>
          <span v-if="curfewTimeLabel" class="badge badge--neutral">{{ curfewTimeLabel }} 문 잠김</span>
        </template>
      </AppHeader>

      <CountdownBadge
        :state="windowState"
        :seconds="secondsUntilClose"
        :close-time="closeTimeLabel"
        :next-open-label="nextOpenLabel"
      />

      <!-- 화면 낭독기용 상태 안내 -->
      <p class="sr-only" role="status" aria-live="polite">{{ statusMessage }}</p>

      <!--
        등록 가능할 때의 시간 안내.
        "명단이 언제 전달되는지"는 window 응답에 발송 시각 필드가 없으므로
        시각을 단정하지 않고 "마감 직후"로만 표현한다. (없는 값을 지어내지 않는다)
      -->
      <section v-if="windowState === 'open'" class="card card--flat no-print" aria-labelledby="window-guide-title">
        <h2 id="window-guide-title" class="card__title">등록 시간 안내</h2>
        <ul class="notice-list">
          <li>지금 등록하면 마감 직후 사감 선생님께 명단이 전달됩니다.</li>
          <li v-if="closeTimeLabel && nextOpenLabel">
            {{ closeTimeLabel }} 이후에는 오늘 등록이 닫히고, {{ nextOpenLabel }}에 다음 날 밤 복귀 등록이
            열립니다.
          </li>
        </ul>
      </section>

      <!-- 마감 사실 자체는 바로 위 카운트다운이 이미 크게 말한다. 여기서는 "그래서 이제 어떻게 되는지"만 다룬다. -->
      <section v-if="isClosed" class="alert alert--warn no-print">
        <span class="alert__title">마감 이후 안내</span>
        <ul class="closed-list">
          <li v-if="nextOpenLabel" class="closed-list__lead">
            {{ nextOpenLabel }}부터 다음 날 밤 복귀 등록을 받습니다.
          </li>
          <li v-if="curfewTimeLabel">
            {{ curfewTimeLabel }} 이후에는 기숙사 문이 잠기니 그 전에 복귀해 주세요.
          </li>
          <li>오늘 등록하지 못했다면 사감 선생님께 직접 문의해 주세요.</li>
        </ul>
        <span>
          <button type="button" class="btn btn--secondary" @click="refreshWindow()">등록 시간 다시 확인</button>
        </span>
      </section>

      <section v-else-if="windowState === 'error'" class="alert alert--danger no-print">
        <span class="alert__title">등록 시간 정보를 불러오지 못했습니다</span>
        <span>{{ windowError ?? '잠시 후 다시 시도해 주세요.' }}</span>
        <span>
          <button type="button" class="btn btn--secondary" @click="refreshWindow()">다시 시도</button>
        </span>
      </section>

      <ResultCard v-if="result" ref="resultRef" :result="result" :curfew-time="curfewTimeLabel" />

      <div v-if="result" class="no-print">
        <button type="button" class="btn btn--secondary btn--block" @click="registerAnother">
          다른 인원 이어서 등록하기
        </button>
      </div>

      <section class="card">
        <h2 class="card__title">복귀 정보 입력</h2>
        <p class="text-sm text-muted">반, 이름, 기숙사 호수를 입력하고 등록 버튼을 눌러 주세요.</p>

        <form class="form" novalidate @submit.prevent="onSubmit">
          <FormField
            id="className"
            ref="classFieldRef"
            label="반"
            :model-value="form.className"
            :error="errors.className"
            :suggestions="classSuggestions"
            :disabled="isClosed || submitting"
            placeholder="예: 1반"
            hint="교육과정에서 사용하는 반 이름을 적어 주세요."
            autocomplete="off"
            inputmode="text"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('className', value)"
            @blur="onFieldBlur('className')"
          />

          <FormField
            id="studentName"
            ref="nameFieldRef"
            label="이름"
            :model-value="form.studentName"
            :error="errors.studentName"
            :suggestions="nameSuggestions"
            :disabled="isClosed || submitting"
            placeholder="예: 홍길동"
            autocomplete="name"
            inputmode="text"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('studentName', value)"
            @blur="onFieldBlur('studentName')"
          />

          <FormField
            id="roomNumber"
            ref="roomFieldRef"
            label="기숙사 호수"
            :model-value="form.roomNumber"
            :error="errors.roomNumber"
            :suggestions="roomSuggestions"
            :disabled="isClosed || submitting"
            placeholder="예: 302"
            hint="숫자만 또는 동·호수 형태 모두 입력할 수 있습니다."
            autocomplete="off"
            inputmode="text"
            enterkeyhint="done"
            @update:model-value="(value: string) => onFieldInput('roomNumber', value)"
            @blur="onFieldBlur('roomNumber')"
          />

          <p class="form__error" aria-live="polite">
            <span v-if="formError" class="alert alert--danger" role="alert">{{ formError }}</span>
          </p>

          <button type="submit" class="btn btn--primary btn--lg btn--block" :disabled="!canSubmit">
            {{ submitLabel }}
          </button>

          <p v-if="hasSaved" class="form__foot no-print">
            <button type="button" class="btn btn--ghost" @click="clearSavedInput">저장된 정보 지우기</button>
          </p>
        </form>
      </section>

      <section class="card card--flat" aria-labelledby="notice-title">
        <h2 id="notice-title" class="card__title">안내</h2>
        <ul class="notice-list">
          <li v-if="curfewTimeLabel && returnTimeLabel">
            {{ curfewTimeLabel }} 이후 기숙사 문이 잠기며, {{ returnTimeLabel }}에 일괄 개방됩니다.
          </li>
          <li>등록 정보는 사감 선생님 확인 목적으로만 사용됩니다.</li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-5);
}

.form__error {
  margin: 0;
}

.form__foot {
  display: flex;
  justify-content: center;
  margin: 0;
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

/* 마감 안내 목록. 색은 .alert--warn 에서 상속받아 톤을 유지한다. */
.closed-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  font-size: var(--fs-sm);
}

.closed-list li {
  position: relative;
  padding-left: 1rem;
}

.closed-list li::before {
  content: '·';
  position: absolute;
  left: 0.25rem;
  font-weight: 700;
}

/* 가장 궁금해할 "언제 다시 열리는지"를 한 단계 강조한다. */
.closed-list__lead {
  font-size: var(--fs-base);
  font-weight: 700;
}
</style>
