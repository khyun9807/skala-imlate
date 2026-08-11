/**
 * 서버 시간 기준 등록/취소 창(카운트다운) 컴포저블.
 *
 * 클라이언트 PC 시계가 틀려도 마감 시간이 어긋나지 않도록,
 * `/registrations/window` 응답의 `serverTime` 으로 **오차(offset)** 를 계산해 보정한다.
 *
 * **등록과 취소는 마감이 다르다**(기본 22:15 / 22:20). 어느 쪽 마감을 추적할지는
 * `deadline` 인자로 고른다. 취소 화면이 등록 마감을 보면 22:15~22:20 사이에
 * "아직 취소할 수 있는데 화면은 마감이라고 말하는" 상태가 된다.
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { fetchRegistrationWindow, toApiError } from '../api/client'
import type { RegistrationWindow } from '../api/types'

/** 등록 창 상태 */
export type WindowState = 'loading' | 'open' | 'closed' | 'error'

/** 어느 마감을 추적할지. `register` = 등록 마감, `cancel` = 취소 마감 */
export type DeadlineKind = 'register' | 'cancel'

/**
 * 1초마다 갱신되는 서버 기준 시계를 제공한다.
 *
 * @param deadline 추적할 마감(기본 `register`)
 */
export function useServerClock(deadline: DeadlineKind = 'register') {
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

  /** 추적 중인 마감 시각 ISO 문자열 */
  const closesAtIso = computed(() => {
    const info = windowInfo.value
    if (!info) {
      return ''
    }
    // 서버가 아직 cancelClosesAt 을 안 내려주는 경우(배포 과도기)에는 등록 마감으로 되돌아간다.
    // 취소 창은 등록 창보다 넓으므로, 좁은 쪽으로 떨어지는 이 폴백은 안전한 방향이다.
    return deadline === 'cancel' ? (info.cancelClosesAt ?? info.closesAt) : info.closesAt
  })

  /** 마감까지 남은 초 (0 이상) */
  const secondsUntilClose = computed(() => {
    const info = windowInfo.value
    if (!info) {
      return 0
    }
    const closesAtMs = Date.parse(closesAtIso.value)
    if (Number.isFinite(closesAtMs)) {
      return Math.max(0, Math.floor((closesAtMs - serverNowMs.value) / 1000))
    }
    // 마감 시각 파싱 실패 시 서버가 준 남은 초에서 경과분을 뺀다.
    const elapsedSeconds = Math.floor((clientNowMs.value - fetchedAtMs.value) / 1000)
    const rawFallback =
      deadline === 'cancel' ? info.secondsUntilCancelClose : info.secondsUntilClose
    const fallbackSeconds = Number.isFinite(rawFallback) ? rawFallback : 0
    return Math.max(0, fallbackSeconds - elapsedSeconds)
  })

  /** 현재 (등록 또는 취소가) 가능한지 */
  const isOpen = computed(() => {
    const info = windowInfo.value
    if (!info) {
      return false
    }
    // cancelOpen 이 없는 응답(배포 과도기)은 등록 창 판정으로 되돌아간다.
    const serverOpen = deadline === 'cancel' ? (info.cancelOpen ?? info.open) : info.open
    return serverOpen && secondsUntilClose.value > 0
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
    /** 추적 중인 마감 시각(ISO). 화면 라벨은 이 값을 써야 등록/취소가 어긋나지 않는다. */
    closesAtIso,
    serverNowMs,
    targetDate,
    refresh,
  }
}
