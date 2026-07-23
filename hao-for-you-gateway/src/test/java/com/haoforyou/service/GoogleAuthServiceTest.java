package com.haoforyou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.gmail.Gmail;
import com.haoforyou.config.AppProperties;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.repository.GoogleTokenRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.PushMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleAuthService}.
 *
 * <p>{@link GoogleTokenRepository} and {@link MessagingApiClient} are mocked so no real
 * database access or LINE push messages happen. {@code GoogleAuthService} builds its own
 * Google OAuth flow / {@code HttpClient} internally (not injected), so the tests that need
 * to exercise the token-exchange / user-info / credential-refresh paths use
 * {@link Mockito#mockConstruction} and {@link Mockito#mockStatic} to intercept those
 * internal constructions instead of making any real network call.</p>
 */
@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTest {

    private static final String LINE_USER_ID = "Uabc1234567890";

    @Mock
    private GoogleTokenRepository tokenRepository;

    @Mock
    private MessagingApiClient messagingApiClient;

    private AppProperties appProperties;
    private ObjectMapper objectMapper;
    private GoogleAuthService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties(
            new AppProperties.Google("client-id", "client-secret", "http://localhost/google/auth/callback"),
            new AppProperties.Conversation(10),
            "system prompt",
            "http://hao-for-you-service"
        );
        objectMapper = new ObjectMapper();
        service = new GoogleAuthService(appProperties, tokenRepository, objectMapper, messagingApiClient);
    }

    // ────────────────────────────────────────────────────────────
    // buildAuthUrl
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildAuthUrl")
    class BuildAuthUrl {

        @Test
        @DisplayName("happy path：應回傳包含 client_id、redirect_uri、state 與 offline/force 參數的授權連結")
        void happyPath_returnsAuthUrlWithExpectedParams() {
            String url = service.buildAuthUrl(LINE_USER_ID);

            assertThat(url).startsWith("https://accounts.google.com/o/oauth2");
            assertThat(url).contains("client_id=client-id");
            assertThat(url).contains("state=" + LINE_USER_ID);
            assertThat(url).contains("access_type=offline");
            assertThat(url).contains("approval_prompt=force");
        }

        @Test
        @DisplayName("建立 flow 時發生例外（例如 JSON 序列化失敗）應包裝為 RuntimeException")
        void buildFlowThrows_wrapsAsRuntimeException() throws Exception {
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any()))
                .thenThrow(mock(JsonProcessingException.class));
            GoogleAuthService serviceWithFailingMapper =
                new GoogleAuthService(appProperties, tokenRepository, failingMapper, messagingApiClient);

            assertThatThrownBy(() -> serviceWithFailingMapper.buildAuthUrl(LINE_USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("無法產生授權連結");

            verifyNoInteractions(tokenRepository, messagingApiClient);
        }
    }

    // ────────────────────────────────────────────────────────────
    // exchangeAndSave
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("exchangeAndSave")
    class ExchangeAndSave {

        @Test
        @DisplayName("新使用者：查無既有 token 時應建立新 GoogleToken 並存入正確欄位")
        void newUser_createsAndSavesNewToken() throws Exception {
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
            tokenResponse.setAccessToken("new-access-token");
            tokenResponse.setRefreshToken("new-refresh-token");
            tokenResponse.setExpiresInSeconds(3600L);

            try (MockedConstruction<GoogleAuthorizationCodeFlow.Builder> ignoredFlow =
                     mockFlowBuilder("auth-code-123", tokenResponse);
                 MockedStatic<HttpClient> ignoredHttp =
                     mockUserInfoHttpClient("{\"email\":\"user@gmail.com\"}")) {

                service.exchangeAndSave("auth-code-123", LINE_USER_ID);
            }

            ArgumentCaptor<GoogleToken> captor = ArgumentCaptor.forClass(GoogleToken.class);
            verify(tokenRepository).save(captor.capture());
            GoogleToken saved = captor.getValue();
            assertThat(saved.getLineUserId()).isEqualTo(LINE_USER_ID);
            assertThat(saved.getAccessToken()).isEqualTo("new-access-token");
            assertThat(saved.getRefreshToken()).isEqualTo("new-refresh-token");
            assertThat(saved.getGoogleEmail()).isEqualTo("user@gmail.com");
            assertThat(saved.getTokenExpiry()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("既有使用者且新 token response 沒有 refresh_token 時，應保留原本的 refresh token")
        void existingUser_withoutNewRefreshToken_keepsOldRefreshToken() throws Exception {
            GoogleToken existing = new GoogleToken(LINE_USER_ID, "old-access", "old-refresh", null, "old@gmail.com");
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.of(existing));

            GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
            tokenResponse.setAccessToken("refreshed-access-token");
            tokenResponse.setRefreshToken(null); // Google 不一定每次都回傳 refresh_token
            tokenResponse.setExpiresInSeconds(3600L);

            try (MockedConstruction<GoogleAuthorizationCodeFlow.Builder> ignoredFlow =
                     mockFlowBuilder("auth-code-456", tokenResponse);
                 MockedStatic<HttpClient> ignoredHttp =
                     mockUserInfoHttpClient("{\"email\":\"old@gmail.com\"}")) {

                service.exchangeAndSave("auth-code-456", LINE_USER_ID);
            }

            ArgumentCaptor<GoogleToken> captor = ArgumentCaptor.forClass(GoogleToken.class);
            verify(tokenRepository).save(captor.capture());
            GoogleToken saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getAccessToken()).isEqualTo("refreshed-access-token");
            assertThat(saved.getRefreshToken()).isEqualTo("old-refresh");
        }

        @Test
        @DisplayName("token response 沒有 expires_in 時，tokenExpiry 應存為 null")
        void noExpiresIn_storesNullExpiry() throws Exception {
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
            tokenResponse.setAccessToken("access-token");
            tokenResponse.setRefreshToken("refresh-token");
            tokenResponse.setExpiresInSeconds(null);

            try (MockedConstruction<GoogleAuthorizationCodeFlow.Builder> ignoredFlow =
                     mockFlowBuilder("auth-code-789", tokenResponse);
                 MockedStatic<HttpClient> ignoredHttp =
                     mockUserInfoHttpClient("{\"email\":\"user@gmail.com\"}")) {

                service.exchangeAndSave("auth-code-789", LINE_USER_ID);
            }

            ArgumentCaptor<GoogleToken> captor = ArgumentCaptor.forClass(GoogleToken.class);
            verify(tokenRepository).save(captor.capture());
            assertThat(captor.getValue().getTokenExpiry()).isNull();
        }

        @Test
        @DisplayName("取得 user email 時發生例外（呼叫 userinfo 失敗）應以 unknown@gmail.com 作為 fallback")
        void getUserEmailFails_fallsBackToUnknownEmail() throws Exception {
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
            tokenResponse.setAccessToken("access-token");
            tokenResponse.setRefreshToken("refresh-token");
            tokenResponse.setExpiresInSeconds(3600L);

            HttpClient failingHttpClient = mock(HttpClient.class);
            when(failingHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("connection refused"));

            try (MockedConstruction<GoogleAuthorizationCodeFlow.Builder> ignoredFlow =
                     mockFlowBuilder("auth-code-000", tokenResponse);
                 MockedStatic<HttpClient> httpStatic = mockStatic(HttpClient.class)) {
                httpStatic.when(HttpClient::newHttpClient).thenReturn(failingHttpClient);

                service.exchangeAndSave("auth-code-000", LINE_USER_ID);
            }

            ArgumentCaptor<GoogleToken> captor = ArgumentCaptor.forClass(GoogleToken.class);
            verify(tokenRepository).save(captor.capture());
            assertThat(captor.getValue().getGoogleEmail()).isEqualTo("unknown@gmail.com");
        }

        @Test
        @DisplayName("Google token 交換失敗（execute 拋出例外）時，例外會往外傳，且不會寫入 repository")
        void tokenExchangeThrows_propagatesAndDoesNotSave() throws Exception {
            GoogleAuthorizationCodeFlow mockFlow = mock(GoogleAuthorizationCodeFlow.class);
            GoogleAuthorizationCodeTokenRequest mockTokenRequest = mock(GoogleAuthorizationCodeTokenRequest.class);
            when(mockFlow.newTokenRequest("bad-code")).thenReturn(mockTokenRequest);
            when(mockTokenRequest.setRedirectUri(anyString())).thenReturn(mockTokenRequest);
            when(mockTokenRequest.execute()).thenThrow(new IOException("invalid_grant"));

            try (MockedConstruction<GoogleAuthorizationCodeFlow.Builder> ignored =
                     mockConstruction(GoogleAuthorizationCodeFlow.Builder.class,
                         (m, context) -> when(m.build()).thenReturn(mockFlow))) {

                assertThatThrownBy(() -> service.exchangeAndSave("bad-code", LINE_USER_ID))
                    .isInstanceOf(IOException.class)
                    .hasMessage("invalid_grant");
            }

            verify(tokenRepository, never()).save(any());
        }
    }

    // ────────────────────────────────────────────────────────────
    // buildCalendarService / buildGmailService（透過 buildCredential 的 refresh 分支）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildCalendarService / buildGmailService")
    class BuildGoogleServices {

        @Test
        @DisplayName("token 尚未過期（expiry 為 null）時，不需要 refresh，直接建立 Calendar service")
        void tokenExpiryNull_buildsCalendarServiceWithoutRefresh() throws Exception {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");

            Calendar calendar = service.buildCalendarService(token);

            assertThat(calendar).isNotNull();
            verify(tokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("token 尚未過期（expiry 在未來）時，不需要 refresh，直接建立 Gmail service")
        void tokenNotExpired_buildsGmailServiceWithoutRefresh() throws Exception {
            GoogleToken token = new GoogleToken(
                LINE_USER_ID, "access", "refresh", LocalDateTime.now().plusHours(1), "user@gmail.com");

            Gmail gmail = service.buildGmailService(token);

            assertThat(gmail).isNotNull();
            verify(tokenRepository, never()).save(any());
        }

        @Test
        @SuppressWarnings("deprecation") // GoogleCredential 本身已被 Google API client 標記為 deprecated
        @DisplayName("token 已過期時，應呼叫 refreshToken 並把新的 access token / expiry 存回 repository")
        void tokenExpired_refreshesAndSavesNewToken() throws Exception {
            GoogleToken token = new GoogleToken(
                LINE_USER_ID, "old-access", "refresh", LocalDateTime.now().minusMinutes(5), "user@gmail.com");

            GoogleCredential mockCredential = mock(GoogleCredential.class);
            when(mockCredential.setAccessToken(anyString())).thenReturn(mockCredential);
            when(mockCredential.setRefreshToken(anyString())).thenReturn(mockCredential);
            when(mockCredential.refreshToken()).thenReturn(true);
            when(mockCredential.getAccessToken()).thenReturn("refreshed-access-token");
            when(mockCredential.getExpiresInSeconds()).thenReturn(3600L);

            try (MockedConstruction<GoogleCredential.Builder> ignored =
                     mockConstruction(GoogleCredential.Builder.class, (mockBuilder, context) -> {
                         when(mockBuilder.setTransport(any())).thenReturn(mockBuilder);
                         when(mockBuilder.setJsonFactory(any())).thenReturn(mockBuilder);
                         when(mockBuilder.setClientSecrets(anyString(), anyString())).thenReturn(mockBuilder);
                         when(mockBuilder.build()).thenReturn(mockCredential);
                     })) {

                Calendar calendar = service.buildCalendarService(token);
                assertThat(calendar).isNotNull();
            }

            verify(mockCredential).refreshToken();
            ArgumentCaptor<GoogleToken> captor = ArgumentCaptor.forClass(GoogleToken.class);
            verify(tokenRepository).save(captor.capture());
            assertThat(captor.getValue().getAccessToken()).isEqualTo("refreshed-access-token");
            assertThat(captor.getValue().getTokenExpiry()).isAfter(LocalDateTime.now());
        }
    }

    // ────────────────────────────────────────────────────────────
    // getToken / deleteToken
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getToken / deleteToken")
    class TokenLookupAndDelete {

        @Test
        @DisplayName("getToken：直接委派給 repository，找得到時回傳 Optional.of(token)")
        void getToken_delegatesToRepository_found() {
            GoogleToken token = new GoogleToken(LINE_USER_ID, "access", "refresh", null, "user@gmail.com");
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.of(token));

            Optional<GoogleToken> result = service.getToken(LINE_USER_ID);

            assertThat(result).contains(token);
        }

        @Test
        @DisplayName("getToken：找不到時回傳 Optional.empty()")
        void getToken_delegatesToRepository_notFound() {
            when(tokenRepository.findByLineUserId(LINE_USER_ID)).thenReturn(Optional.empty());

            Optional<GoogleToken> result = service.getToken(LINE_USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("deleteToken：存在時應刪除並回傳 true")
        void deleteToken_whenExists_deletesAndReturnsTrue() {
            when(tokenRepository.existsByLineUserId(LINE_USER_ID)).thenReturn(true);

            boolean result = service.deleteToken(LINE_USER_ID);

            assertThat(result).isTrue();
            verify(tokenRepository).deleteByLineUserId(LINE_USER_ID);
        }

        @Test
        @DisplayName("deleteToken：不存在時不應刪除，並回傳 false")
        void deleteToken_whenNotExists_doesNotDeleteAndReturnsFalse() {
            when(tokenRepository.existsByLineUserId(LINE_USER_ID)).thenReturn(false);

            boolean result = service.deleteToken(LINE_USER_ID);

            assertThat(result).isFalse();
            verify(tokenRepository, never()).deleteByLineUserId(anyString());
        }
    }

    // ────────────────────────────────────────────────────────────
    // pushLineMessage
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("pushLineMessage")
    class PushLineMessage {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("happy path：成功推播時應呼叫 messagingApiClient.pushMessage 並帶入正確內容")
        void happyPath_pushesMessage() {
            Result<PushMessageResponse> mockResult = mock(Result.class);
            when(messagingApiClient.pushMessage(isNull(), any(PushMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResult));

            service.pushLineMessage(LINE_USER_ID, "哈囉！");

            ArgumentCaptor<PushMessageRequest> captor = ArgumentCaptor.forClass(PushMessageRequest.class);
            verify(messagingApiClient, times(1)).pushMessage(isNull(), captor.capture());
            assertThat(captor.getValue().to()).isEqualTo(LINE_USER_ID);
        }

        @Test
        @DisplayName("pushMessage 回傳的 future 以例外完成時，例外應被吞掉，不會往外拋")
        void futureCompletesExceptionally_swallowsException() {
            CompletableFuture<Result<PushMessageResponse>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("LINE API 出錯"));
            when(messagingApiClient.pushMessage(isNull(), any(PushMessageRequest.class)))
                .thenReturn(failedFuture);

            assertThatCode(() -> service.pushLineMessage(LINE_USER_ID, "哈囉！"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("messagingApiClient 直接拋出例外時，例外應被吞掉，不會往外拋")
        void clientThrowsDirectly_swallowsException() {
            when(messagingApiClient.pushMessage(isNull(), any(PushMessageRequest.class)))
                .thenThrow(new RuntimeException("同步呼叫失敗"));

            assertThatCode(() -> service.pushLineMessage(LINE_USER_ID, "哈囉！"))
                .doesNotThrowAnyException();
        }
    }

    // ────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────

    /**
     * 攔截 {@code new GoogleAuthorizationCodeFlow.Builder(...)}，讓 buildFlow() 回傳的
     * flow 改成 mock，並讓 {@code flow.newTokenRequest(code).setRedirectUri(...).execute()}
     * 回傳指定的 {@link GoogleTokenResponse}，避免真的呼叫 Google token endpoint。
     */
    private MockedConstruction<GoogleAuthorizationCodeFlow.Builder> mockFlowBuilder(
            String expectedCode, GoogleTokenResponse response) throws Exception {
        GoogleAuthorizationCodeFlow mockFlow = mock(GoogleAuthorizationCodeFlow.class);
        GoogleAuthorizationCodeTokenRequest mockTokenRequest = mock(GoogleAuthorizationCodeTokenRequest.class);
        when(mockFlow.newTokenRequest(eq(expectedCode))).thenReturn(mockTokenRequest);
        when(mockTokenRequest.setRedirectUri(anyString())).thenReturn(mockTokenRequest);
        when(mockTokenRequest.execute()).thenReturn(response);

        return mockConstruction(GoogleAuthorizationCodeFlow.Builder.class,
            (mockBuilder, context) -> when(mockBuilder.build()).thenReturn(mockFlow));
    }

    /**
     * 攔截 {@code HttpClient.newHttpClient()}（getUserEmail 內部自建的 client），
     * 讓其回傳指定的 userinfo JSON body，避免真的呼叫 Google userinfo endpoint。
     */
    @SuppressWarnings("unchecked")
    private MockedStatic<HttpClient> mockUserInfoHttpClient(String responseBody) throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.body()).thenReturn(responseBody);
        when(mockClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(mockResponse);

        MockedStatic<HttpClient> staticMock = mockStatic(HttpClient.class);
        staticMock.when(HttpClient::newHttpClient).thenReturn(mockClient);
        return staticMock;
    }
}
