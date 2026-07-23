package com.haoforyou.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.haoforyou.dto.GoogleMailApiRequest;
import com.haoforyou.dto.MailData;
import com.haoforyou.dto.MessageSummary;
import com.haoforyou.model.GoogleToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MailService} 的單元測試。
 *
 * <p>{@code MailService} 是純邏輯的 {@code @Service}，唯一的建構子依賴是
 * {@link GoogleAuthService}，因此用 {@code @ExtendWith(MockitoExtension.class)} +
 * {@code @Mock} GoogleAuthService + 手動建構受測物件。{@link GoogleAuthService} 回傳的
 * Google SDK {@link Gmail} 物件與其 fluent API（users().messages().list/get(...)
 * .setXxx(...).execute()）皆以 Mockito 手動 mock 多層呼叫鏈，全程不打真正的 Gmail API。</p>
 *
 * <p>對應設計文件：docs/specs/2026-06-15-google-api-endpoints-design.md
 * 「1. Gmail 查詢」章節；文件中僅列出 keyword/from/unread_only/limit，但現行程式碼
 * ({@code buildQuery}/{@code appendDateFilter}) 額外支援 date/date_to 區間查詢
 * （Gmail 的 after: 含當天、before: 不含當天，因此區間結束日需 +1 天），測試依實際程式碼行為撰寫，
 * 涵蓋 buildQuery 每個欄位分支與組合、多筆訊息、巢狀 multipart 內文擷取等。</p>
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    private static final String LINE_USER_ID = "Uabc1234567890";

    @Mock
    private GoogleAuthService googleAuthService;

    private MailService mailService;

    private Gmail gmail;
    private Gmail.Users users;
    private Gmail.Users.Messages messages;

    @BeforeEach
    void setUp() throws Exception {
        mailService = new MailService(googleAuthService);

        gmail = mock(Gmail.class);
        users = mock(Gmail.Users.class);
        messages = mock(Gmail.Users.Messages.class);
        lenient().when(gmail.users()).thenReturn(users);
        lenient().when(users.messages()).thenReturn(messages);
        lenient().when(googleAuthService.buildGmailService(any())).thenReturn(gmail);
    }

    private GoogleToken token() {
        return new GoogleToken(LINE_USER_ID, "access-token", "refresh-token", null, "user@gmail.com");
    }

    private GoogleMailApiRequest baseRequest() {
        GoogleMailApiRequest req = new GoogleMailApiRequest();
        req.setLineUserId(LINE_USER_ID);
        return req;
    }

    /** 建立 messages().list(...) 的 mock，並讓 fluent setter 回傳自己（lenient，因並非每個測試都需要用到）。 */
    private Gmail.Users.Messages.List mockListRequest() {
        Gmail.Users.Messages.List listRequest = mock(Gmail.Users.Messages.List.class);
        lenient().when(listRequest.setQ(any())).thenReturn(listRequest);
        lenient().when(listRequest.setMaxResults(any())).thenReturn(listRequest);
        return listRequest;
    }

    /** 建立 messages().get("me", id) 的 mock，回傳指定的 Message。 */
    private void stubGetRequest(String id, Message toReturn) throws Exception {
        Gmail.Users.Messages.Get getRequest = mock(Gmail.Users.Messages.Get.class);
        lenient().when(getRequest.setFormat(any())).thenReturn(getRequest);
        when(messages.get(eq("me"), eq(id))).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(toReturn);
    }

    private Message messageRef(String id) {
        return new Message().setId(id);
    }

    private String encode(String text) {
        return Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private Message fullMessage(String id, String subject, String from, String date,
                                 String snippet, String bodyText) {
        Message msg = new Message();
        msg.setId(id);
        msg.setSnippet(snippet);

        MessagePart payload = new MessagePart();
        List<MessagePartHeader> headers = new ArrayList<>();
        if (subject != null) headers.add(new MessagePartHeader().setName("Subject").setValue(subject));
        if (from != null) headers.add(new MessagePartHeader().setName("From").setValue(from));
        if (date != null) headers.add(new MessagePartHeader().setName("Date").setValue(date));
        payload.setHeaders(headers);

        if (bodyText != null) {
            payload.setMimeType("text/plain");
            payload.setBody(new MessagePartBody().setData(encode(bodyText)));
        }
        msg.setPayload(payload);
        return msg;
    }

    // ────────────────────────────────────────────────────────────
    // query() - happy path / 訊息內容對應
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - happy path 與訊息內容對應")
    class HappyPath {

        @Test
        @DisplayName("單筆郵件含完整 headers/snippet/內文時，正確對應為 MessageSummary")
        void query_singleMessage_mapsAllFieldsCorrectly() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"))));

            Message full = fullMessage("msg1", "明天開會通知", "boss@gmail.com", "2026-06-15",
                "請準時出席...", "各位同事，會議將於明天上午舉行...");
            stubGetRequest("msg1", full);

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages()).hasSize(1);
            MessageSummary summary = data.messages().get(0);
            assertThat(summary.id()).isEqualTo("msg1");
            assertThat(summary.subject()).isEqualTo("明天開會通知");
            assertThat(summary.from()).isEqualTo("boss@gmail.com");
            assertThat(summary.date()).isEqualTo("2026-06-15");
            assertThat(summary.snippet()).isEqualTo("請準時出席...");
            assertThat(summary.body()).isEqualTo("各位同事，會議將於明天上午舉行...");
        }

        @Test
        @DisplayName("多筆郵件時，依序對每筆呼叫 messages().get 並對應為多筆 MessageSummary")
        void query_multipleMessages_callsGetForEachAndMapsAll() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"), messageRef("msg2"))));

            stubGetRequest("msg1", fullMessage("msg1", "主旨一", "a@gmail.com", "2026-06-01", "摘要一", "內文一"));
            stubGetRequest("msg2", fullMessage("msg2", "主旨二", "b@gmail.com", "2026-06-02", "摘要二", "內文二"));

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages()).hasSize(2);
            assertThat(data.messages().get(0).id()).isEqualTo("msg1");
            assertThat(data.messages().get(1).id()).isEqualTo("msg2");
            verify(messages, times(1)).get("me", "msg1");
            verify(messages, times(1)).get("me", "msg2");
        }

        @Test
        @DisplayName("headers 為 null 時，subject/from/date 皆回傳空字串")
        void query_nullHeaders_defaultsToEmptyStrings() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"))));

            Message msg = new Message().setId("msg1").setSnippet(null);
            msg.setPayload(new MessagePart().setHeaders(null));
            stubGetRequest("msg1", msg);

            MailData data = mailService.query(token, baseRequest());

            MessageSummary summary = data.messages().get(0);
            assertThat(summary.subject()).isEqualTo("");
            assertThat(summary.from()).isEqualTo("");
            assertThat(summary.date()).isEqualTo("");
            assertThat(summary.snippet()).isEqualTo("");
            assertThat(summary.body()).isEqualTo("");
        }

        @Test
        @DisplayName("payload 為 null 時，subject/from/date/body 皆回傳空字串，snippet 仍取得原值")
        void query_nullPayload_defaultsSafely() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"))));

            Message msg = new Message().setId("msg1").setSnippet("有摘要無內文");
            // payload 未設定，維持 null
            stubGetRequest("msg1", msg);

            MailData data = mailService.query(token, baseRequest());

            MessageSummary summary = data.messages().get(0);
            assertThat(summary.subject()).isEqualTo("");
            assertThat(summary.snippet()).isEqualTo("有摘要無內文");
            assertThat(summary.body()).isEqualTo("");
        }

        @Test
        @DisplayName("內文為巢狀 multipart 時，遞迴找到第一個 text/plain part 取出內文")
        void query_nestedMultipart_extractsPlainTextBodyRecursively() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"))));

            // 巢狀結構：top(multipart/alternative) -> [ text/html(無內容), multipart/mixed -> [ text/plain ] ]
            MessagePart htmlPart = new MessagePart().setMimeType("text/html");

            MessagePart plainPart = new MessagePart()
                .setMimeType("text/plain")
                .setBody(new MessagePartBody().setData(encode("巢狀內文")));

            MessagePart mixedPart = new MessagePart()
                .setMimeType("multipart/mixed")
                .setParts(List.of(plainPart));

            MessagePart top = new MessagePart()
                .setMimeType("multipart/alternative")
                .setHeaders(List.of(new MessagePartHeader().setName("Subject").setValue("巢狀信件")))
                .setParts(List.of(htmlPart, mixedPart));

            Message msg = new Message().setId("msg1").setSnippet("摘要").setPayload(top);
            stubGetRequest("msg1", msg);

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages().get(0).body()).isEqualTo("巢狀內文");
        }

        @Test
        @DisplayName("找不到任何 text/plain part 時，內文回傳空字串")
        void query_noPlainTextPart_returnsEmptyBody() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"))));

            MessagePart htmlOnly = new MessagePart()
                .setMimeType("text/html")
                .setBody(new MessagePartBody().setData(encode("<p>html</p>")));
            Message msg = new Message().setId("msg1").setSnippet("摘要").setPayload(htmlOnly);
            stubGetRequest("msg1", msg);

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages().get(0).body()).isEqualTo("");
        }
    }

    // ────────────────────────────────────────────────────────────
    // query() - buildQuery / appendDateFilter 分支
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - 查詢條件組成（buildQuery）分支")
    class BuildQueryBranches {

        private String captureQ(GoogleMailApiRequest req) throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of()));

            mailService.query(token, req);

            ArgumentCaptor<String> qCaptor = ArgumentCaptor.forClass(String.class);
            verify(listRequest).setQ(qCaptor.capture());
            return qCaptor.getValue();
        }

        @Test
        @DisplayName("只有 keyword 時，q 只包含 keyword")
        void query_onlyKeyword() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setKeyword("會議");

            assertThat(captureQ(req)).isEqualTo("會議");
        }

        @Test
        @DisplayName("只有 from 時，q 只包含 from:")
        void query_onlyFrom() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setFrom("boss@gmail.com");

            assertThat(captureQ(req)).isEqualTo("from:boss@gmail.com");
        }

        @Test
        @DisplayName("unread_only=true 時，q 包含 is:unread")
        void query_unreadOnlyTrue() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setUnreadOnly(true);

            assertThat(captureQ(req)).isEqualTo("is:unread");
        }

        @Test
        @DisplayName("unread_only=false 或未帶時，q 不包含 is:unread")
        void query_unreadOnlyFalseOrAbsent() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setUnreadOnly(false);
            req.setKeyword("測試");

            assertThat(captureQ(req)).isEqualTo("測試");
        }

        @Test
        @DisplayName("只有 date（無 date_to）時，after/before 皆對應同一天，before 為隔日")
        void query_onlyDate_appliesSameDayRange() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setDate("2026-06-01");

            assertThat(captureQ(req)).isEqualTo("after:2026/06/01 before:2026/06/02");
        }

        @Test
        @DisplayName("date 與 date_to 皆有值時，after 對應 date，before 對應 date_to 隔日")
        void query_dateAndDateTo_appliesRange() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setDate("2026-06-01");
            req.setDateTo("2026-06-05");

            assertThat(captureQ(req)).isEqualTo("after:2026/06/01 before:2026/06/06");
        }

        @Test
        @DisplayName("date、date_to 皆未指定時，q 不包含 after/before")
        void query_noDate_noDateFilterApplied() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setKeyword("會議");

            assertThat(captureQ(req)).isEqualTo("會議");
        }

        @Test
        @DisplayName("keyword/from/unread_only/date 皆有值時，依序組成完整 q 字串")
        void query_allFieldsCombined_composesFullQueryString() throws Exception {
            GoogleMailApiRequest req = baseRequest();
            req.setKeyword("會議");
            req.setFrom("boss@gmail.com");
            req.setUnreadOnly(true);
            req.setDate("2026-06-01");
            req.setDateTo("2026-06-05");

            assertThat(captureQ(req))
                .isEqualTo("會議 from:boss@gmail.com is:unread after:2026/06/01 before:2026/06/06");
        }
    }

    // ────────────────────────────────────────────────────────────
    // query() - limit
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - limit 分支")
    class LimitBranches {

        @Test
        @DisplayName("limit 未指定時，預設帶入 5")
        void query_withoutLimit_defaultsToFive() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of()));

            mailService.query(token, baseRequest());

            verify(listRequest).setMaxResults(5L);
        }

        @Test
        @DisplayName("limit 有指定時，帶入指定值")
        void query_withLimit_usesProvidedValue() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of()));

            GoogleMailApiRequest req = baseRequest();
            req.setLimit(20);

            mailService.query(token, req);

            verify(listRequest).setMaxResults(20L);
        }
    }

    // ────────────────────────────────────────────────────────────
    // query() - 空結果
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - 查無郵件")
    class EmptyResult {

        @Test
        @DisplayName("getMessages() 為 null 時，回傳空的 MailData，且不呼叫 messages().get")
        void query_nullMessages_returnsEmptyMailData() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(null));

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages()).isEmpty();
            verify(messages, never()).get(anyString(), anyString());
        }

        @Test
        @DisplayName("getMessages() 為空陣列時，回傳空的 MailData，且不呼叫 messages().get")
        void query_emptyMessagesList_returnsEmptyMailData() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new ListMessagesResponse().setMessages(List.of()));

            MailData data = mailService.query(token, baseRequest());

            assertThat(data.messages()).isEmpty();
            verify(messages, never()).get(anyString(), anyString());
        }
    }

    // ────────────────────────────────────────────────────────────
    // query() - 例外處理
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - 例外處理")
    class Exceptions {

        @Test
        @DisplayName("buildGmailService 拋出例外時，例外直接往上拋")
        void query_buildGmailServiceThrows_propagates() throws Exception {
            GoogleToken token = token();
            when(googleAuthService.buildGmailService(token))
                .thenThrow(new java.io.IOException("無法建立 Gmail service"));

            assertThatThrownBy(() -> mailService.query(token, baseRequest()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("無法建立 Gmail service");
        }

        @Test
        @DisplayName("messages().list(...).execute() 拋出例外時，例外直接往上拋")
        void query_listExecuteThrows_propagates() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenThrow(new java.io.IOException("quota exceeded"));

            assertThatThrownBy(() -> mailService.query(token, baseRequest()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("quota exceeded");
        }

        @Test
        @DisplayName("其中一封信的 messages().get(...).execute() 拋出例外時，例外直接往上拋")
        void query_getExecuteThrows_propagates() throws Exception {
            GoogleToken token = token();
            Gmail.Users.Messages.List listRequest = mockListRequest();
            when(messages.list("me")).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(
                new ListMessagesResponse().setMessages(List.of(messageRef("msg1"), messageRef("msg2"))));

            stubGetRequest("msg1", fullMessage("msg1", "主旨一", "a@gmail.com", "2026-06-01", "摘要一", "內文一"));

            Gmail.Users.Messages.Get failingGet = mock(Gmail.Users.Messages.Get.class);
            lenient().when(failingGet.setFormat(any())).thenReturn(failingGet);
            when(messages.get("me", "msg2")).thenReturn(failingGet);
            doThrow(new java.io.IOException("network down")).when(failingGet).execute();

            assertThatThrownBy(() -> mailService.query(token, baseRequest()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("network down");

            verify(messages, times(1)).get("me", "msg1");
            verify(messages, times(1)).get("me", "msg2");
        }
    }
}
