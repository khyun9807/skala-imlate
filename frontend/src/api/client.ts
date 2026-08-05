/**
 * 백엔드 API 호출 래퍼.
 *
 * - 베이스 경로: `import.meta.env.VITE_API_BASE ?? '/api/v1'`
 * - 모든 요청에 방문자 식별 헤더 `X-Visitor-Id` 를 붙인다(통계 모듈에서 사용).
 * - 타임아웃 10초(AbortController), 네트워크 오류·서버 오류 모두 `ApiError` 로 정규화한다.
 */

import type {
  LookupResponse,
  RegistrationRequest,
  RegistrationResponse,
  RegistrationWindow,
} from './types'
import { readItem, writeItem } from '../utils/storage'

/** 요청 타임아웃(ms) */
const REQUEST_TIMEOUT_MS = 10_000

/** 방문자 식별자 저장 키 */
const VISITOR_ID_KEY = 'imlate.visitorId'

/** 방문자 식별 헤더 이름 (SPEC §7.1) */
export const VISITOR_ID_HEADER = 'X-Visitor-Id'

const BASE_URL: string = normalizeBase(import.meta.env.VITE_API_BASE ?? '/api/v1')

/** 에러 코드별 기본 사용자 안내 문구. 서버 메시지가 없을 때 사용한다. */
const FALLBACK_MESSAGES: Record<string, string | undefined> = {
  VALIDATION_FAILED: '입력값을 다시 확인해 주세요.',
  REGISTRATION_CLOSED: '오늘 등록은 마감되었습니다. (마감 22:00)',
  REGISTRATION_NOT_OPEN: '아직 등록 가능한 시간이 아닙니다.',
  UNAUTHORIZED: '인증 정보가 필요합니다.',
  FORBIDDEN: '조회 권한이 없습니다. 링크가 만료되었을 수 있습니다.',
  NOT_FOUND: '요청하신 정보를 찾을 수 없습니다.',
  RATE_LIMITED: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  EXTERNAL_API_ERROR: '외부 서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
  INTERNAL_ERROR: '서버에 일시적인 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  NETWORK_ERROR: '네트워크에 연결할 수 없습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.',
  TIMEOUT: '서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
  INVALID_RESPONSE: '서버 응답을 이해하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

/**
 * API 오류를 표현하는 예외.
 * `status === 0` 이면 네트워크 오류/타임아웃이다.
 */
export class ApiError extends Error {
  /** 서버 `ErrorResponse.code` 또는 클라이언트 생성 코드(NETWORK_ERROR/TIMEOUT) */
  readonly code: string
  /** HTTP 상태 코드. 네트워크 오류는 0 */
  readonly status: number
  /** 필드명 → 오류 메시지 */
  readonly fieldErrors: Record<string, string>
  /** 429 응답의 `Retry-After`(초). 없으면 null */
  readonly retryAfterSeconds: number | null

  constructor(
    code: string,
    status: number,
    message: string,
    fieldErrors: Record<string, string> = {},
    retryAfterSeconds: number | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
    Object.setPrototypeOf(this, ApiError.prototype)
  }

  /** 네트워크 단절/타임아웃 여부 */
  get isNetworkError(): boolean {
    return this.status === 0
  }
}

/** 알 수 없는 예외를 사용자 문구가 있는 ApiError 로 변환한다. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  return new ApiError('INTERNAL_ERROR', 0, FALLBACK_MESSAGES.INTERNAL_ERROR ?? '오류가 발생했습니다.')
}

/** 방문자 식별자를 반환한다(없으면 생성 후 저장). */
export function getVisitorId(): string {
  const stored = readItem(VISITOR_ID_KEY)
  if (stored && stored.length >= 8) {
    return stored
  }
  const generated = createUuid()
  writeItem(VISITOR_ID_KEY, generated)
  return generated
}

/** API 요청 옵션 */
interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  query?: Record<string, string | number | undefined | null>
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const url = buildUrl(path, options.query)
  const headers: Record<string, string> = {
    Accept: 'application/json',
    [VISITOR_ID_HEADER]: getVisitorId(),
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

  let response: Response
  try {
    response = await fetch(url, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: 'same-origin',
      signal: controller.signal,
    })
  } catch {
    if (controller.signal.aborted) {
      throw new ApiError('TIMEOUT', 0, FALLBACK_MESSAGES.TIMEOUT ?? '요청이 지연되었습니다.')
    }
    throw new ApiError('NETWORK_ERROR', 0, FALLBACK_MESSAGES.NETWORK_ERROR ?? '네트워크 오류입니다.')
  } finally {
    clearTimeout(timer)
  }

  const rawText = await response.text().catch(() => '')
  const payload = parseJson(rawText)

  if (!response.ok) {
    throw buildApiError(response, payload)
  }

  if (payload === null && rawText.trim().length > 0) {
    throw new ApiError('INVALID_RESPONSE', response.status, FALLBACK_MESSAGES.INVALID_RESPONSE ?? '')
  }
  return payload as T
}

/** 등록 창(서버 시간 포함) 조회 */
export function fetchRegistrationWindow(): Promise<RegistrationWindow> {
  return request<RegistrationWindow>('/registrations/window')
}

/** 복귀 등록 */
export function createRegistration(payload: RegistrationRequest): Promise<RegistrationResponse> {
  return request<RegistrationResponse>('/registrations', { method: 'POST', body: payload })
}

/** 사감용 조회 페이지 데이터 */
export function fetchLookup(date: string | null, token: string): Promise<LookupResponse> {
  return request<LookupResponse>('/lookup', { query: { date: date ?? undefined, token } })
}

// ---------------------------------------------------------------- 내부 유틸

function normalizeBase(value: string): string {
  const trimmed = value.trim()
  if (trimmed.length === 0) {
    return '/api/v1'
  }
  return trimmed.replace(/\/+$/, '')
}

function buildUrl(path: string, query?: Record<string, string | number | undefined | null>): string {
  const suffix = path.startsWith('/') ? path : `/${path}`
  const params: string[] = []
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') {
        continue
      }
      params.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    }
  }
  return params.length > 0 ? `${BASE_URL}${suffix}?${params.join('&')}` : `${BASE_URL}${suffix}`
}

function parseJson(text: string): unknown {
  if (!text || text.trim().length === 0) {
    return null
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function buildApiError(response: Response, payload: unknown): ApiError {
  const body = isRecord(payload) ? payload : null
  const code = typeof body?.code === 'string' && body.code.length > 0 ? body.code : codeFromStatus(response.status)

  const fieldErrors: Record<string, string> = {}
  const errors = body?.errors
  if (Array.isArray(errors)) {
    for (const entry of errors) {
      if (isRecord(entry) && typeof entry.field === 'string' && typeof entry.message === 'string') {
        fieldErrors[entry.field] = entry.message
      }
    }
  }

  const retryAfterRaw = Number(response.headers.get('Retry-After'))
  const retryAfterSeconds = Number.isFinite(retryAfterRaw) && retryAfterRaw > 0 ? Math.ceil(retryAfterRaw) : null

  return new ApiError(code, response.status, resolveMessage(code, body?.message, response.status), fieldErrors, retryAfterSeconds)
}

function codeFromStatus(status: number): string {
  switch (status) {
    case 400:
      return 'VALIDATION_FAILED'
    case 401:
      return 'UNAUTHORIZED'
    case 403:
      return 'FORBIDDEN'
    case 404:
      return 'NOT_FOUND'
    case 409:
      return 'REGISTRATION_CLOSED'
    case 429:
      return 'RATE_LIMITED'
    case 502:
    case 503:
    case 504:
      return 'EXTERNAL_API_ERROR'
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : `HTTP_${status}`
  }
}

function resolveMessage(code: string, serverMessage: unknown, status: number): string {
  if (typeof serverMessage === 'string' && serverMessage.trim().length > 0) {
    return serverMessage.trim()
  }
  const mapped = FALLBACK_MESSAGES[code]
  if (mapped) {
    return mapped
  }
  if (status >= 500) {
    return FALLBACK_MESSAGES.INTERNAL_ERROR ?? '서버 오류가 발생했습니다.'
  }
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

/** crypto.randomUUID 미지원 환경(구형 사파리, http 접속 등)까지 고려한 UUID 생성 */
function createUuid(): string {
  const cryptoObj = globalThis.crypto as Crypto | undefined

  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
    try {
      return cryptoObj.randomUUID()
    } catch {
      // 아래 폴백으로 진행
    }
  }

  if (cryptoObj && typeof cryptoObj.getRandomValues === 'function') {
    try {
      const bytes = cryptoObj.getRandomValues(new Uint8Array(16))
      bytes[6] = (bytes[6] & 0x0f) | 0x40
      bytes[8] = (bytes[8] & 0x3f) | 0x80
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
    } catch {
      // 아래 폴백으로 진행
    }
  }

  // 최후 폴백: Math.random 기반 (통계용 식별자이므로 충돌 위험만 낮으면 충분)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (marker) => {
    const random = (Math.random() * 16) | 0
    const value = marker === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}
