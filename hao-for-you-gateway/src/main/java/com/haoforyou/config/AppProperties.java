package com.haoforyou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Google google,
    Conversation conversation,
    String systemPrompt,
    String serviceUrl
) {
    public record Google(
        String clientId,
        String clientSecret,
        String redirectUri
    ) {}

    public record Conversation(int maxTurns) {}
}
