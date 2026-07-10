package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventSummary(
    @Schema(description = "事件ID", example = "U1234567890abcdef")
    @JsonProperty("event_id") 
    String eventId,

    @Schema(description = "事件標題", example = "會議")
    String title,

    @Schema(description = "事件開始時間", example = "2023-10-01T10:00:00Z")
    String start,

    @Schema(description = "事件結束時間", example = "2023-10-01T11:00:00Z")
    String end,

    @Schema(description = "是否為全天事件", example = "false")
    @JsonProperty("all_day") 
    boolean allDay,
    
    @Schema(description = "事件描述", example = "會議將於下週舉行")
    String description,

    @Schema(description = "事件狀態", example = "confirmed")
    String status
) {}
