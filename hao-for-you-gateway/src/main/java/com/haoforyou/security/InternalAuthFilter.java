package com.haoforyou.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.dto.GoogleApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 對內部受保護的 Google endpoint（{@code /google/**}）驗簽。
 *
 * <p>由 {@code SecurityConfig} 以 FilterRegistrationBean 掛在 URL pattern {@code /google/*}，
 * 但明確排除 {@code /google/auth/callback}（Google OAuth 導回，由使用者瀏覽器/Google 打，不能要求驗簽）。
 * 驗失敗一律回 401 + error code {@code AUTHORIZATION_FAIL}（body 形狀比照 {@link GoogleApiResponse}）。
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);
    private static final String CALLBACK_PATH = "/google/auth/callback";
    private static final String AUTHORIZATION_FAIL = "AUTHORIZATION_FAIL";

    private final InternalApiSigner signer;
    private final ObjectMapper objectMapper;

    public InternalAuthFilter(InternalApiSigner signer, ObjectMapper objectMapper) {
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Google OAuth 導回：使用者瀏覽器/Google 直接打，不帶內部簽章，必須放行
        return CALLBACK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);

        String timestamp = request.getHeader("X-Timestamp");
        String signature = request.getHeader("X-Signature");
        String method = request.getMethod();
        // 驗簽用的 path 不含 query string，與 service 端簽的 path 對齊
        String path = request.getRequestURI();
        String body = new String(wrapped.getCachedBody(), StandardCharsets.UTF_8);

        if (timestamp == null || signature == null) {
            log.warn("Internal auth rejected (missing headers): {} {}", method, path);
            reject(response, "缺少內部驗證標頭");
            return;
        }
        if (!signer.verify(timestamp, signature, method, path, body)) {
            log.warn("Internal auth rejected (invalid signature or stale timestamp): {} {}", method, path);
            reject(response, "內部服務驗證失敗");
            return;
        }
        filterChain.doFilter(wrapped, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
            response.getWriter(),
            GoogleApiResponse.fail(AUTHORIZATION_FAIL, message)
        );
    }
}
