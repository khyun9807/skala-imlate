<script setup lang="ts">
/**
 * 야간 복귀 등록 화면 (`/`).
 *
 * - 서버 시간 기준 마감 카운트다운 (클라이언트 시계 오차 보정)
 * - 반 / 이름 / 기숙사 호수 입력 + 서버와 동일한 규칙의 클라이언트 검증
 * - 이전 입력값 자동 채움(R6), 최근 3건 datalist 제안, 저장 정보 삭제 버튼
 */

import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import AppHeader from '../components/AppHeader.vue'
import CountdownBadge from '../components/CountdownBadge.vue'
import FormField from '../components/FormField.vue'
import ResultCard from '../components/ResultCard.vue'
import { ApiError, createRegistration, fetchRegistrationSummary } from '../api/client'
import type { RegistrationResponse, RegistrationSummary } from '../api/types'
import { useLastInput } from '../composables/useLastInput'
import { useServerClock } from '../composables/useServerClock'
import { formatKoreanDate, formatTimeHm, todayIsoLocal } from '../utils/format'

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
const summary = ref<RegistrationSummary | null>(null)

const classFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const nameFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const roomFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const resultRef = ref<InstanceType<typeof ResultCard> | null>(null)

/** 마감 시각 라벨 (`22:00`) */
const closeTimeLabel = computed(() => {
  const closesAt = windowInfo.value?.closesAt
  const matched = closesAt ? /T(\d{2}):(\d{2})/.exec(closesAt) : null
  return matched ? `${matched[1]}:${matched[2]}` : '22:00'
})

/** 복귀 시각 라벨 (`23:30`) */
const returnTimeLabel = computed(() => formatTimeHm(windowInfo.value?.returnTime) || '23:30')

/** 통금 시각 라벨 (`22:30`) */
const curfewTimeLabel = computed(() => formatTimeHm(windowInfo.value?.curfewTime) || '22:30')

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
  return `${returnTimeLabel.value} 복귀 등록하기`
})

onMounted(() => {
  const last = lastInput.value
  if (last) {
    form.className = last.className
    form.studentName = last.studentName
    form.roomNumber = last.roomNumber
    statusMessage.value = '이전에 입력한 정보를 불러왔습니다.'
  }
  void loadSummary()
})

// 마감 상태로 바뀌면 안내 문구를 갱신한다.
watch(isClosed, (closed) => {
  if (closed) {
    statusMessage.value = `오늘 등록은 마감되었습니다. (마감 ${closeTimeLabel.value})`
  }
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

async function loadSummary(): Promise<void> {
  try {
    summary.value = await fetchRegistrationSummary()
  } catch {
    // 요약은 부가 정보이므로 실패해도 화면 동작에 영향을 주지 않는다.
  }
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
    void loadSummary()
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
          <span class="badge badge--info">{{ returnTimeLabel }} 일괄 복귀</span>
          <span class="badge badge--neutral">{{ curfewTimeLabel }} 문 잠김</span>
        </template>
      </AppHeader>

      <CountdownBadge :state="windowState" :seconds="secondsUntilClose" :close-time="closeTimeLabel" />

      <!-- 화면 낭독기용 상태 안내 -->
      <p class="sr-only" role="status" aria-live="polite">{{ statusMessage }}</p>

      <section v-if="isClosed" class="alert alert--warn no-print">
        <span class="alert__title">오늘 등록은 마감되었습니다 ({{ closeTimeLabel }})</span>
        <span>
          마감 이후에는 등록할 수 없습니다. 부득이한 사정이 있다면 사감 선생님께 직접 문의해 주세요.
        </span>
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

      <ResultCard v-if="result" ref="resultRef" :result="result" />

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
        <p v-if="summary" class="summary">
          오늘 등록 인원
          <strong class="summary__count tabular">{{ summary.count }}명</strong>
        </p>
        <ul class="notice-list">
          <li>{{ closeTimeLabel }}까지 등록하면 잠시 뒤 사감 선생님께 명단이 전달됩니다.</li>
          <li>{{ curfewTimeLabel }} 이후 기숙사 문이 잠기며, {{ returnTimeLabel }}에 일괄 개방됩니다.</li>
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

.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  font-size: var(--fs-base);
  color: var(--c-text-muted);
}

.summary__count {
  font-size: var(--fs-lg);
  font-weight: 800;
  color: var(--c-text);
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
</style>
