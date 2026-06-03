package com.haoforyou.skill;

import com.haoforyou.service.OllamaService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(100)  // 最後 fallback
public class GeneralChatSkill implements Skill {

    private final OllamaService ollamaService;

    public GeneralChatSkill(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @Override public String getName()        { return "general_chat"; }
    @Override public String getDescription() { return "一般對話、問答、閒聊，以及無法判斷意圖時的 fallback"; }
    @Override public List<String> getKeywords() { return List.of(); }

    @Override
    public SkillResult execute(SkillContext ctx) {
        String reply = ollamaService.chat(ctx.conversationHistory(), ctx.message());
        return SkillResult.ok(reply, getName());
    }
}
