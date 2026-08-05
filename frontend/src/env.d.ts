/// <reference types="vite/client" />

/** Vite 환경변수 타입 선언. */
interface ImportMetaEnv {
  /** API 베이스 경로. 미설정 시 `/api/v1` 을 사용한다. */
  readonly VITE_API_BASE?: string
}
