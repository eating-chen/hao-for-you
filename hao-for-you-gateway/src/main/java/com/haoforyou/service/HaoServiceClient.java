package com.haoforyou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * hao-for-you-service 的 HTTP 客戶端。
 * 負責將 LLM 請求轉發給 Python service，由 service 端呼叫 Ollama。
 */
@Service
public class HaoServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HaoServiceClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serviceUrl;

    public HaoServiceClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = objectMapper;
        this.serviceUrl = appProperties.serviceUrl();
    }

    /**
     * 呼叫 service 的 LLM 服務，路徑帶訂。
     * service 端會加上 system prompt 後送給 Ollama，適合一般對話。
     *
     * @param userMessage 使用者原始訊息
     * @return LLM 回覆文字
     */
    // TODO 連結llm server的路徑
    public String generate(String userMessage) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("prompt", userMessage));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/test_ollama"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

            log.info("HaoServiceClient.generate → {}/test_ollama", serviceUrl);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // FastAPI 回傳 JSON 字串時帶有引號（例如 "\"回覆內容\""），需反序列化
            String raw = response.body().trim();
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                return objectMapper.readValue(raw, String.class);
            }
            return raw;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HaoServiceClient.generate interrupted: {}", e.getMessage(), e);
            return "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。";
        } catch (Exception e) {
            log.error("HaoServiceClient.generate error: {}", e.getMessage(), e);
            return "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。";
        }
    }
}
