package com.haoforyou.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.CalendarData;
import com.haoforyou.dto.EventSummary;
import com.haoforyou.dto.GoogleCalendarApiRequest;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.CalendarApiService;
import com.haoforyou.service.GoogleAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link CalendarApiController}.
 *
 * <p>Uses {@code @WebMvcTest} to load only the web layer (controller + MVC
 * infrastructure) and mocks {@link GoogleAuthService} / {@link CalendarApiService}
 * so no real Google Calendar API calls or database access are made.</p>
 *
 * <p>參考 {@code docs/specs/2026-06-15-google-api-endpoints-design.md}：
 * 錯誤碼（GOOGLE_NOT_LINKED / TOKEN_REFRESH_FAILED / MISSING_REQUIRED_FIELD / GOOGLE_API_ERROR）、
 * query 的日期區間／單一日期／未指定日期分支、create 的 all_day 分支等。</p>
 */
@WebMvcTest(CalendarApiController.class)
class CalendarApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GoogleAuthService googleAuthService;

    @MockBean
    private CalendarApiService calendarApiService;

    private static final String LINE_USER_ID = "Uabc1234567890";

    private GoogleToken sampleToken() {
        return new GoogleToken(LINE_USER_ID, "access-token", "refresh-token", null, "user@gmail.com");
    }

    private GoogleCalendarApiRequest baseRequest() {
        GoogleCalendarApiRequest req = new GoogleCalendarApiRequest();
        req.setLineUserId(LINE_USER_ID);
        return req;
    }

    private String json(GoogleCalendarApiRequest req) throws Exception {
        return objectMapper.writeValueAsString(req);
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/calendar/query
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/calendar/query")
    class Query {

        @Test
        @DisplayName("尚未連結 Google 帳號時回傳 401 GOOGLE_NOT_LINKED，且不呼叫 CalendarApiService")
        void query_notLinked_returns401() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.empty());

            GoogleCalendarApiRequest req = baseRequest();

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("GOOGLE_NOT_LINKED"))
                .andExpect(jsonPath("$.error.message").value("此用戶尚未連結 Google 帳號"));

            verify(calendarApiService, never()).query(any(), any());
        }

        @Test
        @DisplayName("日期區間查詢（date + date_to）：happy path，回傳 200 與事件列表，且 request 正確帶入 service")
        void query_dateRange_happyPath_returnsEvents() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            EventSummary event = new EventSummary(
                "event_1", "開會", "2026-10-01T14:00:00+08:00", "2026-10-01T15:00:00+08:00",
                false, "會議說明", null);
            when(calendarApiService.query(eq(token), any())).thenReturn(new CalendarData(List.of(event)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");
            req.setDateTo("2026-10-05");

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.events[0].event_id").value("event_1"))
                .andExpect(jsonPath("$.data.events[0].title").value("開會"))
                .andExpect(jsonPath("$.data.events[0].all_day").value(false))
                .andExpect(jsonPath("$.data.events[0].status").doesNotExist());

            ArgumentCaptorHelper.QueryCapture capture = ArgumentCaptorHelper.captureQuery(calendarApiService, token);
            org.junit.jupiter.api.Assertions.assertEquals("2026-10-01", capture.request().getDate());
            org.junit.jupiter.api.Assertions.assertEquals("2026-10-05", capture.request().getDateTo());
        }

        @Test
        @DisplayName("只帶單一日期（date，無 date_to）：request 應原樣帶入 service，date_to 為 null")
        void query_singleDate_passesThroughWithoutDateTo() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(calendarApiService.query(eq(token), any())).thenReturn(new CalendarData(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.events").isEmpty());

            ArgumentCaptorHelper.QueryCapture capture = ArgumentCaptorHelper.captureQuery(calendarApiService, token);
            org.junit.jupiter.api.Assertions.assertEquals("2026-10-01", capture.request().getDate());
            org.junit.jupiter.api.Assertions.assertNull(capture.request().getDateTo());
        }

        @Test
        @DisplayName("都沒指定日期（date、date_to 皆為空）：request 應原樣帶入 service，兩者皆為 null")
        void query_noDate_passesThroughAsNull() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(calendarApiService.query(eq(token), any())).thenReturn(new CalendarData(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setKeyword("會議");
            req.setLimit(10);

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").isEmpty());

            ArgumentCaptorHelper.QueryCapture capture = ArgumentCaptorHelper.captureQuery(calendarApiService, token);
            org.junit.jupiter.api.Assertions.assertNull(capture.request().getDate());
            org.junit.jupiter.api.Assertions.assertNull(capture.request().getDateTo());
            org.junit.jupiter.api.Assertions.assertEquals("會議", capture.request().getKeyword());
            org.junit.jupiter.api.Assertions.assertEquals(10, capture.request().getLimit());
        }

        @Test
        @DisplayName("CalendarApiService 拋出 TokenResponseException 時回傳 401 TOKEN_REFRESH_FAILED")
        void query_tokenResponseException_returns401() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            TokenResponseException tokenException = mock(TokenResponseException.class);
            when(calendarApiService.query(eq(token), any())).thenThrow(tokenException);

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_REFRESH_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Google 授權已失效，請重新連結帳號"));
        }

        @Test
        @DisplayName("CalendarApiService 拋出一般例外時回傳 500 GOOGLE_API_ERROR，並帶上例外訊息")
        void query_genericException_returns500() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(calendarApiService.query(eq(token), any())).thenThrow(new RuntimeException("Calendar API 逾時"));

            GoogleCalendarApiRequest req = baseRequest();

            mockMvc.perform(post("/google/calendar/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("GOOGLE_API_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Google API 呼叫失敗：Calendar API 逾時"));
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/calendar/create
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/calendar/create")
    class Create {

        @Test
        @DisplayName("尚未連結 Google 帳號時回傳 401 GOOGLE_NOT_LINKED（優先於欄位驗證），且不呼叫 CalendarApiService")
        void create_notLinked_returns401_precedesFieldValidation() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.empty());

            // 故意連 title/date 都不帶，驗證「未連結」檢查優先於「必填欄位」檢查
            GoogleCalendarApiRequest req = baseRequest();

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_NOT_LINKED"));

            verify(calendarApiService, never()).create(any(), any());
        }

        @Test
        @DisplayName("缺少 title 時回傳 400 MISSING_REQUIRED_FIELD")
        void create_missingTitle_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.message").value("title 和 date 為必填欄位"));

            verify(calendarApiService, never()).create(any(), any());
        }

        @Test
        @DisplayName("缺少 date 時回傳 400 MISSING_REQUIRED_FIELD")
        void create_missingDate_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setStartTime("09:00:00");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.message").value("title 和 date 為必填欄位"));
        }

        @Test
        @DisplayName("title 為空白字串時視同缺少必填欄位，回傳 400")
        void create_blankTitle_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("   ");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        }

        @Test
        @DisplayName("非全天事件（all_day 未帶）且缺少 start_time 時回傳 400 MISSING_REQUIRED_FIELD")
        void create_notAllDay_missingStartTime_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            // all_day 未帶（null），start_time 也未帶

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.message").value("非全天事件需提供 start_time"));

            verify(calendarApiService, never()).create(any(), any());
        }

        @Test
        @DisplayName("all_day 明確為 false 且缺少 start_time 時同樣回傳 400")
        void create_allDayFalse_missingStartTime_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setAllDay(false);

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.message").value("非全天事件需提供 start_time"));
        }

        @Test
        @DisplayName("非全天事件且提供 start_time：happy path，回傳 200 並帶入建立的事件")
        void create_notAllDay_withStartTime_happyPath_returns200() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            EventSummary created = new EventSummary(
                "event_new", "開會", "2026-10-01T09:00:00+08:00", "2026-10-01T10:00:00+08:00",
                false, "會議說明", "created");
            when(calendarApiService.create(eq(token), any())).thenReturn(new CalendarData(List.of(created)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");
            req.setDescription("會議說明");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.events[0].event_id").value("event_new"))
                .andExpect(jsonPath("$.data.events[0].status").value("created"))
                .andExpect(jsonPath("$.data.events[0].all_day").value(false));

            verify(calendarApiService, times(1)).create(eq(token), any());
        }

        @Test
        @DisplayName("all_day 為 true 時即使沒有 start_time 也視為 happy path，回傳 200")
        void create_allDayTrue_withoutStartTime_happyPath_returns200() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            EventSummary created = new EventSummary(
                "event_allday", "國定假日", "2026-10-01", "2026-10-01",
                true, "", "created");
            when(calendarApiService.create(eq(token), any())).thenReturn(new CalendarData(List.of(created)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("國定假日");
            req.setDate("2026-10-01");
            req.setAllDay(true);
            // 故意不帶 start_time

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.events[0].all_day").value(true))
                .andExpect(jsonPath("$.data.events[0].status").value("created"));

            verify(calendarApiService, times(1)).create(eq(token), any());
        }

        @Test
        @DisplayName("CalendarApiService 拋出 TokenResponseException 時回傳 401 TOKEN_REFRESH_FAILED")
        void create_tokenResponseException_returns401() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            TokenResponseException tokenException = mock(TokenResponseException.class);
            when(calendarApiService.create(eq(token), any())).thenThrow(tokenException);

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REFRESH_FAILED"));
        }

        @Test
        @DisplayName("CalendarApiService 拋出一般例外時回傳 500 GOOGLE_API_ERROR")
        void create_genericException_returns500() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(calendarApiService.create(eq(token), any())).thenThrow(new RuntimeException("insert failed"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");

            mockMvc.perform(post("/google/calendar/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_API_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Google API 呼叫失敗：insert failed"));
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/calendar/modify
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/calendar/modify")
    class Modify {

        @Test
        @DisplayName("尚未連結 Google 帳號時回傳 401 GOOGLE_NOT_LINKED（優先於缺少 event_id 檢查）")
        void modify_notLinked_returns401_precedesFieldValidation() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.empty());

            GoogleCalendarApiRequest req = baseRequest(); // 連 event_id 都沒帶

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_NOT_LINKED"));

            verify(calendarApiService, never()).modify(any(), any());
        }

        @Test
        @DisplayName("缺少 event_id 時回傳 400 MISSING_REQUIRED_FIELD")
        void modify_missingEventId_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("新標題");

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.message").value("event_id 為必填欄位"));

            verify(calendarApiService, never()).modify(any(), any());
        }

        @Test
        @DisplayName("event_id 為空白字串時視同缺少必填欄位，回傳 400")
        void modify_blankEventId_returns400() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(sampleToken()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("   ");

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        }

        @Test
        @DisplayName("happy path：帶入 event_id 與欲更新欄位，回傳 200 並帶入更新後的事件")
        void modify_happyPath_returns200() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            EventSummary updated = new EventSummary(
                "event_123", "新標題", "2026-10-02T15:00:00+08:00", "2026-10-02T16:30:00+08:00",
                false, "", "updated");
            when(calendarApiService.modify(eq(token), any())).thenReturn(new CalendarData(List.of(updated)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setTitle("新標題");
            req.setDate("2026-10-02");
            req.setStartTime("15:00:00");

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.events[0].event_id").value("event_123"))
                .andExpect(jsonPath("$.data.events[0].status").value("updated"));

            verify(calendarApiService, times(1)).modify(eq(token), any());
        }

        @Test
        @DisplayName("CalendarApiService 拋出 TokenResponseException 時回傳 401 TOKEN_REFRESH_FAILED")
        void modify_tokenResponseException_returns401() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            TokenResponseException tokenException = mock(TokenResponseException.class);
            when(calendarApiService.modify(eq(token), any())).thenThrow(tokenException);

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REFRESH_FAILED"));
        }

        @Test
        @DisplayName("CalendarApiService 拋出一般例外時回傳 500 GOOGLE_API_ERROR")
        void modify_genericException_returns500() throws Exception {
            GoogleToken token = sampleToken();
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(calendarApiService.modify(eq(token), any())).thenThrow(new RuntimeException("patch failed"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");

            mockMvc.perform(post("/google/calendar/modify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_API_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Google API 呼叫失敗：patch failed"));
        }
    }

    /**
     * 小工具：用 ArgumentCaptor 撈出實際傳給 {@link CalendarApiService#query} 的
     * {@link GoogleCalendarApiRequest}，讓測試可以斷言 controller 有把 JSON body
     * 正確反序列化並原樣轉交給 service（例如 date / date_to / keyword / limit 的對應）。
     */
    private static final class ArgumentCaptorHelper {
        record QueryCapture(GoogleCalendarApiRequest request) {}

        static QueryCapture captureQuery(CalendarApiService service, GoogleToken token) throws Exception {
            org.mockito.ArgumentCaptor<GoogleCalendarApiRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GoogleCalendarApiRequest.class);
            verify(service, times(1)).query(eq(token), captor.capture());
            return new QueryCapture(captor.getValue());
        }
    }
}
