package com.skala.imlate.registration.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.error.GlobalExceptionHandler;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.service.RegistrationCommand;
import com.skala.imlate.registration.service.RegistrationResult;
import com.skala.imlate.registration.service.RegistrationService;
import com.skala.imlate.registration.service.RegistrationWindow;
import com.skala.imlate.registration.service.RegistrationWindowPolicy;
import com.skala.imlate.support.TestFixtures;

/**
 * {@link RegistrationController} 웹 계층 테스트.
 *
 * <p>인터셉터(rate limit·통계)나 시큐리티 없이 컨트롤러 + {@link GlobalExceptionHandler} 만
 * standalone MockMvc 로 띄워 상태 코드와 응답 본문을 검증한다(SPEC §5.5).
 */
@DisplayName("등록 API(RegistrationController)")
class RegistrationControllerTest {

    private static final String PATH = "/api/v1/registrations";

    private RegistrationService registrationService;
    private RegistrationWindowPolicy windowPolicy;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registrationService = mock(RegistrationService.class);
        windowPolicy = mock(RegistrationWindowPolicy.class);
        when(windowPolicy.returnTime()).thenReturn(LocalTime.of(23, 30));
        when(windowPolicy.curfewTime()).thenReturn(LocalTime.of(22, 30));
        when(windowPolicy.targetDate()).thenReturn(TestFixtures.DATE);

        Clock clock = TestFixtures.clockAt(LocalTime.of(21, 3, 11));
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new RegistrationController(registrationService, windowPolicy))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    private static String body(String className, String studentName, String roomNumber) {
        return """
                {"className":"%s","studentName":"%s","roomNumber":"%s"}"""
                .formatted(className, studentName, roomNumber);
    }

    @Test
    @DisplayName("신규 등록은 201 CREATED 와 duplicate=false 를 반환한다")
    void 신규_등록은_201() throws Exception {
        ReturnRegistration saved = TestFixtures.registration(12L, "1반", "홍길동", "302");
        when(registrationService.register(any(RegistrationCommand.class)))
                .thenReturn(new RegistrationResult(saved, false));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("1반", "홍길동", "302")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.registrationDate").value("2026-08-05"))
                .andExpect(jsonPath("$.className").value("1반"))
                .andExpect(jsonPath("$.studentName").value("홍길동"))
                .andExpect(jsonPath("$.roomNumber").value("302"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.returnTime").value("23:30"));
    }

    @Test
    @DisplayName("이미 등록된 경우 200 OK 와 duplicate=true 를 반환한다")
    void 중복_등록은_200() throws Exception {
        ReturnRegistration saved = TestFixtures.registration(12L, "1반", "홍길동", "302");
        when(registrationService.register(any(RegistrationCommand.class)))
                .thenReturn(new RegistrationResult(saved, true));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("1반", "홍길동", "302")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    @DisplayName("등록 마감(22:00) 후에는 409 CONFLICT + REGISTRATION_CLOSED 를 반환한다")
    void 마감_후에는_409() throws Exception {
        when(registrationService.register(any(RegistrationCommand.class)))
                .thenThrow(ApiException.of(ErrorCode.REGISTRATION_CLOSED, "등록 마감 시간(22:00)이 지났습니다."));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("1반", "홍길동", "302")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"))
                .andExpect(jsonPath("$.message").value("등록 마감 시간(22:00)이 지났습니다."))
                .andExpect(jsonPath("$.path").value(PATH));
    }

    @Test
    @DisplayName("필수 값이 비어 있으면 400 BAD_REQUEST + VALIDATION_FAILED 를 반환한다")
    void 빈_값은_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("", "홍길동", "302")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("className"));

        verify(registrationService, never()).register(any(RegistrationCommand.class));
    }

    @Test
    @DisplayName("허용되지 않은 문자가 들어오면 400 BAD_REQUEST 로 거부한다")
    void 허용되지_않은_문자는_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("1반", "<script>", "302")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(registrationService, never()).register(any(RegistrationCommand.class));
    }

    @Test
    @DisplayName("20자를 넘는 입력은 400 BAD_REQUEST 로 거부한다")
    void 길이_초과는_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(body("1반", "가".repeat(21), "302")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("본문 JSON 이 깨져 있으면 400 BAD_REQUEST 로 응답한다")
    void 깨진_JSON_은_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"className\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /window 는 서버 시각 기준 등록 창 상태를 반환한다")
    void 등록창_상태를_반환한다() throws Exception {
        OffsetDateTime serverTime =
                ZonedDateTime.of(TestFixtures.DATE, LocalTime.of(21, 3, 11), TestFixtures.KST).toOffsetDateTime();
        OffsetDateTime opensAt =
                ZonedDateTime.of(TestFixtures.DATE, LocalTime.MIDNIGHT, TestFixtures.KST).toOffsetDateTime();
        OffsetDateTime closesAt =
                ZonedDateTime.of(TestFixtures.DATE, LocalTime.of(22, 0), TestFixtures.KST).toOffsetDateTime();
        when(windowPolicy.describe()).thenReturn(new RegistrationWindow(TestFixtures.DATE, true,
                serverTime, opensAt, closesAt, LocalTime.of(23, 30), LocalTime.of(22, 30), 3409L));

        mockMvc.perform(get(PATH + "/window"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-05"))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.secondsUntilClose").value(3409))
                .andExpect(jsonPath("$.returnTime").value("23:30"))
                .andExpect(jsonPath("$.curfewTime").value("22:30"));
    }

    @Test
    @DisplayName("GET /summary 는 PII 없이 날짜·인원 수·등록 가능 여부만 반환한다")
    void 등록_현황_요약을_반환한다() throws Exception {
        when(registrationService.countByDate(TestFixtures.DATE)).thenReturn(12L);
        when(windowPolicy.isOpen()).thenReturn(true);

        mockMvc.perform(get(PATH + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-05"))
                .andExpect(jsonPath("$.count").value(12))
                .andExpect(jsonPath("$.open").value(true));
    }
}
