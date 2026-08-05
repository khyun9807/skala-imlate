/**
 * localStorage 안전 접근 유틸.
 * 사파리 프라이빗 모드·저장소 용량 초과 등으로 localStorage 접근이 실패해도
 * 화면이 깨지지 않도록 메모리 폴백을 사용한다.
 */

const memoryStore = new Map<string, string>()

let resolved = false
let store: Storage | null = null

/** 사용 가능한 localStorage 를 반환한다. 사용 불가하면 null. */
function getStore(): Storage | null {
  if (resolved) {
    return store
  }
  resolved = true
  try {
    const candidate = window.localStorage
    const probeKey = '__imlate_probe__'
    candidate.setItem(probeKey, '1')
    candidate.removeItem(probeKey)
    store = candidate
  } catch {
    store = null
  }
  return store
}

/** 문자열 값을 읽는다. 없으면 null. */
export function readItem(key: string): string | null {
  const target = getStore()
  if (!target) {
    return memoryStore.get(key) ?? null
  }
  try {
    return target.getItem(key)
  } catch {
    return memoryStore.get(key) ?? null
  }
}

/** 문자열 값을 저장한다. 실패해도 예외를 던지지 않는다. */
export function writeItem(key: string, value: string): void {
  memoryStore.set(key, value)
  const target = getStore()
  if (!target) {
    return
  }
  try {
    target.setItem(key, value)
  } catch {
    // 저장 실패(용량 초과 등)는 무시한다. 메모리 폴백만 유지.
  }
}

/** 값을 삭제한다. */
export function removeItem(key: string): void {
  memoryStore.delete(key)
  const target = getStore()
  if (!target) {
    return
  }
  try {
    target.removeItem(key)
  } catch {
    // 무시
  }
}

/** JSON 값을 읽어 파싱한다. 파싱 실패 시 null. */
export function readJson(key: string): unknown {
  const raw = readItem(key)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return null
  }
}

/** 값을 JSON 으로 직렬화해 저장한다. */
export function writeJson(key: string, value: unknown): void {
  try {
    writeItem(key, JSON.stringify(value))
  } catch {
    // 순환 참조 등 직렬화 실패는 무시
  }
}
