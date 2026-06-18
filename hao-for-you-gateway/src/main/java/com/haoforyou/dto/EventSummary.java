package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventSummary(
    @JsonProperty("event_id") String eventId,
    String title,
    String start,
    String end,
    @JsonProperty("all_day") boolean allDay,
    String description,
    String status
) {}
