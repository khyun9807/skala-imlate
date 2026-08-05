-- =====================================================================
-- imlate 초기 스키마 (SPEC §9)
--   - 문자셋: utf8mb4 / utf8mb4_unicode_ci (한글 + 이모지 안전)
--   - 엔진: InnoDB
--   - 시각 컬럼은 마이크로초까지 저장하기 위해 DATETIME(6) 사용
-- =====================================================================

-- ---------------------------------------------------------------------
-- 야간(23:30) 복귀 등록
--   (registration_date, class_name, student_name, room_number) 유니크로 중복 등록을 막고,
--   wal_id 유니크로 Redis WAL 항목과 1:1 대응시켜 누락/중복 복구를 가능하게 한다.
-- ---------------------------------------------------------------------
CREATE TABLE return_registration (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '등록 PK',
    registration_date DATE         NOT NULL COMMENT '복귀 대상일(KST 등록 시점의 날짜)',
    class_name        VARCHAR(20)  NOT NULL COMMENT '반',
    student_name      VARCHAR(20)  NOT NULL COMMENT '교육생 이름',
    room_number       VARCHAR(20)  NOT NULL COMMENT '기숙사 호수',
    wal_id            CHAR(36)     NOT NULL COMMENT 'Redis WAL 항목 식별자(UUID)',
    registered_at     DATETIME(6)  NOT NULL COMMENT '등록 요청 시각(KST)',
    created_at        DATETIME(6)  NOT NULL COMMENT 'DB 저장 시각(KST)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_registration_person (registration_date, class_name, student_name, room_number),
    UNIQUE KEY uk_return_registration_wal_id (wal_id),
    KEY idx_return_registration_date (registration_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '야간 복귀 등록';

-- ---------------------------------------------------------------------
-- 사감 발송 이력 (문자/이메일)
--   재시도·중복 발송 방지 판정에 사용한다.
-- ---------------------------------------------------------------------
CREATE TABLE notification_dispatch (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '발송 이력 PK',
    dispatch_date       DATE          NOT NULL COMMENT '발송 대상일',
    channel             VARCHAR(10)   NOT NULL COMMENT '채널: SMS | EMAIL',
    recipient_name      VARCHAR(50)   NULL COMMENT '수신 사감 이름',
    recipient           VARCHAR(200)  NOT NULL COMMENT '수신처(전화번호 또는 이메일)',
    status              VARCHAR(20)   NOT NULL COMMENT '결과: SUCCESS | FAILED | SKIPPED',
    attempt             INT           NOT NULL DEFAULT 0 COMMENT '시도 횟수',
    target_count        INT           NOT NULL DEFAULT 0 COMMENT '발송 시점 명단 인원 수',
    provider_message_id VARCHAR(200)  NULL COMMENT '외부 제공자 메시지 ID',
    error_message       VARCHAR(1000) NULL COMMENT '실패 사유',
    sent_at             DATETIME(6)   NOT NULL COMMENT '발송 시각(KST)',
    PRIMARY KEY (id),
    KEY idx_notification_dispatch_date (dispatch_date),
    KEY idx_notification_dispatch_date_status (dispatch_date, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '사감 발송 이력';

-- ---------------------------------------------------------------------
-- 일별 통계 스냅샷 (R15)
--   Redis 카운터를 매일 영속화한다. Redis 유실 시 조회 fallback 으로 사용.
-- ---------------------------------------------------------------------
CREATE TABLE daily_stat (
    stat_date       DATE        NOT NULL COMMENT '통계 일자(PK)',
    page_views      BIGINT      NOT NULL DEFAULT 0 COMMENT '페이지뷰(API 요청 수)',
    unique_visitors BIGINT      NOT NULL DEFAULT 0 COMMENT '순 방문자 수',
    registrations   BIGINT      NOT NULL DEFAULT 0 COMMENT '등록 건수',
    updated_at      DATETIME(6) NOT NULL COMMENT '갱신 시각(KST)',
    PRIMARY KEY (stat_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '일별 방문/등록 통계';
