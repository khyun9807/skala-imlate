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
import { RouterLink } from 'vue-router'

import AppHeader from '../components/AppHeader.vue'
import CountdownBadge from '../components/CountdownBadge.vue'
import FormField from '../components/FormField.vue'
import ResultCard from '../components/ResultCard.vue'
import { ApiError, createRegistration } from '../api/client'
import type { RegistrationResponse } from '../api/types'
import { useLastInput } from '../composables/useLastInput'
import { useServerClock } from '../composables/useServerClock'
import { useYesterdayNote } from '../composables/useYesterdayNote'
import {
  addDaysIso,
  formatClockTime,
  formatKoreanDate,
  formatTimeHm,
  relativeDayLabel,
  toDatePart,
  todayIsoLocal,
} from '../utils/format'

/**
 * 서버 검증 규칙과 동일한 허용 문자 (SPEC §5.5).
 *
 * 반·기숙사 호수는 숫자만, 이름은 글자만(한글·영문) 받는다.
 * 이름에만 글자 사이 공백 한 칸을 허용한다 — "Alice Kim" 처럼 띄어 쓰는 이름이 있다.
 */
const DIGITS_PATTERN = /^[0-9]{1,20}$/
const NAME_PATTERN = /^[가-힣A-Za-z]+( [가-힣A-Za-z]+)*$/
const MAX_LENGTH = 20

/** 취소 비밀번호 자릿수. 서버 `imlate.registration.cancel.password-length` 와 같아야 한다. */
const PASSWORD_LENGTH = 4
const PASSWORD_PATTERN = /^[0-9]{4}$/

type FieldKey = 'className' | 'studentName' | 'roomNumber' | 'cancelPassword'

const REQUIRED_MESSAGES: Record<FieldKey, string> = {
  className: '반을 입력해 주세요.',
  studentName: '이름을 입력해 주세요.',
  roomNumber: '기숙사 호수를 입력해 주세요.',
  cancelPassword: `비밀번호 숫자 ${PASSWORD_LENGTH}자리를 입력해 주세요.`,
}

const TOO_LONG_MESSAGES: Record<FieldKey, string> = {
  className: `반은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  studentName: `이름은 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  roomNumber: `기숙사 호수는 ${MAX_LENGTH}자 이하로 입력해 주세요.`,
  cancelPassword: `비밀번호는 숫자 ${PASSWORD_LENGTH}자리로 입력해 주세요.`,
}

const PATTERN_MESSAGES: Record<FieldKey, string> = {
  className: '반은 숫자만 입력해 주세요.',
  studentName: '이름에는 한글·영문만 사용할 수 있습니다.',
  roomNumber: '기숙사 호수는 숫자만 입력해 주세요.',
  cancelPassword: '',
}

const FIELD_KEYS: FieldKey[] = ['className', 'studentName', 'roomNumber', 'cancelPassword']

/** 숫자만 받는 필드. 입력 순간에 숫자가 아닌 글자를 걸러낸다. */
const DIGIT_FIELDS: FieldKey[] = ['className', 'roomNumber']

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

/** 어제 연장 복귀 인원 한 줄. 인원이 0이거나 못 불러오면 빈 문자열이라 화면에 나오지 않는다. */
const { message: yesterdayMessage } = useYesterdayNote()

const form = reactive<Record<FieldKey, string>>({
  className: '',
  studentName: '',
  roomNumber: '',
  cancelPassword: '',
})
const errors = reactive<Record<FieldKey, string>>({
  className: '',
  studentName: '',
  roomNumber: '',
  cancelPassword: '',
})
const touched = reactive<Record<FieldKey, boolean>>({
  className: false,
  studentName: false,
  roomNumber: false,
  cancelPassword: false,
})

const submitting = ref(false)
const formError = ref('')
const statusMessage = ref('')
const result = ref<RegistrationResponse | null>(null)

const classFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const nameFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const roomFieldRef = ref<InstanceType<typeof FormField> | null>(null)
const passwordFieldRef = ref<InstanceType<typeof FormField> | null>(null)
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
  // 비밀번호는 숫자 고정 자릿수라 문자 규칙·길이 규칙이 나머지 필드와 다르다.
  if (key === 'cancelPassword') {
    const password = form.cancelPassword.trim()
    if (password.length === 0) {
      return REQUIRED_MESSAGES.cancelPassword
    }
    return PASSWORD_PATTERN.test(password) ? '' : TOO_LONG_MESSAGES.cancelPassword
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
  // 숫자만 받는 칸(비밀번호·반·호수)은 입력 순간에 숫자가 아닌 글자를 걸러낸다.
  // 나중에 오류 문구로 알려 주는 것보다 애초에 들어가지 않게 하는 편이 낫다.
  //
  // **이름 칸에는 절대 이 필터를 걸지 않는다.** 한글은 자판을 누르는 동안
  // ㅎ → 호 → 홍 처럼 조합 중간 상태를 거치는데, 그 순간마다 글자를 걸러내면
  // 조합이 깨져서 한글을 아예 입력할 수 없게 된다. 이름은 blur/제출 시점에만 검사한다.
  if (key === 'cancelPassword') {
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
  if (key === 'cancelPassword') {
    passwordFieldRef.value?.focus()
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

  // 기억해 둘 값(반·이름·호수)과 비밀번호를 분리한다.
  // 비밀번호는 절대 저장하지 않는다 — 공용 기기에서 그 값이 남으면
  // 다음 사람이 남의 등록을 취소할 수 있고, 그러면 그 사람은 명단에서 사라진 줄도 모른 채 문 밖에 갇힌다.
  const identity = {
    className: normalize(form.className),
    studentName: normalize(form.studentName),
    roomNumber: normalize(form.roomNumber),
  }

  submitting.value = true
  formError.value = ''
  let focusTarget: FieldKey | null = null
  try {
    const response = await createRegistration({ ...identity, cancelPassword: form.cancelPassword.trim() })
    result.value = response
    remember(identity)
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

/** 다른 인원을 이어서 등록할 수 있도록 이름/호수/비밀번호를 비운다. */
function registerAnother(): void {
  result.value = null
  form.studentName = ''
  form.roomNumber = ''
  // 비밀번호도 반드시 비운다. 남겨 두면 다음 사람 등록에 앞사람 비밀번호가 그대로 붙어,
  // 정작 본인은 자기 비밀번호를 모르는 상태가 된다(= 취소할 수 없다).
  form.cancelPassword = ''
  errors.studentName = ''
  errors.roomNumber = ''
  errors.cancelPassword = ''
  touched.studentName = false
  touched.roomNumber = false
  touched.cancelPassword = false
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
            placeholder="예: 1"
            hint="반 번호를 숫자로만 적어 주세요."
            autocomplete="off"
            inputmode="numeric"
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
            hint="한글 또는 영문 이름만 입력할 수 있습니다."
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
            hint="호수를 숫자로만 적어 주세요."
            autocomplete="off"
            inputmode="numeric"
            enterkeyhint="next"
            @update:model-value="(value: string) => onFieldInput('roomNumber', value)"
            @blur="onFieldBlur('roomNumber')"
          />

          <FormField
            id="cancelPassword"
            ref="passwordFieldRef"
            label="취소 비밀번호"
            type="password"
            :model-value="form.cancelPassword"
            :error="errors.cancelPassword"
            :disabled="isClosed || submitting"
            :maxlength="PASSWORD_LENGTH"
            placeholder="숫자 4자리"
            hint="등록을 취소할 때 필요합니다. 잊지 않도록 기억해 주세요. (저장되지 않습니다)"
            autocomplete="off"
            inputmode="numeric"
            enterkeyhint="done"
            @update:model-value="(value: string) => onFieldInput('cancelPassword', value)"
            @blur="onFieldBlur('cancelPassword')"
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

      <!--
        취소 안내. 등록 화면에서 바로 갈 수 있어야 한다 —
        "등록은 했는데 계획이 바뀐" 사람이 취소 방법을 찾지 못하면 결국 사감에게 전화가 간다.
      -->
      <section class="card card--flat no-print" aria-labelledby="cancel-guide-title">
        <h2 id="cancel-guide-title" class="card__title">등록을 취소하려면</h2>
        <p class="text-sm text-muted">
          반·이름·기숙사 호수와 등록할 때 정한 비밀번호를 입력하면 취소할 수 있습니다.
          <span v-if="closeTimeLabel">취소도 {{ closeTimeLabel }}까지만 가능합니다.</span>
        </p>
        <p class="form__foot">
          <RouterLink class="btn btn--secondary" to="/cancel">등록 취소하기</RouterLink>
        </p>
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

      <!--
        어제 연장 복귀 인원 한 줄. 화면 맨 아래에 둔다 — 등록에 필요한 정보가 아니라 곁들이는 말이다.
        다만 글씨는 읽을 수 있는 크기로 유지한다(예전에 흐린 작은 글씨로 두었다가 "안 보인다"는 피드백을 받았다).
        인원이 0이거나 값을 못 불러오면 message 가 빈 문자열이라 아예 그려지지 않는다.
      -->
      <p v-if="yesterdayMessage" class="yesterday-note no-print">{{ yesterdayMessage }}</p>

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

/*
  어제 인원 한 줄.

  자리는 화면 맨 아래다. 등록에 필요한 정보가 아니라 곁들이는 말이기 때문이다.
  다만 글씨 크기·대비는 읽을 수 있는 수준으로 유지한다 —
  처음에 흐린 작은 글씨로 두었더니 "너무 눈에 안 띈다"는 피드백을 받았다.
  자리는 뒤로, 가독성은 그대로. 제목·배지로 올리지는 않는다.
*/
.yesterday-note {
  margin: 0;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  /*
    카운트다운과 같은 파란 틴트(--c-accent-soft)를 쓰면 둘이 한 묶음처럼 읽힌다.
    이 문구는 마감 안내와 아무 상관이 없으므로 중립 표면 + 테두리로 따로 세운다.
  */
  background: var(--c-surface);
  border: var(--border-width) solid var(--c-border);
  color: var(--c-text-muted);
  text-align: center;
  font-size: var(--fs-base);
  line-height: 1.6;
  /* 한국어는 단어 중간에서 끊기면 읽기 나빠진다. 좁은 화면에서도 어절 단위로 접히게 한다. */
  word-break: keep-all;
}
</style>
