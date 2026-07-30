package com.haoforyou.security;

import com.haoforyou.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 跨服務 HMAC-SHA256 簽章 / 驗簽工具（僅用 JDK 內建 {@code javax.crypto}）。
 *
 * <p>signingString 的組法與 hao-for-you-service 端必須完全一致：
 * <pre>{@code timestamp + "\n" + METHOD + "\n" + path + "\n" + body}</pre>
 * 其中 METHOD 一律大寫、path 不含 query string、GET 無 body 時 body 為空字串。
 */
@Component
public class InternalApiSigner {

    private static final long DEFAULT_WINDOW_SECONDS = 300L;

    private final String secret;
    private final long windowSeconds;
    private final Clock clock;

    @Autowired
    public InternalApiSigner(AppProperties appProperties) {
        this(appProperties, Clock.systemUTC());
    }

    /** 供測試注入固定時鐘。 */
    InternalApiSigner(AppProperties appProperties, Clock clock) {
        AppProperties.Internal internal = appProperties.internal();
        this.secret = (internal != null && internal.apiSecret() != null) ? internal.apiSecret() : "";
        Long window = internal != null ? internal.timestampWindow() : null;
        this.windowSeconds = window != null ? window : DEFAULT_WINDOW_SECONDS;
        this.clock = clock;
    }

    /**
     * 為一個外送請求產生 {@code X-Timestamp} / {@code X-Signature} 標頭值。
     *
     * @param method HTTP method（大小寫不拘，內部會轉大寫）
     * @param path   request path，不含 query string（例如 {@code /v1/chat}）
     * @param body   POST 的 raw JSON body 原字串；GET 無 body 時傳空字串
     */
    public SignedHeaders sign(String method, String path, String body) {
        String timestamp = Long.toString(Instant.now(clock).getEpochSecond());
        String signature = hmacHex(buildSigningString(timestamp, method, path, body));
        return new SignedHeaders(timestamp, signature);
    }

    /**
     * 驗證收到的簽章。缺標頭、時間戳無法解析、超出時間窗、或簽章不符都回傳 false。
     * 簽章比對採常數時間（{@link MessageDigest#isEqual}）。
     */
    public boolean verify(String timestamp, String signature, String method, String path, String body) {
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - ts) > windowSeconds) {
            return false;
        }
        String expected = hmacHex(buildSigningString(timestamp.trim(), method, path, body));
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signature.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    static String buildSigningString(String timestamp, String method, String path, String body) {
        String safeMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String safeBody = body == null ? "" : body;
        return timestamp + "\n" + safeMethod + "\n" + path + "\n" + safeBody;
    }

    private String hmacHex(String signingString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }

    /** 一次簽章產生的兩個標頭值。 */
    public record SignedHeaders(String timestamp, String signature) {}
}
