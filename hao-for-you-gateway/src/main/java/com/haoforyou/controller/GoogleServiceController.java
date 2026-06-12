package com.haoforyou.controller;

import com.haoforyou.service.GoogleAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    @GetMapping("/auth")
    public ResponseEntity<Map<String, String>> startAuth(
        @RequestParam("line_user_id") String lineUserId
    ) {
        String authUrl = googleAuthService.buildAuthUrl(lineUserId);
        return ResponseEntity.ok(Map.of("auth_url", authUrl));
    }

    /**
     * Google OAuth callback
     * GET /auth/google/callback?code=xxx&state=line_user_id
     */
    @GetMapping(value = "/auth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> googleCallback(
        @RequestParam("code")  String code,
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
