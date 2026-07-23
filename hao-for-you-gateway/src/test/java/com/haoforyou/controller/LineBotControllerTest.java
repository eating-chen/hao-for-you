package com.haoforyou.controller;

import com.haoforyou.service.ContextService;
import com.haoforyou.service.HaoServiceClient;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.core.properties.LineBotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LineBotController} 的單元測試。
 *
 * 使用 {@code @WebMvcTest} 只載入 web 層（LineBotController + MockMvc 基礎設施），
 * 並以 {@code @MockBean} 取代所有建構子依賴，避免打到真正的 LINE / DB / hao-for-you-service。
 *
 * 簽名驗證：{@code isValidSignature} 是用 request body 的原始 bytes 計算 HmacSHA256，
 * 因此測試一律用 byte[] 送出 request body、並對「完全相同」的 bytes 計算簽名，
 * 避免 MockMvc 對 String content 做字元編碼轉換造成簽名不吻合（尤其是中文內容）。
 */
@WebMvcTest(LineBotController.class)
class LineBotControllerTest {

    private static final String CHANNEL_SECRET = "test-channel-secret";
    private static final String SIGNATURE_HEADER = "x-line-signature";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessagingApiClient messagingApiClient;

    @MockBean
    private LineBotProperties lineBotProperties;

    @MockBean
    private ContextService contextService;

    @MockBean
    private HaoServiceClient haoServiceClient;

    @BeforeEach
    void setUp() {
        when(lineBotProperties.channelSecret()).thenReturn(CHANNEL_SECRET);
    }

    // ==================== GET /health ====================

    @Test
    @DisplayName("GET /health：hao-for-you-service 連通時，回傳整體 ok 且 service.status=ok")
    void health_whenServiceUp_returnsOk() throws Exception {
        when(haoServiceClient.checkHealth()).thenReturn(true);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("hao-for-you Java server is running"))
                .andExpect(jsonPath("$.service.status").value("ok"));
    }

    @Test
    @DisplayName("GET /health：hao-for-you-service 斷線時，gateway 本身仍回傳 ok，但 service.status=down")
    void health_whenServiceDown_returnsServiceDown() throws Exception {
        when(haoServiceClient.checkHealth()).thenReturn(false);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service.status").value("down"));
    }

    // ==================== POST /webhook：簽名驗證 ====================

    @Test
    @DisplayName("POST /webhook：簽名錯誤時回傳 401，且完全不觸發任何後續處理")
    void webhook_withInvalidSignature_returns401() throws Exception {
        byte[] body = utf8(callbackBody(textMessageEvent("U1", "reply-token-1", "msg1", "Hello")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, "clearly-invalid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    @Test
    @DisplayName("POST /webhook：缺少 x-line-signature header 時，Spring 直接回傳 400")
    void webhook_withoutSignatureHeader_returns400() throws Exception {
        byte[] body = utf8(callbackBody(textMessageEvent("U1", "reply-token-1", "msg1", "Hello")));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    @Test
    @DisplayName("POST /webhook：channelSecret 為 null 導致簽名驗證內部拋例外時，仍會被吞掉並回傳 401")
    void webhook_whenChannelSecretIsNull_returns401() throws Exception {
        when(lineBotProperties.channelSecret()).thenReturn(null);
        byte[] body = utf8(callbackBody(textMessageEvent("U1", "reply-token-1", "msg1", "Hello")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, "any-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    // ==================== POST /webhook：body 解析 ====================

    @Test
    @DisplayName("POST /webhook：簽名正確但 body 不是合法 JSON 時回傳 400")
    void webhook_withMalformedBody_returns400() throws Exception {
        byte[] body = utf8("{not-valid-json");

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    @Test
    @DisplayName("POST /webhook：events 為空陣列（LINE 驗證用途）時回傳 200，且不觸發任何處理")
    void webhook_withEmptyEvents_returns200AndDoesNothing() throws Exception {
        byte[] body = utf8(callbackBody());

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    // ==================== POST /webhook：事件處理 ====================

    @Test
    @DisplayName("POST /webhook：文字訊息 happy path，建立/取得使用者、呼叫 LLM 並用回覆內容回 LINE")
    void webhook_withTextMessageEvent_repliesWithGeneratedText() throws Exception {
        when(haoServiceClient.generate(eq("嗨 Hao"), eq("U1234567890"))).thenReturn("哈囉！有什麼可以幫你？");

        byte[] body = utf8(callbackBody(textMessageEvent("U1234567890", "reply-token-1", "msg-1", "嗨 Hao")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(contextService).getOrCreateUser("U1234567890");
        verify(haoServiceClient).generate("嗨 Hao", "U1234567890");

        ArgumentCaptor<ReplyMessageRequest> captor = ArgumentCaptor.forClass(ReplyMessageRequest.class);
        verify(messagingApiClient).replyMessage(captor.capture());

        ReplyMessageRequest actual = captor.getValue();
        assertThat(actual.replyToken()).isEqualTo("reply-token-1");
        assertThat(actual.notificationDisabled()).isFalse();
        assertThat(actual.messages()).hasSize(1);
        Message message = actual.messages().get(0);
        assertThat(message).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) message).text()).isEqualTo("哈囉！有什麼可以幫你？");
    }

    @Test
    @DisplayName("POST /webhook：非文字訊息（貼圖）事件會被忽略，不建立使用者、不呼叫 LLM、不回覆")
    void webhook_withNonTextMessageEvent_isIgnored() throws Exception {
        byte[] body = utf8(callbackBody(stickerMessageEvent("U1", "reply-token-sticker", "msg-2")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    @Test
    @DisplayName("POST /webhook：非訊息事件（例如加好友 follow 事件）會被忽略")
    void webhook_withNonMessageEvent_isIgnored() throws Exception {
        byte[] body = utf8(callbackBody(followEvent("U1", "reply-token-follow")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(contextService, haoServiceClient, messagingApiClient);
    }

    @Test
    @DisplayName("POST /webhook：一次收到多個事件時，非文字訊息事件被跳過，只有文字訊息事件被處理")
    void webhook_withMultipleEvents_onlyHandlesTextMessage() throws Exception {
        when(haoServiceClient.generate(anyString(), anyString())).thenReturn("回覆內容");

        byte[] body = utf8(callbackBody(
                followEvent("U2", "reply-token-follow-2"),
                textMessageEvent("U3", "reply-token-3", "msg-3", "第二則訊息")
        ));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(contextService, times(1)).getOrCreateUser("U3");
        verify(haoServiceClient, times(1)).generate("第二則訊息", "U3");
        verify(messagingApiClient, times(1)).replyMessage(any(ReplyMessageRequest.class));
        verify(contextService, never()).getOrCreateUser("U2");
    }

    @Test
    @DisplayName("POST /webhook：處理事件時若拋出例外（例如呼叫 LLM 失敗），例外會被吞掉，仍回傳 200 且不回覆訊息")
    void webhook_whenHandlingThrows_stillReturns200AndSkipsReply() throws Exception {
        when(haoServiceClient.generate(anyString(), anyString())).thenThrow(new RuntimeException("LLM 服務炸了"));

        byte[] body = utf8(callbackBody(textMessageEvent("U4", "reply-token-4", "msg-4", "會出錯的訊息")));

        mockMvc.perform(post("/webhook")
                        .header(SIGNATURE_HEADER, sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(contextService).getOrCreateUser("U4");
        verify(messagingApiClient, never()).replyMessage(any(ReplyMessageRequest.class));
    }

    // ==================== helpers ====================

    private byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] bodyBytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(utf8(CHANNEL_SECRET), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(bodyBytes));
    }

    private String callbackBody(String... events) {
        return """
                {
                  "destination": "Uabcdef1234567890abcdef1234567890",
                  "events": [%s]
                }
                """.formatted(String.join(",", events));
    }

    private String textMessageEvent(String userId, String replyToken, String messageId, String text) {
        return """
                {
                  "type": "message",
                  "mode": "active",
                  "timestamp": 1626874007000,
                  "webhookEventId": "01ARZ3NDEKTSV4RRFFQ69G5F-%s",
                  "deliveryContext": { "isRedelivery": false },
                  "replyToken": "%s",
                  "source": { "type": "user", "userId": "%s" },
                  "message": { "type": "text", "id": "%s", "text": "%s" }
                }
                """.formatted(messageId, replyToken, userId, messageId, text);
    }

    private String stickerMessageEvent(String userId, String replyToken, String messageId) {
        return """
                {
                  "type": "message",
                  "mode": "active",
                  "timestamp": 1626874007000,
                  "webhookEventId": "01ARZ3NDEKTSV4RRFFQ69G5F-%s",
                  "deliveryContext": { "isRedelivery": false },
                  "replyToken": "%s",
                  "source": { "type": "user", "userId": "%s" },
                  "message": { "type": "sticker", "id": "%s", "packageId": "1", "stickerId": "1" }
                }
                """.formatted(messageId, replyToken, userId, messageId);
    }

    private String followEvent(String userId, String replyToken) {
        return """
                {
                  "type": "follow",
                  "mode": "active",
                  "timestamp": 1626874007000,
                  "webhookEventId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                  "deliveryContext": { "isRedelivery": false },
                  "replyToken": "%s",
                  "source": { "type": "user", "userId": "%s" }
                }
                """.formatted(replyToken, userId);
    }
}
