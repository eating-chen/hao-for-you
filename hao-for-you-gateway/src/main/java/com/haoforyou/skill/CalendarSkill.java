package com.haoforyou.skill;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.haoforyou.model.GoogleToken;
import com.haoforyou.service.GoogleAuthService;
import com.haoforyou.service.IntentService;
import com.haoforyou.service.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Order(20)
public class CalendarSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(CalendarSkill.class);
    private static final ZoneId TZ = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final GoogleAuthService googleAuthService;
    private final OllamaService ollamaService;
    private final ObjectProvider<IntentService> intentServiceProvider;

    public CalendarSkill(GoogleAuthService googleAuthService,
                         OllamaService ollamaService,
                         ObjectProvider<IntentService> intentServiceProvider) {
        this.googleAuthService = googleAuthService;
        this.ollamaService = ollamaService;
        this.intentServiceProvider = intentServiceProvider;
    }

    @Override public String getName()        { return "calendar"; }
    @Override public String getDescription() { return "查詢或新增 Google Calendar 行程、會議、提醒"; }

    @Override
    public List<String> getKeywords() {
        return List.of(
            "行事曆", "行程", "會議", "預約", "排程",
            "今天有", "明天有", "後天有", "這週", "下週",
            "幫我排", "幫我安排", "幫我記", "記一下",
            "什麼時候", "何時", "提醒我"
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        // 1. 確認授權
        Optional<GoogleToken> tokenOpt = googleAuthService.getToken(ctx.userId());
        if (tokenOpt.isEmpty()) {
            String authUrl = googleAuthService.buildAuthUrl(ctx.userId());
            return SkillResult.ok(
                "你還沒有連結 Google 帳號。\n請點擊以下連結完成授權：\n\n" + authUrl,
                getName()
            );
        }

        // 2. 建立 Calendar service
        Calendar service;
        try {
            service = googleAuthService.buildCalendarService(tokenOpt.get());
        } catch (Exception e) {
            log.error("Failed to build calendar service", e);
            return SkillResult.fail("無法連線到 Google Calendar", getName());
        }

        // 3. LLM 萃取參數
        Map<String, Object> params = extractParams(ctx.message());
        String subIntent = (String) params.getOrDefault("sub_intent", "list_upcoming");
        log.info("CalendarSkill sub_intent={} params={}", subIntent, params);

        return switch (subIntent) {
            case "create_event"  -> createEvent(service, params);
            case "query_events"  -> queryEvents(service, params);
            default              -> listUpcoming(service);
        };
    }

    // ── LLM 參數萃取 ──

    private Map<String, Object> extractParams(String message) {
        String today = LocalDate.now(TZ).format(DATE_FMT);
        String prompt = """
            從以下訊息萃取行事曆操作參數。
            今天日期：%s

            訊息：%s

            請以 JSON 格式回覆，欄位：
            - sub_intent: "query_events"|"create_event"|"list_upcoming"
            - date: "YYYY-MM-DD"（若提到日期）
            - time: "HH:MM"（若提到時間，24小時制）
            - title: 事件標題（若有）
            - duration_minutes: 持續分鐘（預設60）

            只輸出 JSON，不要其他文字。
            """.formatted(today, message);

        String raw = ollamaService.generate(prompt);
        IntentService intentService = intentServiceProvider.getIfAvailable();
        if (intentService == null) {
            log.error("IntentService not available for CalendarSkill");
            return Map.of();
        }
        return intentService.extractJson(raw);
    }

    // ── 查詢特定日期行程 ──

    private SkillResult queryEvents(Calendar service, Map<String, Object> params) {
        String dateStr = (String) params.getOrDefault("date",
            LocalDate.now(TZ).format(DATE_FMT));

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            date = LocalDate.now(TZ);
            dateStr = date.format(DATE_FMT);
        }

        ZonedDateTime dayStart = date.atStartOfDay(TZ);
        ZonedDateTime dayEnd   = dayStart.plusDays(1);

        try {
            Events result = service.events().list("primary")
                .setTimeMin(new DateTime(dayStart.toInstant().toEpochMilli()))
                .setTimeMax(new DateTime(dayEnd.toInstant().toEpochMilli()))
                .setSingleEvents(true)
                .setOrderBy("startTime")
                .execute();

            List<Event> events = result.getItems();
            String dateLabel = date.format(DateTimeFormatter.ofPattern("MM/dd"));

            if (events == null || events.isEmpty()) {
                return SkillResult.ok("📅 " + dateLabel + " 沒有行程。", getName());
            }

            StringBuilder sb = new StringBuilder("📅 ").append(dateLabel).append(" 的行程：\n\n");
            for (Event e : events) {
                String time = e.getStart().getDateTime() != null
                    ? ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(e.getStart().getDateTime().getValue()), TZ)
                        .format(TIME_FMT)
                    : "全天";
                sb.append("• ").append(time).append("  ")
                  .append(e.getSummary() != null ? e.getSummary() : "（無標題）").append("\n");
            }
            return SkillResult.ok(sb.toString().trim(), getName());

        } catch (Exception e) {
            log.error("Calendar query error", e);
            return SkillResult.fail("查詢行事曆時發生錯誤", getName());
        }
    }

    // ── 新增事件 ──

    private SkillResult createEvent(Calendar service, Map<String, Object> params) {
        String title    = (String) params.getOrDefault("title", "新事件");
        String dateStr  = (String) params.getOrDefault("date", LocalDate.now(TZ).format(DATE_FMT));
        String timeStr  = (String) params.getOrDefault("time", "09:00");
        int duration    = ((Number) params.getOrDefault("duration_minutes", 60)).intValue();

        ZonedDateTime startDt, endDt;
        try {
            LocalDateTime ldt = LocalDateTime.parse(dateStr + " " + timeStr,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            startDt = ldt.atZone(TZ);
            endDt   = startDt.plusMinutes(duration);
        } catch (Exception e) {
            return SkillResult.ok("無法解析日期或時間，請再說清楚一點，例如：「明天下午三點」。", getName());
        }

        Event event = new Event().setSummary(title)
            .setStart(new EventDateTime()
                .setDateTime(new DateTime(startDt.toInstant().toEpochMilli()))
                .setTimeZone("Asia/Taipei"))
            .setEnd(new EventDateTime()
                .setDateTime(new DateTime(endDt.toInstant().toEpochMilli()))
                .setTimeZone("Asia/Taipei"));

        try {
            service.events().insert("primary", event).execute();
            return SkillResult.ok(
                "✅ 已新增行程「" + title + "」\n📅 "
                + startDt.format(DateTimeFormatter.ofPattern("MM/dd"))
                + " " + startDt.format(TIME_FMT)
                + " – " + endDt.format(TIME_FMT),
                getName()
            );
        } catch (Exception e) {
            log.error("Calendar create error", e);
            return SkillResult.fail("新增行程時發生錯誤", getName());
        }
    }

    // ── 列出近期行程 ──

    private SkillResult listUpcoming(Calendar service) {
        try {
            Events result = service.events().list("primary")
                .setTimeMin(new DateTime(Instant.now().toEpochMilli()))
                .setMaxResults(5)
                .setSingleEvents(true)
                .setOrderBy("startTime")
                .execute();

            List<Event> events = result.getItems();
            if (events == null || events.isEmpty()) {
                return SkillResult.ok("📅 近期沒有行程。", getName());
            }

            StringBuilder sb = new StringBuilder("📅 近期行程：\n\n");
            for (Event e : events) {
                String label = e.getStart().getDateTime() != null
                    ? ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(e.getStart().getDateTime().getValue()), TZ)
                        .format(DISPLAY_FMT)
                    : e.getStart().getDate().toStringRfc3339().substring(0, 10);
                sb.append("• ").append(label).append("  ")
                  .append(e.getSummary() != null ? e.getSummary() : "（無標題）").append("\n");
            }
            return SkillResult.ok(sb.toString().trim(), getName());

        } catch (Exception e) {
            log.error("Calendar list error", e);
            return SkillResult.fail("查詢行事曆時發生錯誤", getName());
        }
    }
}
