package com.haoforyou.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.service.ContextService;
import com.haoforyou.service.HaoServiceClient;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.core.properties.LineBotProperties;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
public class LineBotController {

    private static final Logger log = LoggerFactory.getLogger(LineBotController.class);

    private final MessagingApiClient messagingApiClient;
    private final LineBotProperties lineBotProperties;
    private final ObjectMapper objectMapper;
    private final ContextService contextService;
    private final HaoServiceClient haoServiceClient;
    private final String algorithm = "HmacSHA256";

    /**
     * Health check
     * GET /
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "message", "hao-for-you Java server is running",
            "status",  "ok"
        ));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> callback(
            @RequestHeader("x-line-signature") String signature,
            @RequestBody byte[] body) {

        // 驗證 LINE Webhook 的簽名
        if (!isValidSignature(signature, body)) {
            log.warn("Invalid LINE signature");
            return ResponseEntity.status(401).build();
        }

        CallbackRequest callbackRequest;
        try {
            callbackRequest = objectMapper.readValue(body, CallbackRequest.class);
        } catch (Exception e) {
            log.error("Failed to parse webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // 收到Line訊息並一個個處理
        callbackRequest.events().forEach(this::handleEvent);
        return ResponseEntity.ok().build();
    }

    private boolean isValidSignature(String signature, byte[] body) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(lineBotProperties.channelSecret().getBytes(), algorithm));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("Line Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private void handleEvent(Event event) {
        // 確認並處理文字訊息
        if (!(event instanceof MessageEvent messageEvent)) return;
        if (!(messageEvent.message() instanceof TextMessageContent textContent)) return;

        String userId = messageEvent.source().userId();
        String userText = textContent.text();
        String replyToken = messageEvent.replyToken();

        try {
            // 確保使用者存在（如果不存在就創建）
            contextService.getOrCreateUser(userId);
            String reply = haoServiceClient.generate(userText, userId);

            messagingApiClient.replyMessage(new ReplyMessageRequest(
                    replyToken,
                    List.of(new TextMessage(reply)),
                    false
            ));
        } catch (Exception e) {
            log.error("Error handling message from {}: {}", userId, e.getMessage(), e);
        }
    }
}
