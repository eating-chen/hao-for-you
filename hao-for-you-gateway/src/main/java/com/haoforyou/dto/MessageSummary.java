package com.haoforyou.dto;

public record MessageSummary(
    String id,
    String subject,
    String from,
    String date,
    String snippet,
    String body
) {}
