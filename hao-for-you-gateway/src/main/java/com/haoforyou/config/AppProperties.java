package com.haoforyou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Google google,
    Conversation conversation,
    Internal internal,
    String systemPrompt,
    String serviceUrl
) {
    public record Google(
        String clientId,
        String clientSecret,
        String redirectUri
    ) {}

    public record Conversation(int maxTurns) {}

    /**
     * 跨服務 HMAC 驗證設定：
     * - apiSecret：與 hao-for-you-service 共享的固定密鑰（環境變數 INTERNAL_API_SECRET）
     * - timestampWindow：時間戳新鮮度容許秒數（環境變數 INTERNAL_API_TIMESTAMP_WINDOW，預設 300）
     */
    public record Internal(String apiSecret, Long timestampWindow) {}
}
