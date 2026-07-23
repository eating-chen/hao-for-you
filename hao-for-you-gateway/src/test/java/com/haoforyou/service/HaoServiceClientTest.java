package com.haoforyou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HaoServiceClient}.
 *
 * <p>{@code HaoServiceClient} builds its own {@link HttpClient} internally in the
 * constructor (it is not an injectable dependency), so no real HTTP request can ever
 * leave the process during construction. To control the actual {@code send(...)} calls
 * made by {@link HaoServiceClient#checkHealth()} and {@link HaoServiceClient#generate}
 * without hitting the real hao-for-you-service, the private {@code httpClient} field is
 * swapped for a Mockito mock via {@link ReflectionTestUtils} right after construction.</p>
 */
@ExtendWith(MockitoExtension.class)
class HaoServiceClientTest {

    private static final String SERVICE_URL = "http://hao-for-you-service:8000";

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private HaoServiceClient client;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties(null, null, null, SERVICE_URL);
        client = new HaoServiceClient(appProperties, new ObjectMapper());
        ReflectionTestUtils.setField(client, "httpClient", httpClient);
    }

    // ────────────────────────────────────────────────────────────
    // checkHealth
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkHealth")
    class CheckHealth {

        @Test
        @DisplayName("happy path：service 回傳 200 時應回傳 true，且打的是 service 根路徑")
        void serviceReturns200_returnsTrue() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            boolean result = client.checkHealth();

            assertThat(result).isTrue();
            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), bodyHandler());
            assertThat(captor.getValue().uri().toString()).isEqualTo(SERVICE_URL + "/");
            assertThat(captor.getValue().method()).isEqualTo("GET");
        }

        @Test
        @DisplayName("service 回傳非 200（例如 500）時應回傳 false")
        void serviceReturnsNon200_returnsFalse() throws Exception {
            when(httpResponse.statusCode()).thenReturn(500);
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            boolean result = client.checkHealth();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("送出請求時拋出例外（例如連線失敗）應被吞掉並回傳 false")
        void sendThrows_returnsFalse() throws Exception {
            when(httpClient.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new IOException("connection refused"));

            boolean result = client.checkHealth();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("送出請求時被中斷（InterruptedException）應被吞掉並回傳 false")
        void sendInterrupted_returnsFalse() throws Exception {
            when(httpClient.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new InterruptedException("interrupted"));

            boolean result = client.checkHealth();

            assertThat(result).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────
    // generate
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("happy path：service 回傳 success=true 時，應回傳 response 欄位內容，且打對 /v1/chat 並帶正確 JSON header")
        void success_returnsResponseText() throws Exception {
            when(httpResponse.body()).thenReturn(
                "{\"success\":true,\"user_id\":\"U1\",\"session_id\":\"s1\",\"platform\":\"line\",\"response\":\"哈囉！\"}");
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            String result = client.generate("嗨", "U1");

            assertThat(result).isEqualTo("哈囉！");
            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), bodyHandler());
            HttpRequest sentRequest = captor.getValue();
            assertThat(sentRequest.uri().toString()).isEqualTo(SERVICE_URL + "/v1/chat");
            assertThat(sentRequest.method()).isEqualTo("POST");
            assertThat(sentRequest.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(sentRequest.bodyPublisher()).isPresent();
        }

        @Test
        @DisplayName("service 回傳 success=false 時，應回傳固定的錯誤提示文字")
        void serviceReportsFailure_returnsErrorFallbackText() throws Exception {
            when(httpResponse.body()).thenReturn(
                "{\"success\":false,\"user_id\":\"U1\",\"session_id\":\"s1\",\"platform\":\"line\",\"response\":null}");
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            String result = client.generate("嗨", "U1");

            assertThat(result).isEqualTo("抱歉，連線到 AI 服務有誤，請確認問題後再試。");
        }

        @Test
        @DisplayName("送出請求時拋出一般例外（例如連線失敗）應回傳暫時無法連線的提示文字")
        void sendThrows_returnsUnavailableFallbackText() throws Exception {
            when(httpClient.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new IOException("connection refused"));

            String result = client.generate("嗨", "U1");

            assertThat(result).isEqualTo("抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。");
        }

        @Test
        @DisplayName("送出請求時被中斷（InterruptedException）應回傳暫時無法連線的提示文字，且應重設 thread 的 interrupt 狀態")
        void sendInterrupted_returnsUnavailableFallbackTextAndResetsInterruptFlag() throws Exception {
            when(httpClient.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new InterruptedException("interrupted"));

            String result = client.generate("嗨", "U1");

            try {
                assertThat(result).isEqualTo("抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // 清除 interrupt 旗標，避免影響同一個 JVM 內接下來執行的其他測試
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("service 回傳無法解析的 JSON 時，應被當成例外處理並回傳暫時無法連線的提示文字")
        void malformedResponseBody_returnsUnavailableFallbackText() throws Exception {
            when(httpResponse.body()).thenReturn("this is not valid json");
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            String result = client.generate("嗨", "U1");

            assertThat(result).isEqualTo("抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。");
        }

        @Test
        @DisplayName("userId 為 null 時，仍應正常呼叫並回傳 LLM 回覆內容")
        void nullUserId_stillCallsServiceAndReturnsResponse() throws Exception {
            when(httpResponse.body()).thenReturn(
                "{\"success\":true,\"response\":\"沒有 userId 也可以\"}");
            when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(httpResponse);

            String result = client.generate("嗨", null);

            assertThat(result).isEqualTo("沒有 userId 也可以");
        }
    }

    private static HttpResponse.BodyHandler<String> bodyHandler() {
        return ArgumentMatchers.<HttpResponse.BodyHandler<String>>any();
    }
}
