/**
 * 서비스 종료 안내에 쓰는 값들.
 *
 * **이 파일이 종료일의 유일한 출처다.** 날짜를 바꾸려면 여기만 고치고 배포하면
 * 등록·취소·조회 세 화면의 문구와 남은 일수가 모두 따라간다.
 *
 * <p>다른 시각(등록 마감 등)과 달리 서버 설정으로 빼지 않은 이유 — 이 값은 서버 동작을
 * 바꾸지 않는 <b>안내 문구</b>이고, 종료와 함께 코드 자체가 수명을 다한다.
 * 화면 세 곳이 같은 값을 말하게 하는 것이 목적이므로 상수 하나로 충분하다.
 */

/** 마지막 운영일(이 날까지는 평소대로 이용 가능). `yyyy-MM-dd` */
export const SERVICE_END_DATE = '2026-09-07'

/** 서비스가 멈추는 날(이 날부터 접속 불가). `yyyy-MM-dd` */
export const SERVICE_STOP_DATE = '2026-09-08'

/** KST 기준 오늘 `yyyy-MM-dd` (클라이언트 시계 기준 — 안내 문구용이라 오차가 문제되지 않는다) */
function todayKst(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(new Date())
}

/**
 * 마지막 운영일까지 남은 일수.
 *
 * 0 이면 오늘이 마지막 날, 음수면 이미 종료일이 지난 것이다.
 * 날짜 문자열끼리 UTC 자정 기준으로 빼므로 시간대·서머타임 영향을 받지 않는다.
 */
export function daysUntilServiceEnd(today: string = todayKst()): number {
  const a = Date.parse(`${today}T00:00:00Z`)
  const b = Date.parse(`${SERVICE_END_DATE}T00:00:00Z`)
  if (!Number.isFinite(a) || !Number.isFinite(b)) {
    return 0
  }
  return Math.round((b - a) / 86_400_000)
}

/** 종료일이 지났는지(= 서비스가 멈춘 뒤인지). */
export function isAfterServiceEnd(today: string = todayKst()): boolean {
  return daysUntilServiceEnd(today) < 0
}
