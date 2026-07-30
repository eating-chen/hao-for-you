package com.haoforyou.controller;

import com.haoforyou.service.GoogleAuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Google OAuth", description = "Google 授權流程（OAuth2 PKCE）")
@RestController
@RequestMapping("/google")
public class GoogleServiceController {

    private static final Logger log = LoggerFactory.getLogger(GoogleServiceController.class);
    private final GoogleAuthService googleAuthService;

    public GoogleServiceController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    /**
     * 產生 Google OAuth 授權連結
     * GET /auth/google?line_user_id=xxx
     */
    @Operation(
        summary = "產生 Google OAuth 授權連結",
        description = "使用者點擊後會跳轉到 Google OAuth 頁面，並完成授權。"
            + "此 endpoint 僅供 hao-for-you-service 內部呼叫，需帶跨服務 HMAC 驗證 header。",
        parameters = {
            @Parameter(
                name = "X-Timestamp", in = ParameterIn.HEADER, required = true,
                description = "Unix epoch 秒，跨服務 HMAC 驗證用，需與 X-Signature 搭配"
            ),
            @Parameter(
                name = "X-Signature", in = ParameterIn.HEADER, required = true,
                description = "hex(HMAC-SHA256(INTERNAL_API_SECRET, X-Timestamp+\"\\n\"+METHOD+\"\\n\"+path+\"\\n\"+body))"
            )
        }
    )
    @ApiResponse(
        responseCode = "200",
        description = "成功產生授權連結",
        content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(
                value = "{\"auth_url\": \"https://accounts.google.com/o/oauth2/v2/auth?client_id=...&state=Uxxxx\"}"
            )
        )
    )
    @GetMapping("/auth")
    public ResponseEntity<Map<String, String>> startAuth(
        @Parameter(description = "LINE 使用者 ID", required = true)
        @RequestParam("line_user_id") String lineUserId
    ) {
        String authUrl = googleAuthService.buildAuthUrl(lineUserId);
        return ResponseEntity.ok(Map.of("auth_url", authUrl));
    }

    /**
     * Google OAuth callback
     * GET /auth/google/callback?code=xxx&state=line_user_id
     */
    @Operation(summary = "Google OAuth 回調", description = "Google OAuth 授權完成後的回調接口,由 Google 自動帶入 code 與 state 參數")
    @GetMapping(value = "/auth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> googleCallback(
        @Parameter(description = "Google 授權碼，由 Google 自動帶入", required = true)
        @RequestParam("code") String code,
        @Parameter(description = "LINE 使用者 ID，由 /google/auth 帶入的 state 參數", required = true)
        @RequestParam("state") String lineUserId
    ) {
        try {
            googleAuthService.exchangeAndSave(code, lineUserId);
            String email = googleAuthService.getToken(lineUserId)
                .map(t -> t.getGoogleEmail()).orElse("你的帳號");

            // 主動推播 LINE 通知
            googleAuthService.pushLineMessage(
                lineUserId,
                "✅ 已成功連結 Google 帳號：" + email + "\n現在你可以查詢或新增行事曆了！"
            );

            return ResponseEntity.ok("""
                <html>
                  <head><meta charset="utf-8"></head>
                  <body style="font-family:sans-serif;text-align:center;padding:60px;">
                    <h2>✅ Google 帳號授權成功！</h2>
                    <p>請回到 LINE 繼續使用 Hao。</p>
                <p style="color:gray;font-size:14px;">此頁面可以關閉。</p>
                  </body>
                </html>
                """);

        } catch (Exception e) {
            log.error("Google OAuth callback error", e);
            return ResponseEntity.internalServerError().body("""
                <html>
                  <head><meta charset="utf-8"></head>
                  <body style="font-family:sans-serif;text-align:center;padding:60px;">
                    <h2>❌ 授權失敗</h2>
                    <p>請回到 LINE 重新嘗試。</p>
                  </body>
                </html>
                """);
        }
    }
}
