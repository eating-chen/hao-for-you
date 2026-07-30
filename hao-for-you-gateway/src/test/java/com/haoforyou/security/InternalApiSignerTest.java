package com.haoforyou.security;

import com.haoforyou.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InternalApiSigner} 的單元測試。
 *
 * <p>{@code InternalApiSigner} 是可直接 {@code new} 的純工具類，唯一需要控制的外部輸入是時間，
 * 因此全程透過 package-private 建構子 {@code InternalApiSigner(AppProperties, Clock)} 注入固定
 * {@link Clock#fixed}，讓 {@code sign} 產出的 timestamp 與 {@code verify} 的時間窗判斷完全可預測、
 * 不會產生 flaky test。不打任何網路 / Google API。</p>
 *
 * <p>signingString 組法與 hao-for-you-service 端必須逐字元一致：
 * {@code timestamp + "\n" + METHOD + "\n" + path + "\n" + body}，method 以 {@code Locale.ROOT}
 * 轉大寫、path 不含 query string、GET 無 body 時 body 為空字串。</p>
 */
class InternalApiSignerTest {

    private static final String SECRET = "test-shared-secret";
    private static final long WINDOW = 300L;
    private static final long NOW_EPOCH = 1_700_000_000L;
    private static final Clock FIXED_NOW = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH), ZoneOffset.UTC);

    /** 以固定 NOW 時鐘、預設 secret/window 建立的受測 signer。 */
    private final InternalApiSigner signer = new InternalApiSigner(props(SECRET, WINDOW), FIXED_NOW);

    private static AppProperties props(String secret, Long window) {
        return new AppProperties(null, null, new AppProperties.Internal(secret, window), null, null);
    }

    /** 建立一個時鐘固定在指定 epoch 秒、其餘設定與受測 signer 相同的 signer（用來為特定 timestamp 產生合法簽章）。 */
    private static InternalApiSigner signerAt(long epochSecond) {
        return new InternalApiSigner(
            props(SECRET, WINDOW),
            Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    // ────────────────────────────────────────────────────────────
    // buildSigningString（組字串規則）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSigningString")
    class BuildSigningString {

        @Test
        @DisplayName("以換行串接 timestamp/METHOD/path/body 四段")
        void joinsFourPartsWithNewlines() {
            String result = InternalApiSigner.buildSigningString(
                "1700000000", "POST", "/v1/chat", "{\"a\":1}");

            assertThat(result).isEqualTo("1700000000\nPOST\n/v1/chat\n{\"a\":1}");
        }

        @Test
        @DisplayName("method 一律正規化為大寫（小寫輸入會被轉大寫）")
        void normalizesMethodToUpperCase() {
            String result = InternalApiSigner.buildSigningString(
                "1700000000", "post", "/v1/chat", "");

            assertThat(result).isEqualTo("1700000000\nPOST\n/v1/chat\n");
        }

        @Test
        @DisplayName("method 為 null 時視為空字串")
        void nullMethodBecomesEmpty() {
            String result = InternalApiSigner.buildSigningString(
                "1700000000", null, "/x", "b");

            assertThat(result).isEqualTo("1700000000\n\n/x\nb");
        }

        @Test
        @DisplayName("body 為 null 時視為空字串（GET 無 body 情境）")
        void nullBodyBecomesEmpty() {
            String result = InternalApiSigner.buildSigningString(
                "1700000000", "GET", "/google/auth", null);

            assertThat(result).isEqualTo("1700000000\nGET\n/google/auth\n");
        }
    }

    // ────────────────────────────────────────────────────────────
    // sign（產生標頭）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sign")
    class Sign {

        @Test
        @DisplayName("timestamp 取自注入時鐘的 epoch 秒，signature 為 64 字元小寫 hex")
        void producesFixedTimestampAndLowercaseHexSignature() {
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", "/v1/chat", "{\"x\":1}");

            assertThat(headers.timestamp()).isEqualTo(Long.toString(NOW_EPOCH));
            // HMAC-SHA256 → 32 bytes → 64 hex 字元，且為小寫
            assertThat(headers.signature()).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("method 大小寫正規化：POST 與 post 對相同 path/body 產生相同簽章")
        void methodCaseDoesNotAffectSignature() {
            String upper = signer.sign("POST", "/v1/chat", "body").signature();
            String lower = signer.sign("post", "/v1/chat", "body").signature();

            assertThat(lower).isEqualTo(upper);
        }
    }

    // ────────────────────────────────────────────────────────────
    // verify - round-trip 與竄改
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verify - round-trip 與竄改")
    class VerifyRoundTripAndTamper {

        @Test
        @DisplayName("同一個 signer sign 後 verify 應通過（round-trip）")
        void signThenVerifySucceeds() {
            String body = "{\"lineUserId\":\"U1\"}";
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", "/v1/chat", body);

            boolean valid = signer.verify(
                headers.timestamp(), headers.signature(), "POST", "/v1/chat", body);

            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("小寫 method 簽出的簽章，用大寫 method 驗證仍通過（兩端都正規化）")
        void verifyIsCaseInsensitiveOnMethod() {
            InternalApiSigner.SignedHeaders headers = signer.sign("post", "/v1/chat", "b");

            boolean valid = signer.verify(headers.timestamp(), headers.signature(), "POST", "/v1/chat", "b");

            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("簽章被竄改 → false")
        void tamperedSignatureFails() {
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", "/v1/chat", "b");
            String tampered = headers.signature().substring(0, 63) + "0";

            assertThat(signer.verify(headers.timestamp(), tampered, "POST", "/v1/chat", "b")).isFalse();
        }

        @Test
        @DisplayName("body 被竄改 → false")
        void tamperedBodyFails() {
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", "/v1/chat", "original");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/v1/chat", "tampered"))
                .isFalse();
        }

        @Test
        @DisplayName("path 被竄改 → false")
        void tamperedPathFails() {
            InternalApiSigner.SignedHeaders headers = signer.sign("POST", "/v1/chat", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/v1/other", "b"))
                .isFalse();
        }

        @Test
        @DisplayName("以不同密鑰簽出的簽章 → false")
        void differentSecretFails() {
            InternalApiSigner other = new InternalApiSigner(props("other-secret", WINDOW), FIXED_NOW);
            InternalApiSigner.SignedHeaders headers = other.sign("POST", "/v1/chat", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/v1/chat", "b"))
                .isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────
    // verify - 缺值 / 非數字 timestamp（fail-closed）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verify - 缺值與非法 timestamp")
    class VerifyMissingOrInvalid {

        @Test
        @DisplayName("timestamp 為 null → false")
        void nullTimestampFails() {
            assertThat(signer.verify(null, "sig", "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("timestamp 為空白 → false")
        void blankTimestampFails() {
            assertThat(signer.verify("   ", "sig", "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("signature 為 null → false")
        void nullSignatureFails() {
            assertThat(signer.verify(Long.toString(NOW_EPOCH), null, "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("signature 為空白 → false")
        void blankSignatureFails() {
            assertThat(signer.verify(Long.toString(NOW_EPOCH), "  ", "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("timestamp 非數字 → false（不可放行）")
        void nonNumericTimestampFails() {
            assertThat(signer.verify("not-a-number", "sig", "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("timestamp 為浮點數字串 → false")
        void decimalTimestampFails() {
            assertThat(signer.verify("1700000000.5", "sig", "POST", "/p", "b")).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────
    // verify - 時間窗（雙向邊界）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verify - 時間窗雙向邊界")
    class VerifyTimeWindow {

        @Test
        @DisplayName("過去邊界 |now - ts| == window → 通過（用 > 判斷）")
        void pastExactlyAtWindowPasses() {
            InternalApiSigner.SignedHeaders headers = signerAt(NOW_EPOCH - WINDOW).sign("POST", "/p", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/p", "b")).isTrue();
        }

        @Test
        @DisplayName("過去超窗 |now - ts| == window + 1 → 失敗")
        void pastBeyondWindowFails() {
            InternalApiSigner.SignedHeaders headers = signerAt(NOW_EPOCH - WINDOW - 1).sign("POST", "/p", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("未來邊界 |now - ts| == window → 通過（未來時間也要被納入窗判斷）")
        void futureExactlyAtWindowPasses() {
            InternalApiSigner.SignedHeaders headers = signerAt(NOW_EPOCH + WINDOW).sign("POST", "/p", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/p", "b")).isTrue();
        }

        @Test
        @DisplayName("未來超窗 |now - ts| == window + 1 → 失敗（擋住未來時間戳）")
        void futureBeyondWindowFails() {
            InternalApiSigner.SignedHeaders headers = signerAt(NOW_EPOCH + WINDOW + 1).sign("POST", "/p", "b");

            assertThat(signer.verify(headers.timestamp(), headers.signature(), "POST", "/p", "b")).isFalse();
        }

        @Test
        @DisplayName("window 設定為 null 時 fallback 為預設 300 秒")
        void nullWindowFallsBackToDefault300() {
            InternalApiSigner defaultWindowSigner =
                new InternalApiSigner(props(SECRET, null), FIXED_NOW);

            InternalApiSigner.SignedHeaders atBoundary = signerAt(NOW_EPOCH - 300).sign("POST", "/p", "b");
            InternalApiSigner.SignedHeaders beyond = signerAt(NOW_EPOCH - 301).sign("POST", "/p", "b");

            assertThat(defaultWindowSigner.verify(
                atBoundary.timestamp(), atBoundary.signature(), "POST", "/p", "b")).isTrue();
            assertThat(defaultWindowSigner.verify(
                beyond.timestamp(), beyond.signature(), "POST", "/p", "b")).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────
    // 設定邊界（空密鑰 / null Internal）—— 記錄實際行為，不據此改 production code
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("設定邊界")
    class ConfigEdgeCases {

        // 註：JDK 的 Mac 會拒絕長度為 0 的金鑰（javax.crypto 拋 IllegalArgumentException），
        // 因此當 INTERNAL_API_SECRET 未設定（預設空字串）時，sign/verify 會直接拋
        // IllegalStateException("Failed to compute HMAC signature")，而非「以空密鑰互簽」。
        // 這與交接文件「secret 為空仍可互通」的敘述不同；此處鎖住實際行為，不改動 production code。

        @Test
        @DisplayName("secret 為空字串時，sign 會拋 IllegalStateException（空金鑰無法初始化 Mac）")
        void emptySecretSignThrows() {
            InternalApiSigner emptySecret = new InternalApiSigner(props("", WINDOW), FIXED_NOW);

            assertThatThrownBy(() -> emptySecret.sign("POST", "/v1/chat", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to compute HMAC signature");
        }

        @Test
        @DisplayName("secret 為空字串時，verify（時間窗內、需計算 HMAC）同樣拋 IllegalStateException")
        void emptySecretVerifyThrows() {
            InternalApiSigner emptySecret = new InternalApiSigner(props("", WINDOW), FIXED_NOW);

            assertThatThrownBy(() ->
                emptySecret.verify(Long.toString(NOW_EPOCH), "a".repeat(64), "POST", "/v1/chat", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to compute HMAC signature");
        }

        @Test
        @DisplayName("Internal 設定整段為 null 時，secret 退回空字串、window 退回 300；不 NPE，但 sign 仍因空金鑰拋例外")
        void nullInternalDefaultsSecretToEmptyAndThrowsOnSign() {
            InternalApiSigner nullInternal =
                new InternalApiSigner(new AppProperties(null, null, null, null, null), FIXED_NOW);

            assertThatThrownBy(() -> nullInternal.sign("POST", "/v1/chat", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to compute HMAC signature");
        }
    }
}
