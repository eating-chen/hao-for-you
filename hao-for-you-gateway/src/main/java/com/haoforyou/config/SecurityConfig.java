package com.haoforyou.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.security.InternalApiSigner;
import com.haoforyou.security.InternalAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 註冊跨服務驗簽 filter。刻意用 FilterRegistrationBean 限定只掛在 {@code /google/*}，
 * 避免把 InternalAuthFilter 宣告成 Filter bean 被 Spring Boot 自動註冊到所有請求。
 * （沒有引入 spring-boot-starter-security。）
 */
@Configuration
public class SecurityConfig {

    @Bean
    FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(
            InternalApiSigner signer, ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalAuthFilter(signer, objectMapper));
        // servlet path mapping：/google/* 會 match /google 底下所有層級的路徑，
        // 例如 /google/auth、/google/calendar/create、/google/mail/query、/google/auth/callback。
        // /google/auth/callback 在 filter 內以 shouldNotFilter 排除。
        registration.addUrlPatterns("/google/*");
        registration.setName("internalAuthFilter");
        registration.setOrder(1);
        return registration;
    }
}
