package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HaoforyouServiceApiRequest(
    @JsonProperty("user_id") String userId,
    @JsonProperty("platform") String platform,
    @JsonProperty("message") String message
) {
}
