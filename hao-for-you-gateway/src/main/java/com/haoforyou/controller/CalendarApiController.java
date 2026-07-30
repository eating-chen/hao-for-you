package com.haoforyou.controller;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.haoforyou.dto.CalendarData;
import com.haoforyou.dto.GoogleCalendarApiRequest;
import com.haoforyou.dto.GoogleApiResponse;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.CalendarApiService;
import com.haoforyou.service.GoogleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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

@Tag(name = "Google Calendar", description = "Google Calendar 查詢 / 新增 / 修改事件")
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

    @Operation(
        summary = "查詢 Calendar 事件",
        description = "支援日期區間（date + date_to）、單一日期或關鍵字搜尋，預設回傳最近 5 筆。"
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
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查詢成功，data 為 CalendarData（事件列表）"
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
                    ),
                    @ExampleObject(
                        name = "Token 已失效",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"TOKEN_REFRESH_FAILED\", \"message\": \"Google 授權已失效，請重新連結帳號\"}}"
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
    public ResponseEntity<GoogleApiResponse<CalendarData>> query(@RequestBody GoogleCalendarApiRequest req) {
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

    @Operation(
        summary = "新增 Calendar 事件",
        description = "必填：line_user_id、title、date。非全天事件（all_day 非 true）需額外提供 start_time。"
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
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "新增成功，data 為 CalendarData（包含新建立的事件）"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "缺少必填欄位",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "缺少 title 或 date",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"MISSING_REQUIRED_FIELD\", \"message\": \"title 和 date 為必填欄位\"}}"
                    ),
                    @ExampleObject(
                        name = "非全天事件缺少 start_time",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"MISSING_REQUIRED_FIELD\", \"message\": \"非全天事件需提供 start_time\"}}"
                    )
                }
            )
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
                    ),
                    @ExampleObject(
                        name = "Token 已失效",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"TOKEN_REFRESH_FAILED\", \"message\": \"Google 授權已失效，請重新連結帳號\"}}"
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
    @PostMapping("/create")
    public ResponseEntity<GoogleApiResponse<CalendarData>> create(@RequestBody GoogleCalendarApiRequest req) {
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

    @Operation(
        summary = "修改 Calendar 事件",
        description = "必填：line_user_id、event_id。其餘欄位（title、date 等）依需要帶入要覆蓋的內容。"
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
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "修改成功，data 為 CalendarData（更新後的事件）"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "缺少必填欄位 event_id",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"MISSING_REQUIRED_FIELD\", \"message\": \"event_id 為必填欄位\"}}"
                )
            )
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
                    ),
                    @ExampleObject(
                        name = "Token 已失效",
                        value = "{\"success\": false, \"data\": null, \"error\": {\"code\": \"TOKEN_REFRESH_FAILED\", \"message\": \"Google 授權已失效，請重新連結帳號\"}}"
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
    @PostMapping("/modify")
    public ResponseEntity<GoogleApiResponse<CalendarData>> modify(@RequestBody GoogleCalendarApiRequest req) {
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
