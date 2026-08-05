/**
 * 날짜·시간·숫자 표시 포맷 유틸.
 *
 * 서버가 내려주는 `LocalDate`/`LocalDateTime`/`LocalTime` 문자열은 이미 KST 기준이므로
 * Date 객체로 변환하지 않고 **문자열에서 직접 잘라 쓴다**. (사용자 PC 시간대가 달라도 값이 흔들리지 않는다.)
 */

const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토']

/** `2026-08-05T21:03:11+09:00` → `2026-08-05` */
export function toDatePart(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  return matched ? matched[0] : value
}

/** `2026-08-05` → `2026년 8월 5일 (수)` */
export function formatKoreanDate(value: string | null | undefined): string {
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(value ?? '')
  if (!matched) {
    return value ?? ''
  }
  const year = Number(matched[1])
  const month = Number(matched[2])
  const day = Number(matched[3])
  const weekday = WEEKDAY_LABELS[new Date(year, month - 1, day).getDay()] ?? ''
  return `${year}년 ${month}월 ${day}일 (${weekday})`
}

/** `2026-08-05` → `8월 5일 (수)` */
export function formatKoreanDateShort(value: string | null | undefined): string {
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(value ?? '')
  if (!matched) {
    return value ?? ''
  }
  const year = Number(matched[1])
  const month = Number(matched[2])
  const day = Number(matched[3])
  const weekday = WEEKDAY_LABELS[new Date(year, month - 1, day).getDay()] ?? ''
  return `${month}월 ${day}일 (${weekday})`
}

/** `23:30` 또는 `23:30:00` → `23:30` */
export function formatTimeHm(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const matched = /^(\d{2}):(\d{2})/.exec(value)
  return matched ? `${matched[1]}:${matched[2]}` : value
}

/** `2026-08-05T21:03:11` → `21:03` */
export function formatClockTime(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const matched = /[T ](\d{2}):(\d{2})/.exec(value)
  return matched ? `${matched[1]}:${matched[2]}` : value
}

/** `2026-08-05T21:03:11` → `2026년 8월 5일 (수) 21:03` */
export function formatKoreanDateTime(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const date = formatKoreanDate(value)
  const time = formatClockTime(value)
  return time ? `${date} ${time}` : date
}

/** 남은 초를 사람이 읽기 좋은 한국어로. 예) `1시간 23분 45초` */
export function formatDuration(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(Number.isFinite(totalSeconds) ? totalSeconds : 0))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const seconds = safe % 60
  if (hours > 0) {
    return `${hours}시간 ${pad(minutes)}분 ${pad(seconds)}초`
  }
  if (minutes > 0) {
    return `${minutes}분 ${pad(seconds)}초`
  }
  return `${seconds}초`
}

/** 클라이언트 기준 오늘 날짜를 `yyyy-MM-dd` 로. (서버 날짜를 못 받았을 때의 폴백) */
export function todayIsoLocal(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/** 1000 → `1,000` */
export function formatNumber(value: number | null | undefined): string {
  const numeric = typeof value === 'number' && Number.isFinite(value) ? value : 0
  return numeric.toLocaleString('ko-KR')
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}
