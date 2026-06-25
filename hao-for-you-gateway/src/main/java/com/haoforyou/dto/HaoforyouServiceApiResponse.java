package com.haoforyou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HaoforyouServiceApiResponse(
    @JsonProperty("success") boolean success,
    @JsonProperty("user_id") String user_id,
    @JsonProperty("session_id") String session_id,
    @JsonProperty("platform") String platform,
    @JsonProperty("response") String response
) {
}
