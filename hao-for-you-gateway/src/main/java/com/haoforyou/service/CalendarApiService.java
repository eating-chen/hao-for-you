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
    private static final int DEFAULT_DURATION_MINUTES = 60;

    private final GoogleAuthService googleAuthService;

    public CalendarApiService(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    public CalendarData query(GoogleToken token, GoogleApiRequest req) throws Exception {
        Calendar service = googleAuthService.buildCalendarService(token);

        Calendar.Events.List request = service.events().list("primary")
            .setMaxResults(req.getLimit() != null ? req.getLimit() : 5)
            .setOrderBy("startTime")
            .setSingleEvents(true);

        if (hasText(req.getDate())) {
            ZonedDateTime dayStart = LocalDate.parse(req.getDate()).atStartOfDay(ZONE);
            ZonedDateTime dayEnd = dayStart.plusDays(1);
            request.setTimeMin(toDateTime(dayStart));
            request.setTimeMax(toDateTime(dayEnd));
        } else {
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

        Event event = new Event().setSummary(req.getTitle());
        if (hasText(req.getDescription())) event.setDescription(req.getDescription());

        if (Boolean.TRUE.equals(req.getAllDay())) {
            DateTime date = new DateTime(req.getDate());
            event.setStart(new EventDateTime().setDate(date));
            event.setEnd(new EventDateTime().setDate(date));
        } else {
            ZonedDateTime start = buildZonedDateTime(req.getDate(), req.getStartTime());
            ZonedDateTime end = start.plusMinutes(
                req.getDurationMinutes() != null ? req.getDurationMinutes() : DEFAULT_DURATION_MINUTES
            );
            event.setStart(new EventDateTime().setDateTime(toDateTime(start)).setTimeZone(TIMEZONE));
            event.setEnd(new EventDateTime().setDateTime(toDateTime(end)).setTimeZone(TIMEZONE));
        }

        Event created = service.events().insert("primary", event).execute();
        return new CalendarData(List.of(toSummary(created, "created")));
    }

    public CalendarData modify(GoogleToken token, GoogleApiRequest req) throws Exception {
        Calendar service = googleAuthService.buildCalendarService(token);
        Event existing = service.events().get("primary", req.getEventId()).execute();

        Event patch = new Event();
        if (hasText(req.getTitle())) patch.setSummary(req.getTitle());
        if (req.getDescription() != null) patch.setDescription(req.getDescription());

        boolean changeTime = hasText(req.getDate()) || hasText(req.getStartTime()) || req.getDurationMinutes() != null;
        if (changeTime) {
            boolean existingAllDay = existing.getStart().getDate() != null;

            if (existingAllDay && hasText(req.getDate())) {
                DateTime date = new DateTime(req.getDate());
                patch.setStart(new EventDateTime().setDate(date));
                patch.setEnd(new EventDateTime().setDate(date));
            } else if (!existingAllDay) {
                ZonedDateTime existingStart = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(existing.getStart().getDateTime().getValue()), ZONE);
                ZonedDateTime existingEnd = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(existing.getEnd().getDateTime().getValue()), ZONE);
                long existingDuration = Duration.between(existingStart, existingEnd).toMinutes();

                LocalDate newDate = hasText(req.getDate())
                    ? LocalDate.parse(req.getDate()) : existingStart.toLocalDate();
                LocalTime newTime = hasText(req.getStartTime())
                    ? LocalTime.parse(req.getStartTime(), DateTimeFormatter.ofPattern("HH:mm"))
                    : existingStart.toLocalTime();
                long newDuration = req.getDurationMinutes() != null ? req.getDurationMinutes() : existingDuration;

                ZonedDateTime newStart = ZonedDateTime.of(newDate, newTime, ZONE);
                ZonedDateTime newEnd = newStart.plusMinutes(newDuration);

                patch.setStart(new EventDateTime().setDateTime(toDateTime(newStart)).setTimeZone(TIMEZONE));
                patch.setEnd(new EventDateTime().setDateTime(toDateTime(newEnd)).setTimeZone(TIMEZONE));
            }
        }

        Event updated = service.events().patch("primary", req.getEventId(), patch).execute();
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
