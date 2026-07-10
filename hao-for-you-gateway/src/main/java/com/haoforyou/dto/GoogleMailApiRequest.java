package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class GoogleMailApiRequest {

    @Schema(description = "LINE 用戶 ID", example = "U1234567890abcdef")
    @JsonProperty("line_user_id")
    private String lineUserId;

    @Schema(description = "查詢日期，只帶 date 則查當天寄達的信件", example = "2026-10-01")
    private String date;

    @Schema(description = "查詢日期範圍到期日，需搭配 date 一起使用才會查詢區間，無則不用", example = "2026-10-05")
    @JsonProperty("date_to")
    private String dateTo;

    @Schema(description = "查詢關鍵字，可直接帶 Gmail 搜尋語法（例如 subject:會議 只搜標題）", example = "會議")
    private String keyword;

    @Schema(description = "寄件人", example = "john.doe@example.com")
    private String from;

    @Schema(description = "僅顯示未讀郵件", example = "false")
    @JsonProperty("unread_only")
    private Boolean unreadOnly;

    @Schema(description = "查詢結果限制數量", example = "10")
    private Integer limit;
}
