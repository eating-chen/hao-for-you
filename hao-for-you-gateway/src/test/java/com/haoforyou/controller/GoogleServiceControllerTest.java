package com.haoforyou.controller;

import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.GoogleAuthService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link GoogleServiceController}.
 *
 * <p>Uses {@code @WebMvcTest} to load only the web layer (controller + MVC
 * infrastructure) and mocks {@link GoogleAuthService} so no real Google API
 * calls, database access, or LINE push messages are made.</p>
 */
@WebMvcTest(GoogleServiceController.class)
class GoogleServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GoogleAuthService googleAuthService;

    private static final String LINE_USER_ID = "Uabc1234567890";

    // ────────────────────────────────────────────────────────────
    // GET /google/auth
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /google/auth")
    class StartAuth {

        @Test
        @DisplayName("回傳成功時應包含 auth_url 且帶入正確的 line_user_id")
        void startAuth_happyPath_returnsAuthUrl() throws Exception {
            String expectedUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&state=" + LINE_USER_ID;
            when(googleAuthService.buildAuthUrl(LINE_USER_ID)).thenReturn(expectedUrl);

            mockMvc.perform(get("/google/auth").param("line_user_id", LINE_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.auth_url").value(expectedUrl));

            verify(googleAuthService, times(1)).buildAuthUrl(LINE_USER_ID);
        }

        @Test
        @DisplayName("缺少必填參數 line_user_id 時應回傳 400")
        void startAuth_missingRequiredParam_returnsBadRequest() throws Exception {
            mockMvc.perform(get("/google/auth"))
                .andExpect(status().isBadRequest());

            verify(googleAuthService, never()).buildAuthUrl(anyString());
        }

        @Test
        @DisplayName("GoogleAuthService 拋出例外時，因無 try-catch 包裝，例外會往外傳（正式環境由容器轉為 500）")
        void startAuth_serviceThrows_propagatesException() {
            when(googleAuthService.buildAuthUrl(LINE_USER_ID))
                .thenThrow(new RuntimeException("無法產生授權連結"));

            ServletException thrown = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/google/auth").param("line_user_id", LINE_USER_ID)));

            assertInstanceOf(RuntimeException.class, thrown.getCause());
            org.junit.jupiter.api.Assertions.assertEquals("無法產生授權連結", thrown.getCause().getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // GET /google/auth/callback
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /google/auth/callback")
    class GoogleCallback {

        @Test
        @DisplayName("授權成功且已取得 Google email 時，回應應包含該 email")
        void callback_happyPath_withEmail_returnsSuccessHtml() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.of(token));

            mockMvc.perform(get("/google/auth/callback")
                    .param("code", "authcode123")
                    .param("state", LINE_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("授權成功")));

            verify(googleAuthService, times(1)).exchangeAndSave("authcode123", LINE_USER_ID);
            verify(googleAuthService, times(1))
                .pushLineMessage(eq(LINE_USER_ID), argThat(containsString("user@gmail.com")));
        }

        @Test
        @DisplayName("授權成功但查無 token（email 為 unknown）時，推播訊息應使用預設文字「你的帳號」")
        void callback_happyPath_noToken_usesDefaultAccountText() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/google/auth/callback")
                    .param("code", "authcode123")
                    .param("state", LINE_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("授權成功")));

            verify(googleAuthService, times(1))
                .pushLineMessage(eq(LINE_USER_ID), argThat(containsString("你的帳號")));
        }

        @Test
        @DisplayName("exchangeAndSave 拋出例外時應回傳 500 並顯示授權失敗頁面，且不推播 LINE 訊息")
        void callback_exchangeAndSaveThrows_returnsErrorHtml() throws Exception {
            doThrow(new RuntimeException("invalid_grant"))
                .when(googleAuthService).exchangeAndSave("badcode", LINE_USER_ID);

            mockMvc.perform(get("/google/auth/callback")
                    .param("code", "badcode")
                    .param("state", LINE_USER_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("授權失敗")));

            verify(googleAuthService, never()).getToken(anyString());
            verify(googleAuthService, never()).pushLineMessage(anyString(), anyString());
        }

        @Test
        @DisplayName("getToken 拋出例外時應回傳 500 並顯示授權失敗頁面")
        void callback_getTokenThrows_returnsErrorHtml() throws Exception {
            when(googleAuthService.getToken(LINE_USER_ID))
                .thenThrow(new RuntimeException("db down"));

            mockMvc.perform(get("/google/auth/callback")
                    .param("code", "authcode123")
                    .param("state", LINE_USER_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("授權失敗")));

            verify(googleAuthService, never()).pushLineMessage(anyString(), anyString());
        }

        @Test
        @DisplayName("缺少必填參數 code 時應回傳 400")
        void callback_missingCode_returnsBadRequest() throws Exception {
            mockMvc.perform(get("/google/auth/callback").param("state", LINE_USER_ID))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("缺少必填參數 state 時應回傳 400")
        void callback_missingState_returnsBadRequest() throws Exception {
            mockMvc.perform(get("/google/auth/callback").param("code", "authcode123"))
                .andExpect(status().isBadRequest());
        }
    }
}
