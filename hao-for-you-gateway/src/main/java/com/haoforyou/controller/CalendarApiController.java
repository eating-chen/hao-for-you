package com.haoforyou.controller;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.CalendarData;
import com.haoforyou.dto.GoogleApiRequest;
import com.haoforyou.dto.GoogleApiResponse;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.CalendarApiService;
import com.haoforyou.service.GoogleAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/google/calendar")
public class CalendarApiController {

    private static final Logger log = LoggerFactory.getLogger(CalendarApiController.class);

    private final GoogleAuthService googleAuthService;
    private final CalendarApiService calendarApiService;

    public CalendarApiController(GoogleAuthService googleAuthService, CalendarApiService calendarApiService) {
        this.googleAuthService = googleAuthService;
        this.calendarApiService = calendarApiService;
    }

    @PostMapping("/query")
    public ResponseEntity<GoogleApiResponse<?>> query(@RequestBody GoogleApiRequest req) {
        Optional<GoogleToken> tokenOpt = googleAuthService.getToken(req.getLineUserId());
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("GOOGLE_NOT_LINKED", "此用戶尚未連結 Google 帳號"));
        }
        try {
            CalendarData data = calendarApiService.query(tokenOpt.get(), req);
            return ResponseEntity.ok(GoogleApiResponse.ok(data));
        } catch (TokenResponseException e) {
            log.warn("Calendar token refresh failed for user={}", req.getLineUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("TOKEN_REFRESH_FAILED", "Google 授權已失效，請重新連結帳號"));
        } catch (Exception e) {
            log.error("Calendar query error for user={}", req.getLineUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GoogleApiResponse.fail("GOOGLE_API_ERROR", "Google API 呼叫失敗：" + e.getMessage()));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<GoogleApiResponse<?>> create(@RequestBody GoogleApiRequest req) {
        Optional<GoogleToken> tokenOpt = googleAuthService.getToken(req.getLineUserId());
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("GOOGLE_NOT_LINKED", "此用戶尚未連結 Google 帳號"));
        }
        if (!hasText(req.getTitle()) || !hasText(req.getDate())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GoogleApiResponse.fail("MISSING_REQUIRED_FIELD", "title 和 date 為必填欄位"));
        }
        if (!Boolean.TRUE.equals(req.getAllDay()) && !hasText(req.getStartTime())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GoogleApiResponse.fail("MISSING_REQUIRED_FIELD", "非全天事件需提供 start_time"));
        }
        try {
            CalendarData data = calendarApiService.create(tokenOpt.get(), req);
            return ResponseEntity.ok(GoogleApiResponse.ok(data));
        } catch (TokenResponseException e) {
            log.warn("Calendar token refresh failed for user={}", req.getLineUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("TOKEN_REFRESH_FAILED", "Google 授權已失效，請重新連結帳號"));
        } catch (Exception e) {
            log.error("Calendar create error for user={}", req.getLineUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GoogleApiResponse.fail("GOOGLE_API_ERROR", "Google API 呼叫失敗：" + e.getMessage()));
        }
    }

    @PostMapping("/modify")
    public ResponseEntity<GoogleApiResponse<?>> modify(@RequestBody GoogleApiRequest req) {
        Optional<GoogleToken> tokenOpt = googleAuthService.getToken(req.getLineUserId());
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("GOOGLE_NOT_LINKED", "此用戶尚未連結 Google 帳號"));
        }
        if (!hasText(req.getEventId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(GoogleApiResponse.fail("MISSING_REQUIRED_FIELD", "event_id 為必填欄位"));
        }
        try {
            CalendarData data = calendarApiService.modify(tokenOpt.get(), req);
            return ResponseEntity.ok(GoogleApiResponse.ok(data));
        } catch (TokenResponseException e) {
            log.warn("Calendar token refresh failed for user={}", req.getLineUserId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GoogleApiResponse.fail("TOKEN_REFRESH_FAILED", "Google 授權已失效，請重新連結帳號"));
        } catch (Exception e) {
            log.error("Calendar modify error for user={}", req.getLineUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(GoogleApiResponse.fail("GOOGLE_API_ERROR", "Google API 呼叫失敗：" + e.getMessage()));
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
