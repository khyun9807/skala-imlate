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
