package com.haoforyou.skill;

import com.haoforyou.service.GoogleAuthService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(10)  // 高優先，在 CalendarSkill 之前
public class GoogleAuthSkill implements Skill {

    private final GoogleAuthService googleAuthService;

    public GoogleAuthSkill(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @Override public String getName()        { return "google_auth"; }
    @Override public String getDescription() { return "連結、更換或解除 Google 帳號授權"; }

    @Override
    public List<String> getKeywords() {
        return List.of(
            "連結google", "連結 google", "連結Google", "連結 Google",
            "綁定google", "綁定 google", "綁定Google", "綁定 Google",
            "更換google", "更換 google", "更換Google", "更換 Google",
            "解除google", "解除 google", "解除Google", "解除 Google",
            "google帳號", "google 帳號", "Google帳號", "Google 帳號",
            "授權google", "授權 google", "授權Google", "授權 Google"
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        String msg = ctx.message().toLowerCase();
        boolean isRevoke = msg.contains("解除") || msg.contains("取消")
                        || msg.contains("移除") || msg.contains("刪除");

        if (isRevoke) {
            boolean deleted = googleAuthService.deleteToken(ctx.userId());
            String reply = deleted
                ? "✅ 已解除 Google 帳號授權。\n如需重新連結，請傳送「連結 Google 帳號」。"
                : "你目前沒有連結任何 Google 帳號。";
            return SkillResult.ok(reply, getName());
        }

        String authUrl = googleAuthService.buildAuthUrl(ctx.userId());
        String currentEmail = googleAuthService.getToken(ctx.userId())
            .map(t -> t.getGoogleEmail()).orElse(null);

        String reply = currentEmail != null
            ? "你目前已連結：" + currentEmail + "\n\n如要更換帳號，請點擊以下連結重新授權：\n" + authUrl
            : "請點擊以下連結授權你的 Google 帳號：\n" + authUrl;

        return SkillResult.ok(reply, getName());
    }
}
