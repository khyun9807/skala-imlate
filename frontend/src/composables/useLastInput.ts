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

function toSavedInput(value: unknown): SavedInput | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const className = typeof record.className === 'string' ? record.className.trim() : ''
  const studentName = typeof record.studentName === 'string' ? record.studentName.trim() : ''
  const roomNumber = typeof record.roomNumber === 'string' ? record.roomNumber.trim() : ''
  return { className, studentName, roomNumber }
}

function isValidSavedInput(input: SavedInput): boolean {
  return (
    input.className.length > 0 &&
    input.className.length <= 20 &&
    input.studentName.length > 0 &&
    input.studentName.length <= 20 &&
    input.roomNumber.length > 0 &&
    input.roomNumber.length <= 20
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
