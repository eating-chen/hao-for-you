package com.haoforyou.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.config.AppProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InternalAuthFilter} 的單元測試。
 *
 * <p>用 Spring 的 {@link MockHttpServletRequest}/{@link MockHttpServletResponse} 搭配一個 lambda
 * {@link FilterChain} 直接驅動 filter，不啟動 servlet 容器、不打網路。多數情境使用「真的
 * {@link InternalApiSigner}（注入固定時鐘 + 已知 secret）」來產生合法/過期簽章，讓驗簽路徑貼近真實；
 * signer 拋例外的 fail-closed 情境才用 Mockito mock。</p>
 *
 * <p>時間一律用 {@link Clock#fixed} 固定，避免時間窗判斷產生 flaky test。</p>
 */
class InternalAuthFilterTest {

    private static final String SECRET = "filter-test-secret";
    private static final long NOW_EPOCH = 1_700_000_000L;
    private static final Clock FIXED_NOW = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH), ZoneOffset.UTC);
    private static final String PROTECTED_PATH = "/google/calendar/create";

    private InternalApiSigner signer;
    private ObjectMapper objectMapper;
    private InternalAuthFilter filter;

    private static AppProperties props(String secret) {
        return new AppProperties(null, null, new AppProperties.Internal(secret, 300L), null, null);
    }

    /** 時鐘固定在指定 epoch 秒、secret 相同的 signer，用來為特定時間產生合法簽章。 */
    private static InternalApiSigner signerAt(long epochSecond) {
        return new InternalApiSigner(
            props(SECRET),
            Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        signer = new InternalApiSigner(props(SECRET), FIXED_NOW);
        objectMapper = new ObjectMapper();
        filter = new InternalAuthFilter(signer, objectMapper);
    }

    private MockHttpServletRequest signedRequest(String method, String path, String body,
                                                 InternalApiSigner.SignedHeaders headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (headers != null) {
            request.addHeader("X-Timestamp", headers.timestamp());
            request.addHeader("X-Signature", headers.signature());
        }
        return request;
    }

    // ────────────────────────────────────────────────────────────
    // shouldNotFilter（受保護路徑 vs. OAuth callback 排除）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldNotFilter")
    class ShouldNotFilter {

        private MockHttpServletRequest uri(String path) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI(path);
            return request;
        }

        @Test
        @DisplayName("/google/auth/callback 被排除（不驗簽）")
        void callbackIsExcluded() {
            assertThat(filter.shouldNotFilter(uri("/google/auth/callback"))).isTrue();
        }

        @Test
        @DisplayName("/google/calendar/create 需驗簽")
        void calendarCreateIsProtected() {
            assertThat(filter.shouldNotFilter(uri("/google/calendar/create"))).isFalse();
        }

        @Test
        @DisplayName("/google/auth 需驗簽")
        void authIsProtected() {
            assertThat(filter.shouldNotFilter(uri("/google/auth"))).isFalse();
        }

        @Test
        @DisplayName("/google/mail/query 需驗簽")
        void mailQueryIsProtected() {
            assertThat(filter.shouldNotFilter(uri("/google/mail/query"))).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────
    // doFilter - 放行（合法簽章）與 body 快取
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("doFilter - 放行")
    class Pass {

        @Test
        @DisplayName("合法簽章 → 放行（chain 繼續），且下游仍能讀到原始 body（body 快取生效）")
        void validSignaturePassesAndBodyStillReadable() throws Exception {
            String body = "{\"lineUserId\":\"U1\",\"title\":\"開會\"}";
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", PROTECTED_PATH, body);
            MockHttpServletRequest request = signedRequest("POST", PROTECTED_PATH, body, headers);
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicReference<String> downstreamBody = new AtomicReference<>();
            AtomicBoolean chainCalled = new AtomicBoolean(false);
            FilterChain chain = (req, res) -> {
                chainCalled.set(true);
                downstreamBody.set(StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8));
            };

            filter.doFilter(request, response, chain);

            assertThat(chainCalled).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            // 驗簽 filter 已先讀過一次 body，下游透過 CachedBodyHttpServletRequest 仍讀得到同一份
            assertThat(downstreamBody.get()).isEqualTo(body);
        }

        @Test
        @DisplayName("/google/auth/callback → shouldNotFilter 放行，即使未帶任何簽章 header")
        void callbackPassesWithoutHeaders() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/google/auth/callback");
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            FilterChain chain = (req, res) -> chainCalled.set(true);

            filter.doFilter(request, response, chain);

            assertThat(chainCalled).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    // ────────────────────────────────────────────────────────────
    // doFilter - 拒絕（401 AUTHORIZATION_FAIL）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("doFilter - 拒絕")
    class Reject {

        private void assertRejected(MockHttpServletResponse response, AtomicBoolean chainCalled) throws Exception {
            assertThat(chainCalled).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).contains("application/json");
            JsonNode json = objectMapper.readTree(response.getContentAsString());
            assertThat(json.get("success").asBoolean()).isFalse();
            assertThat(json.get("data").isNull()).isTrue();
            assertThat(json.get("error").get("code").asText()).isEqualTo("AUTHORIZATION_FAIL");
            assertThat(json.get("error").get("message").asText()).isNotBlank();
        }

        @Test
        @DisplayName("缺少 X-Timestamp / X-Signature → 401 AUTHORIZATION_FAIL，chain 不被呼叫")
        void missingHeadersRejected() throws Exception {
            MockHttpServletRequest request = signedRequest("POST", PROTECTED_PATH, "{}", null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

            assertRejected(response, chainCalled);
        }

        @Test
        @DisplayName("簽章錯誤 → 401 AUTHORIZATION_FAIL")
        void invalidSignatureRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", PROTECTED_PATH);
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            request.addHeader("X-Timestamp", Long.toString(NOW_EPOCH));
            request.addHeader("X-Signature", "deadbeef".repeat(8)); // 64 字元但錯誤的簽章
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

            assertRejected(response, chainCalled);
        }

        @Test
        @DisplayName("body 與簽章不一致（body 被竄改）→ 401")
        void tamperedBodyRejected() throws Exception {
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", PROTECTED_PATH, "{\"a\":1}");
            MockHttpServletRequest request = signedRequest("POST", PROTECTED_PATH, "{\"a\":2}", headers);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

            assertRejected(response, chainCalled);
        }

        @Test
        @DisplayName("時間戳過期（超出時間窗）→ 401，即使簽章本身合法")
        void staleTimestampRejected() throws Exception {
            String body = "{}";
            // 用時鐘在 NOW-1000 的 signer 產生合法但過期的簽章；filter 的時鐘固定在 NOW
            InternalApiSigner.SignedHeaders stale = signerAt(NOW_EPOCH - 1000).sign("POST", PROTECTED_PATH, body);
            MockHttpServletRequest request = signedRequest("POST", PROTECTED_PATH, body, stale);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

            assertRejected(response, chainCalled);
        }
    }

    // ────────────────────────────────────────────────────────────
    // doFilter - fail-closed（驗簽過程拋例外時不放行下游）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("doFilter - fail-closed")
    class FailClosed {

        @Test
        @DisplayName("signer.verify 拋例外時，例外往外傳且下游 chain 不被呼叫（請求不會被放行）")
        void signerThrowsDoesNotPassDownstream() {
            InternalApiSigner throwingSigner = mock(InternalApiSigner.class);
            when(throwingSigner.verify(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("HMAC boom"));
            InternalAuthFilter failClosedFilter = new InternalAuthFilter(throwingSigner, objectMapper);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", PROTECTED_PATH);
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            request.addHeader("X-Timestamp", Long.toString(NOW_EPOCH));
            request.addHeader("X-Signature", "somesignature");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainCalled = new AtomicBoolean(false);
            FilterChain chain = (req, res) -> chainCalled.set(true);

            assertThatThrownBy(() -> failClosedFilter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);

            // 關鍵：驗簽過程出錯時下游不會被觸及（不放行）
            assertThat(chainCalled).isFalse();
        }
    }
}
