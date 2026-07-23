package com.haoforyou.controller;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.GoogleMailApiRequest;
import com.haoforyou.dto.MailData;
import com.haoforyou.dto.MessageSummary;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.GoogleAuthService;
import com.haoforyou.service.MailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MailController} 的單元測試。
 *
 * <p>使用 {@code @WebMvcTest} 只載入 web 層（MailController + MVC 基礎設施），
 * 並以 {@code @MockBean} 取代 {@link GoogleAuthService} 與 {@link MailService}，
 * 避免打到真正的 Google API、DB。</p>
 *
 * <p>對應設計文件：docs/specs/2026-06-15-google-api-endpoints-design.md
 * 「1. Gmail 查詢」章節與「錯誤處理」章節。</p>
 *
 * <p>備註：spec 文件中把 GOOGLE_NOT_LINKED 對應到 HTTP 404，但目前
 * {@code MailController} 實作是回傳 401（與 TOKEN_REFRESH_FAILED 共用
 * UNAUTHORIZED 狀態碼），測試依實際程式行為撰寫。</p>
 */
@WebMvcTest(MailController.class)
class MailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GoogleAuthService googleAuthService;

    @MockBean
    private MailService mailService;

    private static final String LINE_USER_ID = "Uabc1234567890";

    // ────────────────────────────────────────────────────────────
    // POST /google/mail/query — happy path
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/mail/query - 成功情境")
    class HappyPath {

        @Test
        @DisplayName("已連結帳號且查得到郵件時，回傳 200 與郵件列表")
        void query_happyPath_returnsMailData() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            MessageSummary summary = new MessageSummary(
                "1789abcdef123456", "明天開會通知", "boss@gmail.com",
                "2026-06-15", "請準時出席...", "各位同事，會議將於明天上午舉行..."
            );
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenReturn(new MailData(List.of(summary)));

            String body = """
                {
                  "line_user_id": "%s",
                  "keyword": "開會",
                  "from": "boss@gmail.com",
                  "unread_only": true,
                  "limit": 3
                }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.messages", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.messages[0].id").value("1789abcdef123456"))
                .andExpect(jsonPath("$.data.messages[0].subject").value("明天開會通知"))
                .andExpect(jsonPath("$.data.messages[0].from").value("boss@gmail.com"))
                .andExpect(jsonPath("$.data.messages[0].date").value("2026-06-15"))
                .andExpect(jsonPath("$.data.messages[0].snippet").value("請準時出席..."))
                .andExpect(jsonPath("$.data.messages[0].body").value("各位同事，會議將於明天上午舉行..."));

            ArgumentCaptor<GoogleMailApiRequest> reqCaptor = ArgumentCaptor.forClass(GoogleMailApiRequest.class);
            verify(mailService, times(1)).query(org.mockito.ArgumentMatchers.eq(token), reqCaptor.capture());
            GoogleMailApiRequest capturedReq = reqCaptor.getValue();
            assertThat(capturedReq.getLineUserId()).isEqualTo(LINE_USER_ID);
            assertThat(capturedReq.getKeyword()).isEqualTo("開會");
            assertThat(capturedReq.getFrom()).isEqualTo("boss@gmail.com");
            assertThat(capturedReq.getUnreadOnly()).isTrue();
            assertThat(capturedReq.getLimit()).isEqualTo(3);
        }

        @Test
        @DisplayName("查無郵件時，回傳 200 且 data.messages 為空陣列")
        void query_happyPath_noMessages_returnsEmptyList() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenReturn(new MailData(List.of()));

            String body = """
                { "line_user_id": "%s" }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages", org.hamcrest.Matchers.hasSize(0)));
        }

        @Test
        @DisplayName("日期區間欄位 date/date_to 能正確反序列化並傳給 MailService")
        void query_withDateRangeFields_deserializesCorrectly() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenReturn(new MailData(List.of()));

            String body = """
                {
                  "line_user_id": "%s",
                  "date": "2026-06-01",
                  "date_to": "2026-06-05",
                  "unread_only": false,
                  "limit": 10
                }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk());

            ArgumentCaptor<GoogleMailApiRequest> reqCaptor = ArgumentCaptor.forClass(GoogleMailApiRequest.class);
            verify(mailService).query(any(GoogleToken.class), reqCaptor.capture());
            GoogleMailApiRequest capturedReq = reqCaptor.getValue();
            assertThat(capturedReq.getDate()).isEqualTo("2026-06-01");
            assertThat(capturedReq.getDateTo()).isEqualTo("2026-06-05");
            assertThat(capturedReq.getUnreadOnly()).isFalse();
            assertThat(capturedReq.getLimit()).isEqualTo(10);
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/mail/query — 未連結 Google 帳號
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/mail/query - 尚未連結 Google 帳號")
    class NotLinked {

        @Test
        @DisplayName("getToken 回傳 empty 時，回傳 401 GOOGLE_NOT_LINKED，且不呼叫 MailService")
        void query_userNotLinked_returns401AndDoesNotCallMailService() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.empty());

            String body = """
                { "line_user_id": "%s" }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_NOT_LINKED"))
                .andExpect(jsonPath("$.error.message").value("此用戶尚未連結 Google 帳號"));

            verify(mailService, never()).query(any(GoogleToken.class), any(GoogleMailApiRequest.class));
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/mail/query — MailService 例外
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/mail/query - MailService 拋出例外")
    class ServiceErrors {

        @Test
        @DisplayName("TokenResponseException（refresh_token 失效）時，回傳 401 TOKEN_REFRESH_FAILED")
        void query_tokenRefreshFails_returns401() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            TokenResponseException tokenException = mock(TokenResponseException.class);
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenThrow(tokenException);

            String body = """
                { "line_user_id": "%s" }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_REFRESH_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Google 授權已失效，請重新連結帳號"));
        }

        @Test
        @DisplayName("一般例外（如 Gmail API 呼叫失敗）時，回傳 500 GOOGLE_API_ERROR 並附上例外訊息")
        void query_genericException_returns500WithMessage() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenThrow(new RuntimeException("quota exceeded"));

            String body = """
                { "line_user_id": "%s" }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_API_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Google API 呼叫失敗：quota exceeded"));
        }

        @Test
        @DisplayName("checked Exception（非 TokenResponseException）時，同樣落入通用 catch 回傳 500")
        void query_checkedException_returns500() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));
            when(mailService.query(any(GoogleToken.class), any(GoogleMailApiRequest.class)))
                .thenThrow(new java.io.IOException("network down"));

            String body = """
                { "line_user_id": "%s" }
                """.formatted(LINE_USER_ID);

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_API_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Google API 呼叫失敗：network down"));
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /google/mail/query — request 格式 / HTTP 邊界
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /google/mail/query - 請求格式與 HTTP 邊界")
    class RequestFormat {

        @Test
        @DisplayName("body 不是合法 JSON 時，回傳 400，且不呼叫任何 service")
        void query_malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not-valid-json"))
                .andExpect(status().isBadRequest());

            verify(googleAuthService, never()).getToken(anyString());
            verify(mailService, never()).query(any(GoogleToken.class), any(GoogleMailApiRequest.class));
        }

        @Test
        @DisplayName("缺少 request body 時，回傳 400")
        void query_missingBody_returns400() throws Exception {
            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

            verify(mailService, never()).query(any(GoogleToken.class), any(GoogleMailApiRequest.class));
        }

        @Test
        @DisplayName("line_user_id 為 null（未傳）時，依然會呼叫 getToken(null)，若查無 token 則回傳 401")
        void query_missingLineUserId_callsGetTokenWithNull() throws Exception {
            when(googleAuthService.getToken(null)).thenReturn(Optional.empty());

            mockMvc.perform(post("/google/mail/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_NOT_LINKED"));

            verify(googleAuthService, times(1)).getToken(null);
        }

        @Test
        @DisplayName("以 GET 呼叫 /google/mail/query（只允許 POST）時，回傳 405")
        void query_wrongHttpMethod_returns405() throws Exception {
            mockMvc.perform(get("/google/mail/query"))
                .andExpect(status().isMethodNotAllowed());

            verify(mailService, never()).query(any(GoogleToken.class), any(GoogleMailApiRequest.class));
        }
    }
}
