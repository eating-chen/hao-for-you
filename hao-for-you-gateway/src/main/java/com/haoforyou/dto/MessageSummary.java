package com.haoforyou.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MessageSummary(

    @Schema(description = "郵件 ID", example = "1789abcdef123456")
    String id,

    @Schema(description = "郵件主旨", example = "會議通知")
    String subject,

    @Schema(description = "寄件人", example = "andy@example.com")
    String from,

    @Schema(description = "發送日期", example = "2023-10-01T10:00:00Z")
    String date,

    @Schema(description = "郵件摘要", example = "會議將於下週舉行")
    String snippet,

    @Schema(description = "郵件內容", example = "各位同事，提醒您...")
    String body
) {}
