package com.haoforyou.controller;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.GoogleApiResponse;
import com.haoforyou.dto.GoogleMailApiRequest;
import com.haoforyou.dto.MailData;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.GoogleAuthService;
import com.haoforyou.service.MailService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Tag(name = "Gmail", description = "Gmail 郵件查詢")
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

    @Operation(summary = "查詢 Gmail 郵件", description = "使用者需先連結 Google 帳號，才能查詢 Gmail 郵件")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查詢成功，data 為 MailData（郵件列表）"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "使用者尚未連結 Google 帳號，或 token 已失效需重新連結",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "尚未連結 Google 帳號",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"GOOGLE_NOT_LINKED\", \"message\": \"此用戶尚未連結 Google 帳號\"}}"
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Google API 呼叫失敗",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"GOOGLE_API_ERROR\", \"message\": \"Google API 呼叫失敗：...\"}}"
                )
            )
        )
    })
    @PostMapping("/query")
    public ResponseEntity<GoogleApiResponse<MailData>> query(@RequestBody GoogleMailApiRequest req) {
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
