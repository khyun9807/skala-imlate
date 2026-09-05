/**
 * 서비스 종료 안내에 쓰는 값들.
 *
 * **이 파일이 종료 시점의 유일한 출처다.** 여기만 고치고 배포하면 등록·취소·조회
 * 세 화면의 문구와 남은 시간이 모두 따라간다.
 *
 * <p>다른 시각(등록 마감 등)과 달리 서버 설정으로 빼지 않은 이유 — 이 값은 서버 동작을
 * 바꾸지 않는 <b>안내 문구</b>이고, 종료와 함께 코드 자체가 수명을 다한다.
 * 화면 세 곳이 같은 값을 말하게 하는 것이 목적이므로 상수로 충분하다.
 */

/** 마지막 운영일(이 날까지는 평소대로 이용 가능). `yyyy-MM-dd` */
export const SERVICE_END_DATE = '2026-09-07'

/** 서비스가 멈추는 날(이 날부터 접속 불가). `yyyy-MM-dd` */
export const SERVICE_STOP_DATE = '2026-09-08'

/**
 * 카운트다운의 기준이 되는 **마지막 순간** — 마지막 날의 등록 마감(22:15 KST).
 *
 * 왜 자정이 아니라 22:15 인가 — 이용자에게 의미 있는 마지막 순간은 "마지막으로 등록할 수 있는
 * 시각"이지 날짜가 넘어가는 순간이 아니다. 22:15 를 넘기면 그날 등록은 이미 불가능하다.
 *
 * ※ `imlate.registration.close-time`(기본 22:15)과 같은 값이어야 한다.
 *   마감 시각을 옮기면 이 줄도 함께 고친다.
 */
export const SERVICE_END_AT = '2026-09-07T22:15:00+09:00'

/** KST 기준 오늘 `yyyy-MM-dd` (안내 문구용이라 클라이언트 시계로 충분하다) */
function todayKst(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(new Date())
}

/**
 * 마지막 순간까지 남은 밀리초. 이미 지났으면 0 이하.
 *
 * @param now 기준 시각(테스트에서 고정하기 위한 인자)
 */
export function msUntilServiceEnd(now: number = Date.now()): number {
  const end = Date.parse(SERVICE_END_AT)
  return Number.isFinite(end) ? end - now : 0
}

/**
 * 남은 시간을 사람이 읽는 문구로.
 *
 * 시간 단위로 보여 주되, 한 시간이 안 남으면 분으로 내려간다 —
 * "종료까지 0시간"은 아무것도 알려 주지 않는다.
 * 이미 지났으면 빈 문자열(배지를 감춘다).
 */
export function remainingLabel(now: number = Date.now()): string {
  const ms = msUntilServiceEnd(now)
  if (ms <= 0) {
    return ''
  }
  const totalMinutes = Math.floor(ms / 60_000)
  const hours = Math.floor(totalMinutes / 60)
  if (hours >= 1) {
    return `종료까지 ${hours}시간`
  }
  // 1분 미만도 "1분"으로 보여 준다. "0분 남음"은 이미 끝난 것처럼 읽힌다.
  return `종료까지 ${Math.max(1, totalMinutes)}분`
}

/** 종료 시점이 지났는지(= 문구를 과거형으로 바꿔야 하는지). */
export function isAfterServiceEnd(now: number = Date.now()): boolean {
  return msUntilServiceEnd(now) <= 0
}

/** 오늘이 마지막 운영일인지 (필요할 때 쓰라고 남겨 둔다). */
export function isLastDay(today: string = todayKst()): boolean {
  return today === SERVICE_END_DATE
}
