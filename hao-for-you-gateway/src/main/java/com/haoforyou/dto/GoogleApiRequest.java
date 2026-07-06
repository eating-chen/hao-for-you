package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class GoogleApiRequest {

    @Schema(description = "LINE 用戶 ID", example = "U1234567890abcdef")
    @JsonProperty("line_user_id")
    private String lineUserId;

    @Schema(description = "Line對話事件 ID", example = "event_123")
    @JsonProperty("event_id")
    private String eventId;

    @Schema(description = "Gmail 查詢關鍵字", example = "subject:Meeting")
    private String title;
    
    @Schema(description = "查詢日期", example = "2026-10-01")
    private String date;

    @Schema(description = "查詢日期範圍到期日，如需要查詢時間段則帶入最後一日，無則不用", example = "2026-10-01")
    @JsonProperty("date_to")
    private String dateTo;

    @Schema(description = "查詢開始時間", example = "09:00:00")
    @JsonProperty("start_time")
    private String startTime;

    @Schema(description = "查詢結束時間", example = "17:00:00")
    @JsonProperty("end_time")
    private String endTime;

    @Schema(description = "郵件描述", example = "會議邀請")
    private String description;

    @Schema(description = "是否為全天事件", example = "false")
    @JsonProperty("all_day")
    private Boolean allDay;

    @Schema(description = "查詢關鍵字", example = "會議")
    private String keyword;

    @Schema(description = "寄件人", example = "john.doe@example.com")
    private String from;

    @Schema(description = "僅顯示未讀郵件", example = "false")
    @JsonProperty("unread_only")
    private Boolean unreadOnly;

    @Schema(description = "查詢結果限制數量", example = "10")
    private Integer limit;
}
