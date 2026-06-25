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

import com.haoforyou.dto.HaoforyouServiceApiRequest;
import com.haoforyou.dto.HaoforyouServiceApiResponse;

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
            // 強制 HTTP/1.1：預設 HTTP/2 會對明文連線送 h2c upgrade，
            // uvicorn 不支援 → "Unsupported upgrade request" 並導致 422
            .version(HttpClient.Version.HTTP_1_1)
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
    public String generate(String userMessage, String userId) {
        try {
            HaoforyouServiceApiRequest requestPayload = new HaoforyouServiceApiRequest(
                userId, // userId 可以為 null，service 端不需要
                "line", // platform 可以固定為 "line"
                userMessage
            );
            // 必須用 Jackson 序列化成 JSON，record.toString() 不是合法 JSON 會導致 422
            String body = objectMapper.writeValueAsString(requestPayload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/v1/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

            log.info("HaoServiceClient.generate → {}/v1/chat | body={}", serviceUrl, body);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            HaoforyouServiceApiResponse apiResponse = objectMapper.readValue(response.body(), HaoforyouServiceApiResponse.class);
            // FastAPI 回傳 JSON 字串時帶有引號（例如 "\"回覆內容\""），需反序列化
            if (apiResponse.success()){
                String raw = apiResponse.response();
                return raw;
            } else {
                log.error("HaoServiceClient.generate failed: {}", apiResponse);
                return "抱歉，連線到 AI 服務有誤，請確認問題後再試。";
            }
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
