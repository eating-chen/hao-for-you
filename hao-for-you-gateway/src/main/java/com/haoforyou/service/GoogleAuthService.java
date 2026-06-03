package com.haoforyou.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.haoforyou.config.AppProperties;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.repository.GoogleTokenRepository;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final List<String> SCOPES = Arrays.asList(
        CalendarScopes.CALENDAR,
        "https://www.googleapis.com/auth/gmail.readonly",
        "openid",
        "email"
    );

    private final AppProperties appProperties;
    private final GoogleTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;
    private final MessagingApiClient messagingApiClient;

    public GoogleAuthService(AppProperties appProperties,
                             GoogleTokenRepository tokenRepository,
                             ObjectMapper objectMapper,
                             MessagingApiClient messagingApiClient) {
        this.appProperties = appProperties;
        this.tokenRepository = tokenRepository;
        this.objectMapper = objectMapper;
        this.messagingApiClient = messagingApiClient;
    }

    /** 產生 Google OAuth 授權 URL（state = lineUserId） */
    public String buildAuthUrl(String lineUserId) {
        try {
            GoogleAuthorizationCodeFlow flow = buildFlow();
            return flow.newAuthorizationUrl()
                .setRedirectUri(appProperties.google().redirectUri())
                .setState(lineUserId)
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
        } catch (Exception e) {
            log.error("Failed to build auth URL", e);
            throw new RuntimeException("無法產生授權連結", e);
        }
    }

    /** 用 authorization code 換 tokens，存入 DB */
    @Transactional
    public void exchangeAndSave(String code, String lineUserId) throws Exception {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        TokenResponse tokenResponse = flow.newTokenRequest(code)
            .setRedirectUri(appProperties.google().redirectUri())
            .execute();

        String accessToken = tokenResponse.getAccessToken();
        String refreshToken = tokenResponse.getRefreshToken();
        Long expiresIn = tokenResponse.getExpiresInSeconds();
        LocalDateTime expiry = expiresIn != null
            ? LocalDateTime.now().plusSeconds(expiresIn)
            : null;

        String email = getUserEmail(accessToken);

        // upsert
        GoogleToken token = tokenRepository.findByLineUserId(lineUserId)
            .orElseGet(() -> new GoogleToken(lineUserId, accessToken, refreshToken, expiry, email));
        token.setAccessToken(accessToken);
        if (refreshToken != null) token.setRefreshToken(refreshToken);
        token.setTokenExpiry(expiry);
        token.setGoogleEmail(email);
        tokenRepository.save(token);

        log.info("Google token saved for user={} email={}", lineUserId, email);
    }

    /** 用已儲存的 token 建立 Calendar service */
    public Calendar buildCalendarService(GoogleToken token) throws Exception {
        @SuppressWarnings("deprecation")
        GoogleCredential credential = new GoogleCredential.Builder()
            .setTransport(GoogleNetHttpTransport.newTrustedTransport())
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .setClientSecrets(appProperties.google().clientId(), appProperties.google().clientSecret())
            .build()
            .setAccessToken(token.getAccessToken())
            .setRefreshToken(token.getRefreshToken());

        // 若過期則自動 refresh 並更新 DB
        if (token.getTokenExpiry() != null && token.getTokenExpiry().isBefore(LocalDateTime.now())) {
            credential.refreshToken();
            token.setAccessToken(credential.getAccessToken());
            LocalDateTime newExpiry = LocalDateTime.now()
                .plusSeconds(credential.getExpiresInSeconds());
            token.setTokenExpiry(newExpiry);
            tokenRepository.save(token);
        }

        return new Calendar.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("hao-for-you").build();
    }

    public Optional<GoogleToken> getToken(String lineUserId) {
        return tokenRepository.findByLineUserId(lineUserId);
    }

    @Transactional
    public boolean deleteToken(String lineUserId) {
        if (tokenRepository.existsByLineUserId(lineUserId)) {
            tokenRepository.deleteByLineUserId(lineUserId);
            return true;
        }
        return false;
    }

    public void pushLineMessage(String lineUserId, String text) {
        try {
            messagingApiClient.pushMessage(
                null,
                new PushMessageRequest(lineUserId, List.of(new TextMessage(text)), false, null)
            ).join();
            log.info("Push message sent to user={}", lineUserId);
        } catch (Exception e) {
            log.error("Failed to push LINE message to user={}: {}", lineUserId, e.getMessage());
        }
    }

    // ── private helpers ──

    private GoogleAuthorizationCodeFlow buildFlow() throws Exception {
        Map<String, Object> webConfig = Map.of(
            "client_id", appProperties.google().clientId(),
            "client_secret", appProperties.google().clientSecret(),
            "auth_uri", "https://accounts.google.com/o/oauth2/auth",
            "token_uri", "https://oauth2.googleapis.com/token",
            "redirect_uris", List.of(appProperties.google().redirectUri())
        );
        String secretJson = objectMapper.writeValueAsString(Map.of("web", webConfig));
        GoogleClientSecrets secrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(), new StringReader(secretJson)
        );
        return new GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            secrets,
            SCOPES
        ).build();
    }

    private String getUserEmail(String accessToken) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                .header("Authorization", "Bearer " + accessToken)
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = objectMapper.readValue(
                response.body(), new TypeReference<>() {}
            );
            Object emailObj = body.getOrDefault("email", "unknown@gmail.com");
            return emailObj instanceof String ? (String) emailObj : "unknown@gmail.com";
        } catch (Exception e) {
            log.error("Failed to get user email", e);
            return "unknown@gmail.com";
        }
    }
}
