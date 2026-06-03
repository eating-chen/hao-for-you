package com.haoforyou.skill;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /**
     * Spring 自動注入所有實作 Skill 介面的 Bean。
     * 順序由 @Order 控制（數字越小越優先）。
     */
    public SkillRegistry(List<Skill> skillList) {
        skillList.stream()
            .sorted(AnnotationAwareOrderComparator.INSTANCE)
            .forEach(s -> skills.put(s.getName(), s));
    }

    public Optional<Skill> findByKeyword(String message) {
        return skills.values().stream()
            .filter(s -> !s.getKeywords().isEmpty())
            .filter(s -> s.keywordMatch(message))
            .findFirst();
    }

    public Optional<Skill> getByName(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<Skill> getAll() {
        return skills.values();
    }
}
