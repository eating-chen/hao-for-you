package com.haoforyou.skill;

/**
 * Skill 執行結果（對應 Python SkillResult dataclass）
 */
public record SkillResult(
    boolean success,
    String replyText,
    String skillName,
    String errorMessage
) {
    public static SkillResult ok(String replyText, String skillName) {
        return new SkillResult(true, replyText, skillName, null);
    }

    public static SkillResult fail(String errorMessage, String skillName) {
        return new SkillResult(false, "抱歉，發生了一些問題，請稍後再試。", skillName, errorMessage);
    }
}
