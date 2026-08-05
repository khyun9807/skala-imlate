package com.skala.imlate.registration.web.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.skala.imlate.registration.service.CancelResult;

/**
 * 등록 취소 응답.
 *
 * <p>반·이름·호수를 되돌려주지 않는다. 취소한 사람은 자기가 무엇을 입력했는지 이미 알고 있고,
 * 응답에 담으면 그 화면을 어깨너머로 보는 사람에게까지 남의 정보가 노출될 이유가 생긴다.
 *
 * @param date             복귀 대상일
 * @param cancelledAt      취소 시각(KST)
 * @param alreadyCancelled 이미 취소되어 있던 요청이면 true(같은 화면에서 두 번 눌렀을 때)
 * @param message          화면에 그대로 띄울 안내 문구
 */
public record CancelResponse(LocalDate date, LocalDateTime cancelledAt,
                             boolean alreadyCancelled, String message) {

    /**
     * 서비스 결과를 응답으로 옮긴다.
     *
     * @param result 취소 결과
     * @return 응답 본문
     */
    public static CancelResponse of(CancelResult result) {
        String message = result.alreadyCancelled()
                ? "이미 취소된 등록입니다. 오늘 밤 명단에 포함되지 않습니다."
                : "취소되었습니다. 오늘 밤 명단에서 빠졌습니다.";
        return new CancelResponse(result.date(), result.cancelledAt(), result.alreadyCancelled(), message);
    }
}
