/**
 * 이전 입력값 기억 컴포저블 (요구사항 R6).
 *
 * - `imlate.lastInput`   : 마지막으로 성공한 입력 1건 → 다음 방문 시 자동 채움
 * - `imlate.recentInputs`: 최근 3건 → 각 입력칸 datalist 제안
 *
 * 개인정보 배려를 위해 사용자가 언제든 지울 수 있도록 `clearSaved()` 를 제공한다.
 */

import { computed, ref } from 'vue'

import { readJson, removeItem, writeJson } from '../utils/storage'

/** 저장되는 입력 한 벌 */
export interface SavedInput {
  className: string
  studentName: string
  roomNumber: string
}

const LAST_INPUT_KEY = 'imlate.lastInput'
const RECENT_INPUTS_KEY = 'imlate.recentInputs'
/** 최근 입력 보관 개수 */
const RECENT_LIMIT = 3

/** localStorage 기반 이전 입력값 관리 */
export function useLastInput() {
  const lastInput = ref<SavedInput | null>(loadLastInput())
  const recentInputs = ref<SavedInput[]>(loadRecentInputs())

  const hasSaved = computed(() => lastInput.value !== null || recentInputs.value.length > 0)

  const classSuggestions = computed(() => unique(recentInputs.value.map((item) => item.className)))
  const nameSuggestions = computed(() => unique(recentInputs.value.map((item) => item.studentName)))
  const roomSuggestions = computed(() => unique(recentInputs.value.map((item) => item.roomNumber)))

  /** 성공한 입력을 기억한다. 동일 입력은 중복 저장하지 않고 맨 앞으로 올린다. */
  function remember(input: SavedInput): void {
    const normalized: SavedInput = {
      className: input.className.trim(),
      studentName: input.studentName.trim(),
      roomNumber: input.roomNumber.trim(),
    }
    if (!isValidSavedInput(normalized)) {
      return
    }

    lastInput.value = normalized
    writeJson(LAST_INPUT_KEY, normalized)

    const key = signature(normalized)
    const next = [normalized, ...recentInputs.value.filter((item) => signature(item) !== key)].slice(0, RECENT_LIMIT)
    recentInputs.value = next
    writeJson(RECENT_INPUTS_KEY, next)
  }

  /** 저장된 정보를 모두 삭제한다. */
  function clearSaved(): void {
    lastInput.value = null
    recentInputs.value = []
    removeItem(LAST_INPUT_KEY)
    removeItem(RECENT_INPUTS_KEY)
  }

  return {
    lastInput,
    recentInputs,
    hasSaved,
    classSuggestions,
    nameSuggestions,
    roomSuggestions,
    remember,
    clearSaved,
  }
}

// ---------------------------------------------------------------- 내부 유틸

function loadLastInput(): SavedInput | null {
  const raw = readJson(LAST_INPUT_KEY)
  const parsed = toSavedInput(raw)
  return parsed && isValidSavedInput(parsed) ? parsed : null
}

function loadRecentInputs(): SavedInput[] {
  const raw = readJson(RECENT_INPUTS_KEY)
  if (!Array.isArray(raw)) {
    return []
  }
  const result: SavedInput[] = []
  const seen = new Set<string>()
  for (const entry of raw) {
    const parsed = toSavedInput(entry)
    if (!parsed || !isValidSavedInput(parsed)) {
      continue
    }
    const key = signature(parsed)
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    result.push(parsed)
    if (result.length >= RECENT_LIMIT) {
      break
    }
  }
  return result
}

/** 반·기숙사 호수 규칙 — 숫자만. 화면·서버 검증과 같은 규칙이어야 한다. */
const DIGITS_PATTERN = /^[0-9]{1,20}$/

/** 이름 규칙 — 글자만(한글·영문). 가운데 공백 불허(운영 요청). */
const NAME_PATTERN = /^[가-힣A-Za-z]+$/

function toSavedInput(value: unknown): SavedInput | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const studentName = typeof record.studentName === 'string' ? record.studentName.trim() : ''

  // 반·호수는 숫자만 남긴다.
  //
  // **왜 버리지 않고 고쳐 쓰는가** — 입력 규칙이 "숫자만"으로 바뀌기 전에 저장된 값은
  // "1반", "302호" 같은 형태다. 그대로 채우면 사용자는 자기가 건드리지도 않은 칸에서
  // 갑자기 오류 문구를 보게 되고, 무엇을 고쳐야 하는지 알기 어렵다.
  // 숫자만 뽑아내면 "1반" → "1", "302호" → "302" 로 사용자가 의도했던 값이 그대로 남는다.
  const className = extractDigits(record.className)
  const roomNumber = extractDigits(record.roomNumber)

  return { className, studentName, roomNumber }
}

/** 문자열에서 숫자만 뽑아낸다(최대 20자). 문자열이 아니면 빈 값. */
function extractDigits(value: unknown): string {
  if (typeof value !== 'string') {
    return ''
  }
  return value.replace(/\D/g, '').slice(0, 20)
}

/**
 * 저장·복원할 값으로 쓸 수 있는지 검사한다.
 *
 * 이름은 고쳐 쓸 방법이 없으므로(글자를 지우면 다른 이름이 된다) 규칙에 맞지 않으면
 * 그 저장분 자체를 버린다 — 잘못된 이름을 자동으로 채워 주는 것보다 빈 칸이 낫다.
 */
function isValidSavedInput(input: SavedInput): boolean {
  return (
    DIGITS_PATTERN.test(input.className) &&
    NAME_PATTERN.test(input.studentName) &&
    input.studentName.length <= 20 &&
    DIGITS_PATTERN.test(input.roomNumber)
  )
}

function signature(input: SavedInput): string {
  return `${input.className}|${input.studentName}|${input.roomNumber}`
}

function unique(values: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const value of values) {
    const trimmed = value.trim()
    if (trimmed.length === 0 || seen.has(trimmed)) {
      continue
    }
    seen.add(trimmed)
    result.push(trimmed)
  }
  return result
}
