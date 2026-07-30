package com.haoforyou.security;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CachedBodyHttpServletRequest} 的單元測試。
 *
 * <p>驗證 body 被快取後可被「重複」讀取——這是驗簽 filter 先讀 body 重算簽章、下游 controller
 * 仍能再讀同一份 body 的關鍵。用 {@link MockHttpServletRequest} 當作底層來源，模擬只能讀一次的
 * request input stream，包一層 {@code CachedBodyHttpServletRequest} 後驗證多次讀取皆得到相同內容。</p>
 */
class CachedBodyHttpServletRequestTest {

    private static final String BODY = "{\"lineUserId\":\"U1\",\"msg\":\"哈囉世界\"}";

    private MockHttpServletRequest source(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    @DisplayName("getCachedBody 回傳與原始 request 完全一致的位元組")
    void cachedBodyMatchesOriginal() throws Exception {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(source(BODY));

        assertThat(new String(wrapped.getCachedBody(), StandardCharsets.UTF_8)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("getInputStream 可被讀取兩次（各自為新 stream），皆得到相同內容")
    void inputStreamCanBeReadRepeatedly() throws Exception {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(source(BODY));

        String first = StreamUtils.copyToString(wrapped.getInputStream(), StandardCharsets.UTF_8);
        String second = StreamUtils.copyToString(wrapped.getInputStream(), StandardCharsets.UTF_8);

        assertThat(first).isEqualTo(BODY);
        assertThat(second).isEqualTo(BODY);
    }

    @Test
    @DisplayName("getReader 讀出的內容與 body 一致")
    void readerReturnsBody() throws Exception {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(source(BODY));

        String content = StreamUtils.copyToString(
            wrapped.getInputStream(), StandardCharsets.UTF_8);
        String viaReader = wrapped.getReader().lines()
            .reduce("", (a, b) -> a + b);

        // reader 讀取的是同一份快取內容（此處 body 無換行，可直接比對）
        assertThat(viaReader).isEqualTo(content);
    }

    @Test
    @DisplayName("讀完後 ServletInputStream.isFinished 為 true，isReady 恆為 true")
    void inputStreamReportsFinishedAfterFullyRead() throws Exception {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(source(BODY));

        ServletInputStream in = wrapped.getInputStream();
        assertThat(in.isReady()).isTrue();
        StreamUtils.copyToString(in, StandardCharsets.UTF_8);

        assertThat(in.isFinished()).isTrue();
    }

    @Test
    @DisplayName("空 body 也能被快取，讀出為空字串且 isFinished 立即為 true")
    void emptyBodyIsHandled() throws Exception {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(source(""));

        ServletInputStream in = wrapped.getInputStream();

        assertThat(in.isFinished()).isTrue();
        assertThat(StreamUtils.copyToString(in, StandardCharsets.UTF_8)).isEmpty();
    }
}
