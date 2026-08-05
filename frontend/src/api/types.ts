/**
 * 백엔드 REST API 응답 타입 정의. (SPEC §5.5, §7.2, §4.2 계약과 1:1 대응)
 */

/** `GET /registrations/window` — 서버 시간 기준 등록 창 정보 */
export interface RegistrationWindow {
  /** 등록 대상일 (KST 기준 오늘) `yyyy-MM-dd` */
  date: string
  /** 현재 등록 가능 여부 */
  open: boolean
  /** 서버 현재 시각 (offset 포함 ISO) */
  serverTime: string
  /** 등록 시작 시각 (offset 포함 ISO) */
  opensAt: string
  /** 등록 마감 시각 (offset 포함 ISO) */
  closesAt: string
  /** 복귀 시각 `23:30` */
  returnTime: string
  /** 통금 시각 `22:30` */
  curfewTime: string
  /** 마감까지 남은 초 */
  secondsUntilClose: number
}

/** `POST /registrations` 요청 바디 */
export interface RegistrationRequest {
  className: string
  studentName: string
  roomNumber: string
  /**
   * 나중에 본인이 취소할 때 쓸 비밀번호(숫자 4자리).
   *
   * 서버는 해시해서 저장하고 평문을 남기지 않는다. 프론트도 <b>저장하지 않는다</b> —
   * 이전 입력값 자동 채움(R6)은 반·이름·호수만 기억한다.
   */
  cancelPassword: string
}

/** `POST /registrations/cancel` 요청 바디 */
export interface CancelRequest {
  className: string
  studentName: string
  roomNumber: string
  /** 등록할 때 정한 비밀번호(숫자 4자리) */
  password: string
}

/** `POST /registrations/cancel` 응답 */
export interface CancelResponse {
  date: string
  /** `2026-08-05T20:15:00` (KST LocalDateTime) */
  cancelledAt: string
  /** 이미 취소되어 있던 요청이면 true */
  alreadyCancelled: boolean
  /** 화면에 그대로 띄울 안내 문구 */
  message: string
}

/** `POST /registrations` 응답 */
export interface RegistrationResponse {
  id: number
  registrationDate: string
  className: string
  studentName: string
  roomNumber: string
  /** `2026-08-05T21:03:11` (KST LocalDateTime) */
  registeredAt: string
  /** 이미 등록되어 있던 경우 true */
  duplicate: boolean
  returnTime: string
}

/** 조회 페이지 명단 한 줄 */
export interface LookupItem {
  no: number
  className: string
  studentName: string
  roomNumber: string
  registeredAt: string
}

/** 반별 인원 */
export interface ClassCount {
  className: string
  count: number
}

/** 호수별 인원 */
export interface RoomCount {
  roomNumber: string
  count: number
}

/**
 * `GET /lookup?date=&token=` 응답.
 *
 * 대사(검증) 결과와 방문/등록 통계는 화면에 노출하지 않기로 해 응답에서 빠졌다.
 * 서버는 그대로 기록만 남긴다.
 */
export interface LookupResponse {
  date: string
  generatedAt: string
  totalCount: number
  returnTime: string
  curfewTime: string
  items: LookupItem[]
  byClass: ClassCount[]
  byRoom: RoomCount[]
}

/** 서버 에러 응답 바디 (`common.error.ErrorResponse`) */
export interface ErrorResponseBody {
  code: string
  message: string
  path?: string
  timestamp?: string
  errors?: Array<{ field: string; message: string }>
}
