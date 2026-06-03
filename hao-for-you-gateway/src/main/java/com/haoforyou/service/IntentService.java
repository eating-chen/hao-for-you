package com.haoforyou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haoforyou.skill.Skill;
import com.haoforyou.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);
    private final SkillRegistry skillRegistry;
    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;

    public IntentService(SkillRegistry skillRegistry,
                         OllamaService ollamaService,
                         ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.ollamaService = ollamaService;
        this.objectMapper = objectMapper;
    }

    public record RouteResult(String skillName, Map<String, Object> params) {}

    /**
     * 混合路由：關鍵字優先，LLM fallback
     */
    public RouteResult route(String message, List<Map<String, String>> history) {
        // Step 1: 關鍵字快速比對
        Optional<Skill> matched = skillRegistry.findByKeyword(message);
        if (matched.isPresent()) {
            log.info("Intent matched by keyword: {}", matched.get().getName());
            return new RouteResult(matched.get().getName(), Map.of());
        }

        // Step 2: LLM 分類（超過 1 個 skill 時才呼叫）
        if (skillRegistry.getAll().size() > 1) {
            try {
                String skillDescriptions = skillRegistry.getAll().stream()
                    .map(s -> "- " + s.getName() + ": " + s.getDescription())
                    .collect(Collectors.joining("\n"));

                String recentHistory = history.stream()
                    .skip(Math.max(0, history.size() - 6))
                    .map(t -> ("user".equals(t.get("role")) ? "使用者" : "Hao") + ": " + t.get("content"))
                    .collect(Collectors.joining("\n"));

                String prompt = """
                    你是一個意圖分類器。根據使用者的最新訊息，從以下意圖中選擇最符合的一個。

                    可用意圖：
                    %s

                    規則：
                    - 只輸出 JSON，格式為 {"intent": "<意圖名稱>", "params": {}}
                    - 不要輸出任何其他文字
                    - 若無法判斷，選擇 general_chat

                    對話歷史（最近3輪）：
                    %s

                    使用者最新訊息：%s
                    """.formatted(skillDescriptions,
                                  recentHistory.isEmpty() ? "（無）" : recentHistory,
                                  message);

                String raw = ollamaService.generate(prompt);
                Map<String, Object> result = extractJson(raw);
                String intent = (String) result.getOrDefault("intent", "general_chat");

                if (skillRegistry.getByName(intent).isPresent()) {
                    log.info("Intent classified by LLM: {}", intent);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) result.getOrDefault("params", Map.of());
                    return new RouteResult(intent, params);
                }
            } catch (Exception e) {
                log.error("Intent classification error", e);
            }
        }

        log.info("Intent fallback to general_chat");
        return new RouteResult("general_chat", Map.of());
    }

    /** LLM 輸出的 JSON 安全解析（允許有前後雜訊） */
    public Map<String, Object> extractJson(String text) {
        String cleaned = text.replaceAll("```(?:json)?", "").trim();
        try {
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(cleaned);
            if (m.find()) {
                try {
                    return objectMapper.readValue(m.group(), Map.class);
                } catch (Exception ignored) {}
            }
        }
        return Map.of();
    }
}
