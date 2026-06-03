package com.haoforyou.skill;

import java.util.List;
import java.util.Map;

public interface Skill {

    String getName();
    String getDescription();
    List<String> getKeywords();

    SkillResult execute(SkillContext context);

    default boolean keywordMatch(String message) {
        return getKeywords().stream().anyMatch(message::contains);
    }
}
