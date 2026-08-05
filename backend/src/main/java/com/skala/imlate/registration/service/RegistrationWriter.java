package com.skala.imlate.registration.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.event.RegistrationCreatedEvent;

/**
 * 등록 INSERT 전용 트랜잭션 경계 컴포넌트.
 *
 * <p><b>왜 별도 클래스인가</b> — 유니크 충돌({@code DataIntegrityViolationException})이 나면 그 트랜잭션은
 * rollback-only 로 표시되어, <u>같은 트랜잭션 안에서 기존 레코드를 다시 조회하면 커밋 시점에
 * {@code UnexpectedRollbackException} 이 발생</u>한다. 그래서 INSERT 만 독립 트랜잭션
 * ({@link Propagation#REQUIRES_NEW})으로 떼어내고, 충돌 처리·재조회는 호출자가 트랜잭션 밖에서 수행한다.
 *
 * <p>{@link RegistrationCreatedEvent} 를 <b>이 트랜잭션 내부</b>에서 발행하므로 stats 모듈이
 * {@code @EventListener} / {@code @TransactionalEventListener(AFTER_COMMIT)} 중 무엇을 써도 수신된다.
 */
@Component
public class RegistrationWriter {

    private static final Logger log = LoggerFactory.getLogger(RegistrationWriter.class);

    private final ReturnRegistrationRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param repository     등록 저장소
     * @param eventPublisher 도메인 이벤트 발행기
     */
    public RegistrationWriter(ReturnRegistrationRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 신규 등록을 독립 트랜잭션으로 저장하고 {@link RegistrationCreatedEvent} 를 발행한다.
     *
     * <p>{@code saveAndFlush} 로 INSERT 를 즉시 밀어내 유니크 충돌이 이 메서드 밖으로
     * {@code DataIntegrityViolationException} 형태로 전파되도록 한다.
     *
     * @param registration 저장할 새 엔티티
     * @return 저장된 엔티티(PK 채워짐)
     * @throws org.springframework.dao.DataIntegrityViolationException 유니크 제약 위반 시
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnRegistration insert(ReturnRegistration registration) {
        ReturnRegistration saved = repository.saveAndFlush(registration);
        eventPublisher.publishEvent(new RegistrationCreatedEvent(saved.getRegistrationDate(), saved.getClassName()));
        log.debug("Registration inserted id={} walId={}", saved.getId(), saved.getWalId());
        return saved;
    }

    /**
     * WAL 에만 남아 있는 등록을 DB 로 복구한다(대사 전용 경로).
     *
     * <p><b>왜 {@link #insert} 를 그대로 쓰지 않는가</b> — 복구는 "새 등록"이 아니라 유실된 쓰기의 되돌림이다.
     * 무조건 이벤트를 발행하면 통계의 등록 수가 중복 집계된다. 그래서 최초 등록 시점에 이미 집계되었는지를
     * WAL 상태로 판별해 호출자가 결정하도록 한다.
     *
     * @param registration           복구할 엔티티
     * @param countAsNewRegistration 통계에 새 등록으로 반영할지 여부.
     *                               WAL 상태가 {@code PENDING}/{@code FAILED} 였다면 최초 등록 때 DB INSERT 가
     *                               실패해 통계에도 잡히지 않았으므로 {@code true},
     *                               {@code COMMITTED} 였다면 이미 집계된 뒤 행이 유실된 것이므로 {@code false}.
     * @return 저장된 엔티티(PK 채워짐)
     * @throws org.springframework.dao.DataIntegrityViolationException 유니크 제약 위반 시
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnRegistration recover(ReturnRegistration registration, boolean countAsNewRegistration) {
        ReturnRegistration saved = repository.saveAndFlush(registration);
        if (countAsNewRegistration) {
            eventPublisher.publishEvent(new RegistrationCreatedEvent(saved.getRegistrationDate(), saved.getClassName()));
        }
        log.debug("Registration recovered id={} walId={} counted={}",
                saved.getId(), saved.getWalId(), countAsNewRegistration);
        return saved;
    }

    /**
     * 등록을 취소 상태로 바꾼다(소프트 삭제).
     *
     * <p>서비스가 트랜잭션 밖에서 읽어 둔 엔티티는 준영속(detached)이므로 그대로 바꿔도 반영되지 않는다.
     * 그래서 이 트랜잭션 안에서 PK 로 다시 읽어 변경 감지(dirty checking)에 태운다.
     *
     * <p><b>통계는 건드리지 않는다.</b> {@code imlate:stats:reg:*} 는 "그날 등록한 적이 있는 사람 수"이며,
     * 취소했다고 해서 등록한 적이 없어지는 것은 아니다. 사감이 보는 명단 인원은 통계가 아니라
     * 실제 명단 조회에서 나오므로 취소분이 섞이지 않는다.
     *
     * @param id          등록 PK
     * @param cancelledAt 취소 시각(KST)
     * @return 이번 호출로 실제 취소되었으면 true, 이미 취소 상태였으면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancel(Long id, LocalDateTime cancelledAt) {
        ReturnRegistration target = repository.findById(id).orElse(null);
        if (target == null) {
            // 취소 검증과 이 호출 사이에 행이 사라진 경우(운영자 수동 삭제 등). 취소 목적은 이미 달성됐다.
            log.warn("Cancel target disappeared before update id={}", id);
            return false;
        }
        boolean changed = target.cancel(cancelledAt);
        repository.saveAndFlush(target);
        log.info("Registration cancelled id={} date={} changed={}", id, target.getRegistrationDate(), changed);
        return changed;
    }

    /**
     * 취소했던 등록을 다시 유효하게 되돌린다(취소 후 재등록).
     *
     * <p>유니크 제약 {@code (일자, 반, 이름, 호수)} 이 취소된 행에도 그대로 걸려 있어 새 행을 만들 수 없다.
     * 그래서 재등록은 INSERT 가 아니라 기존 행 되살리기다.
     *
     * <p><b>{@link RegistrationCreatedEvent} 를 발행하지 않는다.</b> 이 사람은 최초 등록 때 이미 집계되었고,
     * 취소로 통계를 깎지도 않았다. 되살릴 때 다시 올리면 취소→재등록을 반복하는 것만으로
     * 통계를 부풀릴 수 있다.
     *
     * @param id                 등록 PK
     * @param cancelPasswordHash 이번 등록에서 새로 받은 비밀번호 해시
     * @param registeredAt       재등록 요청 시각(KST)
     * @return 되살아난 엔티티. 대상 행이 없으면 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnRegistration reactivate(Long id, String cancelPasswordHash,
                                         LocalDateTime registeredAt) {
        ReturnRegistration target = repository.findById(id).orElse(null);
        if (target == null) {
            log.warn("Reactivate target disappeared before update id={}", id);
            return null;
        }
        target.reactivate(cancelPasswordHash, registeredAt);
        ReturnRegistration saved = repository.saveAndFlush(target);
        log.info("Registration reactivated id={} date={}", id, saved.getRegistrationDate());
        return saved;
    }
}
