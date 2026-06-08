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

@RequiredArgsConstructor
@RestController
public class LineBotController {

    private static final Logger log = LoggerFactory.getLogger(LineBotController.class);

    private final MessagingApiClient messagingApiClient;
    private final LineBotProperties lineBotProperties;
    private final ObjectMapper objectMapper;
    private final ContextService contextService;
    private final HaoServiceClient haoServiceClient;

    @PostMapping("/webhook")
    public ResponseEntity<Void> callback(
            @RequestHeader("x-line-signature") String signature,
            @RequestBody byte[] body) {

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

        callbackRequest.events().forEach(this::handleEvent);
        return ResponseEntity.ok().build();
    }

    private boolean isValidSignature(String signature, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(lineBotProperties.channelSecret().getBytes(), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private void handleEvent(Event event) {
        if (!(event instanceof MessageEvent messageEvent)) return;
        if (!(messageEvent.message() instanceof TextMessageContent textContent)) return;

        String userId = messageEvent.source().userId();
        String userText = textContent.text();
        String replyToken = messageEvent.replyToken();

        try {
            contextService.getOrCreateUser(userId);
            String reply = haoServiceClient.generate(userText);

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
