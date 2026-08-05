<script setup lang="ts">
/**
 * 야간 복귀 등록 취소 화면 (`/cancel`).
 *
 * 반 / 이름 / 기숙사 호수 + 등록할 때 정한 비밀번호 4자리를 모두 맞춰야 취소된다.
 *
 * **이 화면의 설계 기준은 "잘못 취소되는 것이 취소가 안 되는 것보다 훨씬 나쁘다"이다.**
 * 명단에서 빠진 교육생은 22:30 에 문이 잠기면 밖에서 밤을 새게 된다. 그래서
 * - 비밀번호를 기억나게 도와주는 어떤 힌트도 두지 않는다(남이 맞히는 데도 똑같이 도움이 된다),
 * - 취소 버튼은 한 번 더 확인을 거치며,
 * - 실패 사유를 "그런 등록 없음 / 비밀번호 틀림"으로 나눠 알려주지 않는다(서버도 나누지 않는다).
 *
 * 등록 화면과 마찬가지로 시각은 하드코딩하지 않고 `/registrations/window` 응답에서만 가져온다.
 */

import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppHeader from '../components/AppHeader.vue'
import FormField from '../components/FormField.vue'
import { ApiError, cancelRegistration } from '../api/client'
import type { CancelResponse } from '../api/types'
import { useLastInput } from '../composables/useLastInput'
import { useServerClock } from '../composables/useServerClock'
import { formatClockTime, formatKoreanDate, formatTimeHm, todayIsoLocal } from '../utils/format'

/**
 * 서버 검증 규칙과 동일한 허용 문자 (SPEC §5.5).
 * 반·기숙사 호수는 숫자만, 이름은 글자만(한글·영문).
 */
const DIGITS_PATTERN = /^[0-9]{1,20}$/
const NAME_PATTERN = /^[가-힣A-Za-z]+( [가-힣A-Za-z]+)*$/
const MAX_LENGTH = 20

/** 비밀번호 자릿수. 서버 `imlate.registration.cancel.password-length` 와 같아야 한다. */
const PASSWORD_LENGTH = 4
const PASSWORD_PATTERN = /^[0-9]{4}$/

type FieldKey = 'className' | 'studentName' | 'roomNumber' | 'password'

const REQUIRED_MESSAGES: Record<FieldKey, string> = {
  className: '반을 입력해 주세요.',
  studentName: '이름을 입력해 주세요.',
  roomNumber: '기숙사 호수를 입력해 주세요.',
  password: `등록할 때 정한 비밀번호 숫자 ${PASSWORD_LENGTH}자리를 입력해 주세요.`,
}

const TOO_LONG_MESSAGES: Record<FieldKey, string> = {
  className: `반은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  studentName: `이름은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  roomNumber: `기숙사 호수는 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  password: `비밀번호는 숫자 ${PASSWORD_LENGTH}자리로 입력해 주세요.`,
}

const PATTERN_MESSAGES: Record<FieldKey, string> = {
  className: '반은 숫자만 입력해 주세요.',
  studentName: '이름에는 한글·영문만 사용할 수 있습니다.',
  roomNumber: '기숙사 호수는 숫자만 입력해 주세요.',
  password: '',
}

const FIELD_KEYS: FieldKey[] = ['className', 'studentName', 'roomNumber', 'password']

/** 숫자만 받는 필드. 이름은 한글 조합이 깨지므로 절대 넣지 않는다. */
const DIGIT_FIELDS: FieldKey[] = ['className', 'roomNumber']

const {
  windowInfo,
  state: windowState,
  errorMessage: windowError,
  targetDate,
  refresh: refreshWindow,
} = useServerClock()

// 취소하려는 사람은 방금 등록한 사람이다. 반·이름·호수를 다시 치게 하지 않는다(R6 와 같은 취지).
// 비밀번호는 저장하지 않으므로 그 칸만 비어 있다.
const { lastInput, classSuggestions, nameSuggestions, roomSuggestions } = useLastInput()

const form = reactive<Record<FieldKey, string>>({
  className: '',
  studentName: '',
  roomNumber: '',
  password: '',
})
const errors = reactive<Record<FieldKey, string>>({
  className: '',
  studentName: '',
  roomNumber: '',
  password: '',
})
const touched = reactive<Record<FieldKey, boolean>>({
  className: false,
  studentName: false,
  roomNumber: false,
  password: false,
})

const submitting = ref(false)
const formError = ref('')
const statusMessage = ref('')
const result = ref<CancelResponse | null>(null)

/** 확인 단계를 거쳤는지. 실수로 한 번에 취소되지 않게 두 단계로 나눈다. */
const confirming = ref(false)

/** 시도 횟수를 다 써서 오늘은 더 시도할 수 없는 상태 */
const locked = ref(false)

const classFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const nameFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const roomFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const passwordFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const resultRef = ref<HTMLElement | null>(null)

/** 마감 시각 라벨 (`21:45`) */
const closeTimeLabel = computed(() => formatClockTime(windowInfo.value?.closesAt))

/** 복귀 시각 라벨 (`23:30`) */
const returnTimeLabel = computed(() => formatTimeHm(windowInfo.value?.returnTime))

/** 헤더에 표시할 날짜 문구 */
const dateLabel = computed(() => formatKoreanDate(targetDate.value || todayIsoLocal()))

/** 마감되어 취소가 잠긴 상태인지 */
const isClosed = computed(() => windowState.value === 'closed')

/** 입력칸을 잠글지 여부 */
const inputsDisabled = computed(() => isClosed.value || submitting.value || locked.value || result.value !== null)

/** 제출 가능 여부 */
const canSubmit = computed(() => !submitting.value && !isClosed.value && !locked.value && result.value === null)

onMounted(() => {
  const last = lastInput.value
  if (last) {
    form.className = last.className
    form.studentName = last.studentName
    form.roomNumber = last.roomNumber
    statusMessage.value = '이전에 입력한 정보를 불러왔습니다. 비밀번호만 입력해 주세요.'
  }
})

watch(isClosed, (closed) => {
  if (!closed) {
    return
  }
  const closedAt = closeTimeLabel.value ? ` (마감 ${closeTimeLabel.value})` : ''
  statusMessage.value = `등록 취소도 마감되었습니다.${closedAt} 명단은 이미 사감 선생님께 전달되었습니다.`
})

function normalize(value: string): string {
  return value.trim().replace(/\s+/g, ' ')
}

function validateField(key: FieldKey): string {
  if (key === 'password') {
    const password = form.password.trim()
    if (password.length === 0) {
      return REQUIRED_MESSAGES.password
    }
    return PASSWORD_PATTERN.test(password) ? '' : TOO_LONG_MESSAGES.password
  }

  const value = normalize(form[key])
  if (value.length === 0) {
    return REQUIRED_MESSAGES[key]
  }
  if (value.length > MAX_LENGTH) {
    return TOO_LONG_MESSAGES[key]
  }
  const pattern = key === 'studentName' ? NAME_PATTERN : DIGITS_PATTERN
  if (!pattern.test(value)) {
    return PATTERN_MESSAGES[key]
  }
  return ''
}

function onFieldBlur(key: FieldKey): void {
  touched[key] = true
  errors[key] = validateField(key)
}

function onFieldInput(key: FieldKey, value: string): void {
  // 숫자 칸은 입력 순간에 걸러내되, **이름 칸은 건드리지 않는다** —
  // 한글은 ㅎ → 호 → 홍 처럼 조합 중간 상태를 거치므로 필터를 걸면 입력 자체가 깨진다.
  if (key === 'password') {
    form[key] = value.replace(/\D/g, '').slice(0, PASSWORD_LENGTH)
  } else if (DIGIT_FIELDS.includes(key)) {
    form[key] = value.replace(/\D/g, '').slice(0, MAX_LENGTH)
  } else {
    form[key] = value
  }
  if (touched[key]) {
    errors[key] = validateField(key)
  }
  formError.value = ''
  // 입력을 고치면 확인 단계를 처음으로 되돌린다. 확인 문구가 떠 있는 채로 값만 바뀌면
  // 사용자가 "확인한 그 내용"과 실제로 보내지는 내용이 달라진다.
  confirming.value = false
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
  if (key === 'roomNumber') {
    roomFieldRef.value?.focus()
    return
  }
  passwordFieldRef.value?.focus()
}

/**
 * 제출. 첫 번째 누름은 "확인" 단계로만 넘어가고, 두 번째 누름에서 실제로 취소한다.
 *
 * 취소는 되돌릴 수는 있지만(다시 등록하면 된다) 그 사실을 모르고 지나가면
 * 명단에서 빠진 채 밤을 맞게 된다. 한 단계를 더 두는 비용이 그 위험보다 싸다.
 */
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
    confirming.value = false
    focusField(firstInvalid)
    return
  }

  if (!confirming.value) {
    confirming.value = true
    statusMessage.value = '취소할 내용을 확인한 뒤 한 번 더 눌러 주세요.'
    return
  }

  const payload = {
    className: normalize(form.className),
    studentName: normalize(form.studentName),
    roomNumber: normalize(form.roomNumber),
    password: form.password.trim(),
  }

  submitting.value = true
  formError.value = ''
  try {
    const response = await cancelRegistration(payload)
    result.value = response
    statusMessage.value = response.message
    // 성공하면 비밀번호를 즉시 지운다. 화면에 남겨 둘 이유가 없다.
    form.password = ''
    confirming.value = false
    await nextTick()
    resultRef.value?.focus()
  } catch (error) {
    handleSubmitError(error)
  } finally {
    submitting.value = false
  }
}

function handleSubmitError(error: unknown): void {
  confirming.value = false
  if (!(error instanceof ApiError)) {
    formError.value = '알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
    statusMessage.value = formError.value
    return
  }

  for (const key of FIELD_KEYS) {
    const message = error.fieldErrors[key]
    if (message) {
      touched[key] = true
      errors[key] = message
    }
  }

  if (error.code === 'CANCEL_LOCKED') {
    locked.value = true
  }

  formError.value =
    error.code === 'RATE_LIMITED' && error.retryAfterSeconds !== null
      ? `${error.message} (약 ${error.retryAfterSeconds}초 후 다시 시도해 주세요.)`
      : error.message
  statusMessage.value = formError.value

  if (error.code === 'REGISTRATION_CLOSED' || error.code === 'REGISTRATION_NOT_OPEN') {
    void refreshWindow()
  }

  // 실패했으면 비밀번호만 비우고 다시 입력하게 한다(반·이름·호수는 그대로 두어 재입력 부담을 줄인다).
  if (error.code === 'CANCEL_REJECTED') {
    form.password = ''
    errors.password = ''
    touched.password = false
    void nextTick(() => focusField('password'))
  }
}

/** 제출 버튼 문구. 확인 단계에서는 되돌릴 수 없다는 뜻이 드러나야 한다. */
const submitLabel = computed(() => {
  if (submitting.value) {
    return '취소 처리 중…'
  }
  if (isClosed.value) {
    return '취소 마감'
  }
  if (locked.value) {
    return '오늘은 더 시도할 수 없습니다'
  }
  return confirming.value ? '정말 취소합니다' : '등록 취소하기'
})
</script>

<template>
  <main id="main" class="app-main">
    <div class="container stack">
      <AppHeader title="야간 복귀 등록 취소" :subtitle="dateLabel" />

      <!-- 화면 낭독기용 상태 안내 -->
      <p class="sr-only" role="status" aria-live="polite">{{ statusMessage }}</p>

      <!-- 취소 완료 -->
      <section
        v-if="result"
        ref="resultRef"
        class="alert alert--success"
        tabindex="-1"
        role="status"
        aria-labelledby="cancel-result-title"
      >
        <span id="cancel-result-title" class="alert__title">
          {{ result.alreadyCancelled ? '이미 취소되어 있었습니다' : '취소되었습니다' }}
        </span>
        <span>{{ result.message }}</span>
        <span v-if="returnTimeLabel" class="text-sm">
          다시 {{ returnTimeLabel }} 복귀가 필요해지면 마감 전에 다시 등록할 수 있습니다.
        </span>
        <span>
          <RouterLink class="btn btn--secondary" to="/">등록 화면으로</RouterLink>
        </span>
      </section>

      <section v-if="isClosed" class="alert alert--warn no-print">
        <span class="alert__title">취소 마감 이후 안내</span>
        <ul class="notice-list">
          <li v-if="closeTimeLabel">{{ closeTimeLabel }} 이후에는 취소할 수 없습니다.</li>
          <li>명단은 이미 사감 선생님께 전달되어 시스템에서 되돌릴 수 없습니다.</li>
          <li>사정이 바뀌었다면 사감 선생님이나 운영진에게 직접 알려 주세요.</li>
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

      <section v-if="!result" class="card">
        <h2 class="card__title">취소할 등록 정보</h2>
        <p class="text-sm text-muted">
          등록할 때 입력한 반·이름·기숙사 호수와 그때 정한 비밀번호를 입력해 주세요. 네 가지가 모두 맞아야
          취소됩니다.
        </p>

        <form class="form" novalidate @submit.prevent="onSubmit">
          <FormField
            id="cancelClassName"
            ref="classFieldRef"
            label="반"
            :model-value="form.className"
            :error="errors.className"
            :suggestions="classSuggestions"
            :disabled="inputsDisabled"
            placeholder="예: 1"
            autocomplete="off"
            inputmode="numeric"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('className', value)"
            @blur="onFieldBlur('className')"
          />

          <FormField
            id="cancelStudentName"
            ref="nameFieldRef"
            label="이름"
            :model-value="form.studentName"
            :error="errors.studentName"
            :suggestions="nameSuggestions"
            :disabled="inputsDisabled"
            placeholder="예: 홍길동"
            autocomplete="name"
            inputmode="text"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('studentName', value)"
            @blur="onFieldBlur('studentName')"
          />

          <FormField
            id="cancelRoomNumber"
            ref="roomFieldRef"
            label="기숙사 호수"
            :model-value="form.roomNumber"
            :error="errors.roomNumber"
            :suggestions="roomSuggestions"
            :disabled="inputsDisabled"
            placeholder="예: 302"
            autocomplete="off"
            inputmode="numeric"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('roomNumber', value)"
            @blur="onFieldBlur('roomNumber')"
          />

          <FormField
            id="cancelPasswordInput"
            ref="passwordFieldRef"
            label="취소 비밀번호"
            type="password"
            :model-value="form.password"
            :error="errors.password"
            :disabled="inputsDisabled"
            :maxlength="PASSWORD_LENGTH"
            placeholder="숫자 4자리"
            hint="등록할 때 정한 숫자 4자리입니다."
            autocomplete="off"
            inputmode="numeric"
            enterkeyhint="done"
            @update:model-value="(value: string) => onFieldInput('password', value)"
            @blur="onFieldBlur('password')"
          />

          <!-- 확인 단계: 무엇이 취소되는지 눈으로 확인하게 한다 -->
          <p v-if="confirming" class="alert alert--warn confirm" role="alert">
            <span class="alert__title">아래 등록을 취소합니다</span>
            <!-- 반이 숫자만 남으면서 라벨이 없으면 "1 · 홍길동 · 302호" 처럼 무엇이 반인지 안 보인다. -->
            <span class="confirm__target">{{ form.className }}반 · {{ form.studentName }} · {{ form.roomNumber }}호</span>
            <span class="text-sm">
              취소하면 오늘 밤 명단에서 빠집니다. 한 번 더 누르면 취소가 완료됩니다.
            </span>
          </p>

          <p class="form__error" aria-live="polite">
            <span v-if="formError" class="alert alert--danger" role="alert">{{ formError }}</span>
          </p>

          <button
            type="submit"
            class="btn btn--lg btn--block"
            :class="confirming ? 'btn--danger' : 'btn--primary'"
            :disabled="!canSubmit"
          >
            {{ submitLabel }}
          </button>

          <p class="form__foot no-print">
            <RouterLink class="btn btn--ghost" to="/">등록 화면으로 돌아가기</RouterLink>
          </p>
        </form>
      </section>

      <section class="card card--flat" aria-labelledby="cancel-notice-title">
        <h2 id="cancel-notice-title" class="card__title">안내</h2>
        <ul class="notice-list">
          <li v-if="closeTimeLabel">취소는 등록과 마찬가지로 {{ closeTimeLabel }}까지만 가능합니다.</li>
          <li>비밀번호를 잊었다면 취소할 수 없습니다. 사감 선생님이나 운영진에게 문의해 주세요.</li>
          <li>비밀번호를 여러 번 틀리면 그날은 더 시도할 수 없습니다.</li>
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

.confirm {
  margin: 0;
}

/* 취소 대상이 한눈에 들어와야 실수로 남의 등록을 지우는 일이 줄어든다. */
.confirm__target {
  font-size: var(--fs-lg);
  font-weight: 700;
  word-break: break-word;
}
</style>
