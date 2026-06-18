package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GoogleApiRequest {

    @JsonProperty("line_user_id")
    private String lineUserId;

    @JsonProperty("event_id")
    private String eventId;

    private String title;
    private String date;

    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    private String description;

    @JsonProperty("all_day")
    private Boolean allDay;

    private String keyword;

    private String from;

    @JsonProperty("unread_only")
    private Boolean unreadOnly;

    private Integer limit;
}
