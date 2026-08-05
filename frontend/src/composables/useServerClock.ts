/**
 * 서버 시간 기준 등록 창(카운트다운) 컴포저블.
 *
 * 클라이언트 PC 시계가 틀려도 마감 시간이 어긋나지 않도록,
 * `/registrations/window` 응답의 `serverTime` 으로 **오차(offset)** 를 계산해 보정한다.
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { fetchRegistrationWindow, toApiError } from '../api/client'
import type { RegistrationWindow } from '../api/types'

/** 등록 창 상태 */
export type WindowState = 'loading' | 'open' | 'closed' | 'error'

/** 1초마다 갱신되는 서버 기준 시계를 제공한다. */
export function useServerClock() {
  const windowInfo = ref<RegistrationWindow | null>(null)
  const loading = ref(true)
  const errorMessage = ref<string | null>(null)

  /** 서버시각 - 클라이언트시각 (ms) */
  const offsetMs = ref(0)
  /** 1초마다 갱신되는 클라이언트 현재 시각 */
  const clientNowMs = ref(Date.now())
  /** window 응답을 받은 시점의 클라이언트 시각 (closesAt 파싱 실패 시 폴백 계산용) */
  const fetchedAtMs = ref(0)

  let ticker: ReturnType<typeof setInterval> | undefined

  /** 서버 기준 현재 시각(ms) */
  const serverNowMs = computed(() => clientNowMs.value + offsetMs.value)

  /** 마감까지 남은 초 (0 이상) */
  const secondsUntilClose = computed(() => {
    const info = windowInfo.value
    if (!info) {
      return 0
    }
    const closesAtMs = Date.parse(info.closesAt)
    if (Number.isFinite(closesAtMs)) {
      return Math.max(0, Math.floor((closesAtMs - serverNowMs.value) / 1000))
    }
    // closesAt 파싱 실패 시 secondsUntilClose 값에서 경과분을 뺀다.
    const elapsedSeconds = Math.floor((clientNowMs.value - fetchedAtMs.value) / 1000)
    const fallbackSeconds = Number.isFinite(info.secondsUntilClose) ? info.secondsUntilClose : 0
    return Math.max(0, fallbackSeconds - elapsedSeconds)
  })

  /** 현재 등록 가능한지 */
  const isOpen = computed(() => {
    const info = windowInfo.value
    if (!info) {
      return false
    }
    return info.open && secondsUntilClose.value > 0
  })

  /** 화면 표시용 상태 */
  const state = computed<WindowState>(() => {
    if (loading.value && !windowInfo.value) {
      return 'loading'
    }
    if (!windowInfo.value) {
      return 'error'
    }
    return isOpen.value ? 'open' : 'closed'
  })

  /** 등록 대상일 `yyyy-MM-dd` */
  const targetDate = computed(() => windowInfo.value?.date ?? '')

  /** 서버에서 등록 창 정보를 다시 가져온다. */
  async function refresh(): Promise<void> {
    loading.value = true
    try {
      const info = await fetchRegistrationWindow()
      const receivedAt = Date.now()
      const serverTimeMs = Date.parse(info.serverTime)
      offsetMs.value = Number.isFinite(serverTimeMs) ? serverTimeMs - receivedAt : 0
      fetchedAtMs.value = receivedAt
      clientNowMs.value = receivedAt
      windowInfo.value = info
      errorMessage.value = null
    } catch (error) {
      errorMessage.value = toApiError(error).message
    } finally {
      loading.value = false
    }
  }

  function handleVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      clientNowMs.value = Date.now()
      void refresh()
    }
  }

  // 마감 순간(남은 시간 0)에 서버 상태를 한 번 재확인한다.
  watch(secondsUntilClose, (current, previous) => {
    if (current === 0 && previous > 0) {
      void refresh()
    }
  })

  onMounted(() => {
    void refresh()
    ticker = setInterval(() => {
      clientNowMs.value = Date.now()
    }, 1000)
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onBeforeUnmount(() => {
    if (ticker !== undefined) {
      clearInterval(ticker)
      ticker = undefined
    }
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return {
    windowInfo,
    loading,
    errorMessage,
    state,
    isOpen,
    secondsUntilClose,
    serverNowMs,
    targetDate,
    refresh,
  }
}
