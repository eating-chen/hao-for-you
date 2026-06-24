package com.haoforyou.controller;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.GoogleApiRequest;
import com.haoforyou.dto.GoogleApiResponse;
import com.haoforyou.dto.MailData;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.GoogleAuthService;
import com.haoforyou.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/google/mail")
public class MailController {

    private static final Logger log = LoggerFactory.getLogger(MailController.class);

    private final GoogleAuthService googleAuthService;
    private final MailService mailService;

    public MailController(GoogleAuthService googleAuthService, MailService mailService) {
        this.googleAuthService = googleAuthService;
        this.mailService = mailService;
    }

    @PostMapping("/query")
    public ResponseEntity<GoogleApiResponse<?>> query(@RequestBody GoogleApiRequest req) {
        Optional<GoogleToken> tokenOpt = googleAuthService.getToken(req.getLineUserId());
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("GOOGLE_NOT_LINKED", "此用戶尚未連結 Google 帳號"));
        }
        try {
            MailData data = mailService.query(tokenOpt.get(), req);
            return ResponseEntity.ok(GoogleApiResponse.ok(data));
        } catch (TokenResponseException e) {
            log.warn("Gmail token refresh failed for user={}", req.getLineUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("TOKEN_REFRESH_FAILED", "Google 授權已失效，請重新連結帳號"));
        } catch (Exception e) {
            log.error("Gmail query error for user={}", req.getLineUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GoogleApiResponse.fail("GOOGLE_API_ERROR", "Google API 呼叫失敗：" + e.getMessage()));
        }
    }
}
