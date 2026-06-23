package com.haoforyou.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.haoforyou.dto.CalendarData;
import com.haoforyou.dto.EventSummary;
import com.haoforyou.dto.GoogleApiRequest;
import com.haoforyou.model.GoogleToken;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CalendarApiService {

    private static final String TIMEZONE = "Asia/Taipei";
    private static final ZoneId ZONE = ZoneId.of(TIMEZONE);
    private static final String PRIMARY_CALENDAR = "primary";

    private final GoogleAuthService googleAuthService;

    public CalendarApiService(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    public CalendarData query(GoogleToken token, GoogleApiRequest req) throws Exception {
        Calendar service = googleAuthService.buildCalendarService(token);

        Calendar.Events.List request = service.events().list(PRIMARY_CALENDAR)
            .setMaxResults(req.getLimit() != null ? req.getLimit() : 5)
            .setOrderBy("startTime")
            .setSingleEvents(true);

        if (hasText(req.getDate()) && hasText(req.getDateTo())) {
            // 查詢日期區間（date 為起始，dateTo 為結束）
            ZonedDateTime rangeStart = LocalDate.parse(req.getDate()).atStartOfDay(ZONE);
            ZonedDateTime rangeEnd = LocalDate.parse(req.getDateTo()).plusDays(1).atStartOfDay(ZONE);
            request.setTimeMin(toDateTime(rangeStart));
            request.setTimeMax(toDateTime(rangeEnd));
        } else if (hasText(req.getDate())) {
            // 查詢單一日期
            ZonedDateTime dayStart = LocalDate.parse(req.getDate()).atStartOfDay(ZONE);
            ZonedDateTime dayEnd = dayStart.plusDays(1);
            request.setTimeMin(toDateTime(dayStart));
            request.setTimeMax(toDateTime(dayEnd));
        } else {
            // 未指定日期，從現在起往後查
            request.setTimeMin(new DateTime(System.currentTimeMillis()));
        }

        if (hasText(req.getKeyword())) {
            request.setQ(req.getKeyword());
        }

        Events events = request.execute();
        List<EventSummary> summaries = events.getItems() == null ? List.of() :
            events.getItems().stream().map(e -> toSummary(e, null)).toList();

        return new CalendarData(summaries);
    }

    public CalendarData create(GoogleToken token, GoogleApiRequest req) throws Exception {
        Calendar service = googleAuthService.buildCalendarService(token);

        // 組成要新增到日曆的物件
        Event event = new Event().setSummary(req.getTitle());
        if (hasText(req.getDescription())) event.setDescription(req.getDescription());

        if (Boolean.TRUE.equals(req.getAllDay())) {
            DateTime date = new DateTime(req.getDate());
            event.setStart(new EventDateTime().setDate(date));
            event.setEnd(new EventDateTime().setDate(date));
        } else {
            ZonedDateTime start = buildZonedDateTime(req.getDate(), req.getStartTime());
            ZonedDateTime end = buildZonedDateTime(req.getDate(), req.getEndTime());
            event.setStart(new EventDateTime().setDateTime(toDateTime(start)).setTimeZone(TIMEZONE));
            event.setEnd(new EventDateTime().setDateTime(toDateTime(end)).setTimeZone(TIMEZONE));
        }

        Event created = service.events().insert(PRIMARY_CALENDAR, event).execute();
        return new CalendarData(List.of(toSummary(created, "created")));
    }

    public CalendarData modify(GoogleToken token, GoogleApiRequest req) throws Exception {
        Calendar service = googleAuthService.buildCalendarService(token);

        Event patch = new Event().setSummary(req.getTitle());
        patch.setDescription(req.getDescription() != null ? req.getDescription() : "");

        if (Boolean.TRUE.equals(req.getAllDay())) {
            DateTime date = new DateTime(req.getDate());
            patch.setStart(new EventDateTime().setDate(date));
            patch.setEnd(new EventDateTime().setDate(date));
        } else {
            ZonedDateTime start = buildZonedDateTime(req.getDate(), req.getStartTime());
            ZonedDateTime end = buildZonedDateTime(req.getDate(), req.getEndTime());
            patch.setStart(new EventDateTime().setDateTime(toDateTime(start)).setTimeZone(TIMEZONE));
            patch.setEnd(new EventDateTime().setDateTime(toDateTime(end)).setTimeZone(TIMEZONE));
        }

        Event updated = service.events().patch(PRIMARY_CALENDAR, req.getEventId(), patch).execute();
        return new CalendarData(List.of(toSummary(updated, "updated")));
    }

    private EventSummary toSummary(Event event, String status) {
        boolean allDay = event.getStart().getDate() != null;
        String start = allDay
            ? event.getStart().getDate().toStringRfc3339()
            : event.getStart().getDateTime().toStringRfc3339();
        String end = allDay
            ? event.getEnd().getDate().toStringRfc3339()
            : event.getEnd().getDateTime().toStringRfc3339();

        return new EventSummary(
            event.getId(),
            event.getSummary(),
            start,
            end,
            allDay,
            event.getDescription() != null ? event.getDescription() : "",
            status
        );
    }

    private ZonedDateTime buildZonedDateTime(String date, String time) {
        return ZonedDateTime.of(
            LocalDate.parse(date),
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")),
            ZONE
        );
    }

    private DateTime toDateTime(ZonedDateTime zdt) {
        return new DateTime(zdt.toInstant().toEpochMilli());
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
