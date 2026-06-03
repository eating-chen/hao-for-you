package com.haoforyou.skill;

import java.util.List;
import java.util.Map;

/**
 * Skill 執行時的上下文（對應 Python SkillContext dataclass）
 */
public record SkillContext(
    String userId,
    String message,
    List<Map<String, String>> conversationHistory,
    Map<String, Object> extractedParams
) {}
