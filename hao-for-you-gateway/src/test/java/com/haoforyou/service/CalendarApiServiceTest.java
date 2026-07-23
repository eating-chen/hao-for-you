package com.haoforyou.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.haoforyou.dto.CalendarData;
import com.haoforyou.dto.EventSummary;
import com.haoforyou.dto.GoogleCalendarApiRequest;
import com.haoforyou.model.GoogleToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CalendarApiService} 的單元測試。
 *
 * <p>{@code CalendarApiService} 是純邏輯的 {@code @Service}，唯一的建構子依賴是
 * {@link GoogleAuthService}，因此用 {@code @ExtendWith(MockitoExtension.class)} +
 * {@code @Mock} GoogleAuthService + {@code @InjectMocks} 受測物件。{@link GoogleAuthService}
 * 回傳的 Google SDK {@link Calendar} 物件與其 fluent API（events().list/insert/patch(...)
 * .setXxx(...).execute()）皆以 Mockito 手動 mock 多層呼叫鏈，全程不打真正的 Google Calendar API。</p>
 *
 * <p>對應設計文件：docs/specs/2026-06-15-google-api-endpoints-design.md 中
 * 「2. Calendar 查詢」「3. Calendar 新增」「4. Calendar 修改」章節；由於文件與現行程式碼已有落差
 * （例如 query 目前支援 date/date_to 區間查詢、create 與 modify 皆需要明確的 start_time/end_time
 * 而非 duration_minutes），測試依實際程式碼行為撰寫，涵蓋日期區間／單一日期／未指定日期、
 * all_day true/false、keyword 有無等每個分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class CalendarApiServiceTest {

    private static final String LINE_USER_ID = "Uabc1234567890";
    private static final String PRIMARY_CALENDAR = "primary";
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    @Mock
    private GoogleAuthService googleAuthService;

    private CalendarApiService calendarApiService;

    private Calendar calendar;
    private Calendar.Events events;

    @BeforeEach
    void setUp() throws Exception {
        calendarApiService = new CalendarApiService(googleAuthService);

        calendar = mock(Calendar.class);
        events = mock(Calendar.Events.class);
        lenient().when(calendar.events()).thenReturn(events);
        lenient().when(googleAuthService.buildCalendarService(any())).thenReturn(calendar);
    }

    private GoogleToken token() {
        return new GoogleToken(LINE_USER_ID, "access-token", "refresh-token", null, "user@gmail.com");
    }

    private GoogleCalendarApiRequest baseRequest() {
        GoogleCalendarApiRequest req = new GoogleCalendarApiRequest();
        req.setLineUserId(LINE_USER_ID);
        return req;
    }

    /** 建立 events().list(...) 的 mock，並讓所有 fluent setter 回傳自己（lenient，因各測試只會用到部分 setter）。 */
    private Calendar.Events.List mockListRequest() {
        Calendar.Events.List listRequest = mock(Calendar.Events.List.class);
        lenient().when(listRequest.setMaxResults(any())).thenReturn(listRequest);
        lenient().when(listRequest.setOrderBy(any())).thenReturn(listRequest);
        lenient().when(listRequest.setSingleEvents(any())).thenReturn(listRequest);
        lenient().when(listRequest.setTimeMin(any())).thenReturn(listRequest);
        lenient().when(listRequest.setTimeMax(any())).thenReturn(listRequest);
        lenient().when(listRequest.setQ(any())).thenReturn(listRequest);
        return listRequest;
    }

    private Event timedEvent(String id, String summary, String description,
                              ZonedDateTime start, ZonedDateTime end) {
        Event event = new Event();
        event.setId(id);
        event.setSummary(summary);
        event.setDescription(description);
        event.setStart(new EventDateTime()
            .setDateTime(new DateTime(start.toInstant().toEpochMilli()))
            .setTimeZone("Asia/Taipei"));
        event.setEnd(new EventDateTime()
            .setDateTime(new DateTime(end.toInstant().toEpochMilli()))
            .setTimeZone("Asia/Taipei"));
        return event;
    }

    private Event allDayEvent(String id, String summary, String date) {
        Event event = new Event();
        event.setId(id);
        event.setSummary(summary);
        DateTime d = new DateTime(date);
        event.setStart(new EventDateTime().setDate(d));
        event.setEnd(new EventDateTime().setDate(d));
        return event;
    }

    // ────────────────────────────────────────────────────────────
    // query()
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("query - 日期分支")
    class QueryDateBranches {

        @Test
        @DisplayName("date 與 date_to 皆有值：timeMin 為 date 當日 00:00，timeMax 為 date_to 隔日 00:00")
        void query_dateRange_setsTimeMinAndTimeMax() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");
            req.setDateTo("2026-10-05");

            calendarApiService.query(token, req);

            ArgumentCaptor<DateTime> minCaptor = ArgumentCaptor.forClass(DateTime.class);
            ArgumentCaptor<DateTime> maxCaptor = ArgumentCaptor.forClass(DateTime.class);
            verify(listRequest).setTimeMin(minCaptor.capture());
            verify(listRequest).setTimeMax(maxCaptor.capture());

            ZonedDateTime expectedMin = LocalDate.parse("2026-10-01").atStartOfDay(ZONE);
            ZonedDateTime expectedMax = LocalDate.parse("2026-10-05").plusDays(1).atStartOfDay(ZONE);
            assertThat(minCaptor.getValue().getValue()).isEqualTo(expectedMin.toInstant().toEpochMilli());
            assertThat(maxCaptor.getValue().getValue()).isEqualTo(expectedMax.toInstant().toEpochMilli());
        }

        @Test
        @DisplayName("只有 date（無 date_to）：timeMin 為當日 00:00，timeMax 為隔日 00:00")
        void query_singleDate_setsTimeMinAndTimeMaxForSingleDay() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");

            calendarApiService.query(token, req);

            ArgumentCaptor<DateTime> minCaptor = ArgumentCaptor.forClass(DateTime.class);
            ArgumentCaptor<DateTime> maxCaptor = ArgumentCaptor.forClass(DateTime.class);
            verify(listRequest).setTimeMin(minCaptor.capture());
            verify(listRequest).setTimeMax(maxCaptor.capture());

            ZonedDateTime expectedStart = LocalDate.parse("2026-10-01").atStartOfDay(ZONE);
            ZonedDateTime expectedEnd = expectedStart.plusDays(1);
            assertThat(minCaptor.getValue().getValue()).isEqualTo(expectedStart.toInstant().toEpochMilli());
            assertThat(maxCaptor.getValue().getValue()).isEqualTo(expectedEnd.toInstant().toEpochMilli());
        }

        @Test
        @DisplayName("date 與 date_to 皆未指定：只呼叫 setTimeMin(約當下時間)，不呼叫 setTimeMax")
        void query_noDate_onlySetsTimeMinToNow() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            long before = System.currentTimeMillis();
            GoogleCalendarApiRequest req = baseRequest();

            calendarApiService.query(token, req);

            long after = System.currentTimeMillis();

            ArgumentCaptor<DateTime> minCaptor = ArgumentCaptor.forClass(DateTime.class);
            verify(listRequest).setTimeMin(minCaptor.capture());
            verify(listRequest, never()).setTimeMax(any());

            assertThat(minCaptor.getValue().getValue()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("query - keyword 分支")
    class QueryKeywordBranches {

        @Test
        @DisplayName("keyword 有值時，帶入 setQ")
        void query_withKeyword_callsSetQ() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setKeyword("會議");

            calendarApiService.query(token, req);

            verify(listRequest).setQ("會議");
        }

        @Test
        @DisplayName("keyword 為 null 時，不呼叫 setQ")
        void query_withoutKeyword_neverCallsSetQ() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            calendarApiService.query(token, baseRequest());

            verify(listRequest, never()).setQ(any());
        }

        @Test
        @DisplayName("keyword 為空白字串時，視同未指定，不呼叫 setQ")
        void query_blankKeyword_neverCallsSetQ() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setKeyword("   ");

            calendarApiService.query(token, req);

            verify(listRequest, never()).setQ(any());
        }
    }

    @Nested
    @DisplayName("query - limit 與回傳結果")
    class QueryLimitAndResult {

        @Test
        @DisplayName("limit 未指定時，預設帶入 5")
        void query_withoutLimit_defaultsToFive() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            calendarApiService.query(token, baseRequest());

            verify(listRequest).setMaxResults(5);
        }

        @Test
        @DisplayName("limit 有指定時，帶入指定值")
        void query_withLimit_usesProvidedValue() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of()));

            GoogleCalendarApiRequest req = baseRequest();
            req.setLimit(20);

            calendarApiService.query(token, req);

            verify(listRequest).setMaxResults(20);
        }

        @Test
        @DisplayName("happy path：回傳事件列表，非全天事件的 start/end 取自 dateTime，status 為 null")
        void query_happyPath_mapsTimedEventCorrectly() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);

            ZonedDateTime start = ZonedDateTime.of(2026, 10, 1, 14, 0, 0, 0, ZONE);
            ZonedDateTime end = start.plusHours(1);
            Event event = timedEvent("event_1", "開會", "會議說明", start, end);
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of(event)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("2026-10-01");

            CalendarData data = calendarApiService.query(token, req);

            assertThat(data.events()).hasSize(1);
            EventSummary summary = data.events().get(0);
            assertThat(summary.eventId()).isEqualTo("event_1");
            assertThat(summary.title()).isEqualTo("開會");
            assertThat(summary.allDay()).isFalse();
            assertThat(summary.start()).isEqualTo("2026-10-01T14:00:00.000+08:00");
            assertThat(summary.end()).isEqualTo("2026-10-01T15:00:00.000+08:00");
            assertThat(summary.description()).isEqualTo("會議說明");
            assertThat(summary.status()).isNull();
        }

        @Test
        @DisplayName("happy path：全天事件的 start/end 取自 date 而非 dateTime，allDay 為 true")
        void query_happyPath_mapsAllDayEventCorrectly() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);

            Event event = allDayEvent("event_holiday", "國定假日", "2026-10-10");
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of(event)));

            CalendarData data = calendarApiService.query(token, baseRequest());

            assertThat(data.events()).hasSize(1);
            EventSummary summary = data.events().get(0);
            assertThat(summary.allDay()).isTrue();
            assertThat(summary.start()).isEqualTo("2026-10-10");
            assertThat(summary.end()).isEqualTo("2026-10-10");
            assertThat(summary.description()).isEqualTo("");
        }

        @Test
        @DisplayName("多筆事件時，依序對應為多筆 EventSummary")
        void query_multipleEvents_mapsAllOfThem() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);

            ZonedDateTime start = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE);
            Event timed = timedEvent("event_1", "會議 A", null, start, start.plusMinutes(30));
            Event allDay = allDayEvent("event_2", "會議 B", "2026-10-02");
            when(listRequest.execute()).thenReturn(new Events().setItems(List.of(timed, allDay)));

            CalendarData data = calendarApiService.query(token, baseRequest());

            assertThat(data.events()).hasSize(2);
            assertThat(data.events().get(0).eventId()).isEqualTo("event_1");
            assertThat(data.events().get(0).description()).isEqualTo("");
            assertThat(data.events().get(1).eventId()).isEqualTo("event_2");
        }

        @Test
        @DisplayName("Google API 回傳 items 為 null 時，回傳空的事件列表")
        void query_nullItems_returnsEmptyList() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenReturn(new Events()); // getItems() == null

            CalendarData data = calendarApiService.query(token, baseRequest());

            assertThat(data.events()).isEmpty();
        }
    }

    @Nested
    @DisplayName("query - 例外處理")
    class QueryExceptions {

        @Test
        @DisplayName("buildCalendarService 拋出例外時，例外直接往上拋")
        void query_buildCalendarServiceThrows_propagates() throws Exception {
            GoogleToken token = token();
            when(googleAuthService.buildCalendarService(token))
                .thenThrow(new java.io.IOException("無法建立 Calendar service"));

            assertThatThrownBy(() -> calendarApiService.query(token, baseRequest()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("無法建立 Calendar service");
        }

        @Test
        @DisplayName("Calendar API execute() 拋出例外時，例外直接往上拋")
        void query_executeThrows_propagates() throws Exception {
            GoogleToken token = token();
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);
            when(listRequest.execute()).thenThrow(new java.io.IOException("Calendar API 逾時"));

            assertThatThrownBy(() -> calendarApiService.query(token, baseRequest()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Calendar API 逾時");
        }

        @Test
        @DisplayName("date 格式不合法時，拋出 DateTimeParseException")
        void query_invalidDateFormat_throwsDateTimeParseException() throws Exception {
            GoogleToken token = token();
            // request 建構（events().list(...).setMaxResults(...)...）發生在日期解析之前，
            // 因此仍需先把 list 呼叫鏈接好，才能讓例外真正來自日期解析而非 NPE。
            Calendar.Events.List listRequest = mockListRequest();
            when(events.list(PRIMARY_CALENDAR)).thenReturn(listRequest);

            GoogleCalendarApiRequest req = baseRequest();
            req.setDate("not-a-date");

            assertThatThrownBy(() -> calendarApiService.query(token, req))
                .isInstanceOf(DateTimeParseException.class);
        }
    }

    // ────────────────────────────────────────────────────────────
    // create()
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create - all_day 分支")
    class CreateAllDayBranches {

        @Test
        @DisplayName("all_day=true：以 date 建立整天事件，start/end 皆為同一天的 date 值且不帶 dateTime")
        void create_allDayTrue_buildsDateOnlyEvent() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);
            when(insertRequest.execute()).thenReturn(allDayEvent("event_allday", "國定假日", "2026-10-01"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("國定假日");
            req.setDate("2026-10-01");
            req.setAllDay(true);

            CalendarData data = calendarApiService.create(token, req);

            Event captured = eventCaptor.getValue();
            assertThat(captured.getSummary()).isEqualTo("國定假日");
            assertThat(captured.getStart().getDate().toStringRfc3339()).isEqualTo("2026-10-01");
            assertThat(captured.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-10-01");
            assertThat(captured.getStart().getDateTime()).isNull();

            assertThat(data.events()).hasSize(1);
            assertThat(data.events().get(0).status()).isEqualTo("created");
            assertThat(data.events().get(0).allDay()).isTrue();
        }

        @Test
        @DisplayName("all_day=false：以 date + start_time/end_time 建立帶時區的 dateTime 事件")
        void create_allDayFalse_buildsZonedDateTimeEvent() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);

            ZonedDateTime start = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE);
            when(insertRequest.execute())
                .thenReturn(timedEvent("event_new", "開會", "會議說明", start, start.plusHours(1)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00");
            req.setEndTime("10:00:00");
            req.setAllDay(false);

            CalendarData data = calendarApiService.create(token, req);

            Event captured = eventCaptor.getValue();
            assertThat(captured.getStart().getDate()).isNull();
            assertThat(captured.getStart().getDateTime().toStringRfc3339())
                .isEqualTo("2026-10-01T09:00:00.000+08:00");
            assertThat(captured.getEnd().getDateTime().toStringRfc3339())
                .isEqualTo("2026-10-01T10:00:00.000+08:00");
            assertThat(captured.getStart().getTimeZone()).isEqualTo("Asia/Taipei");

            assertThat(data.events()).hasSize(1);
            assertThat(data.events().get(0).status()).isEqualTo("created");
            assertThat(data.events().get(0).allDay()).isFalse();
        }

        @Test
        @DisplayName("all_day 未帶（null）時，比照 all_day=false 走 dateTime 分支")
        void create_allDayNull_behavesLikeFalse() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);

            ZonedDateTime start = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZONE);
            when(insertRequest.execute())
                .thenReturn(timedEvent("event_new", "開會", null, start, start.plusHours(1)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");
            req.setEndTime("10:00:00");

            calendarApiService.create(token, req);

            Event captured = eventCaptor.getValue();
            assertThat(captured.getStart().getDate()).isNull();
            assertThat(captured.getStart().getDateTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("create - description 分支")
    class CreateDescriptionBranches {

        @Test
        @DisplayName("description 有值時，帶入事件描述")
        void create_withDescription_setsDescription() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);
            when(insertRequest.execute()).thenReturn(allDayEvent("event_1", "假日", "2026-10-01"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("假日");
            req.setDate("2026-10-01");
            req.setAllDay(true);
            req.setDescription("國定假日說明");

            calendarApiService.create(token, req);

            assertThat(eventCaptor.getValue().getDescription()).isEqualTo("國定假日說明");
        }

        @Test
        @DisplayName("description 未帶時，事件描述維持 null（不呼叫 setDescription）")
        void create_withoutDescription_descriptionStaysNull() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);
            when(insertRequest.execute()).thenReturn(allDayEvent("event_1", "假日", "2026-10-01"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("假日");
            req.setDate("2026-10-01");
            req.setAllDay(true);

            calendarApiService.create(token, req);

            assertThat(eventCaptor.getValue().getDescription()).isNull();
        }

        @Test
        @DisplayName("description 為空白字串時，視同未帶，不呼叫 setDescription")
        void create_blankDescription_descriptionStaysNull() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.insert(eq(PRIMARY_CALENDAR), eventCaptor.capture())).thenReturn(insertRequest);
            when(insertRequest.execute()).thenReturn(allDayEvent("event_1", "假日", "2026-10-01"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("假日");
            req.setDate("2026-10-01");
            req.setAllDay(true);
            req.setDescription("   ");

            calendarApiService.create(token, req);

            assertThat(eventCaptor.getValue().getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("create - 例外與邊界處理")
    class CreateExceptions {

        @Test
        @DisplayName("buildCalendarService 拋出例外時，例外直接往上拋，且不會呼叫 insert")
        void create_buildCalendarServiceThrows_propagates() throws Exception {
            GoogleToken token = token();
            when(googleAuthService.buildCalendarService(token))
                .thenThrow(new java.io.IOException("無法建立 Calendar service"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setAllDay(true);

            assertThatThrownBy(() -> calendarApiService.create(token, req))
                .isInstanceOf(java.io.IOException.class);

            verify(events, never()).insert(any(), any());
        }

        @Test
        @DisplayName("insert().execute() 拋出例外時，例外直接往上拋")
        void create_executeThrows_propagates() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Insert insertRequest = mock(Calendar.Events.Insert.class);
            when(events.insert(eq(PRIMARY_CALENDAR), any(Event.class))).thenReturn(insertRequest);
            when(insertRequest.execute()).thenThrow(new java.io.IOException("insert failed"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setAllDay(true);

            assertThatThrownBy(() -> calendarApiService.create(token, req))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("insert failed");
        }

        @Test
        @DisplayName("非全天事件缺少 end_time 時，buildZonedDateTime 內的 LocalTime.parse 會拋出 NullPointerException")
        void create_notAllDay_missingEndTime_throwsNullPointerException() {
            GoogleToken token = token();
            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026-10-01");
            req.setStartTime("09:00:00");
            // 故意不帶 end_time

            assertThatThrownBy(() -> calendarApiService.create(token, req))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("非全天事件且 date 格式不合法時，buildZonedDateTime 內的 LocalDate.parse 拋出 DateTimeParseException")
        void create_invalidDateFormat_throwsDateTimeParseException() {
            GoogleToken token = token();
            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026/10/01");
            req.setStartTime("09:00:00");
            req.setEndTime("10:00:00");
            req.setAllDay(false);

            assertThatThrownBy(() -> calendarApiService.create(token, req))
                .isInstanceOf(DateTimeParseException.class);
        }

        @Test
        @DisplayName("全天事件且 date 格式不合法時，Google DateTime 解析拋出 NumberFormatException")
        void create_allDay_invalidDateFormat_throwsNumberFormatException() {
            GoogleToken token = token();
            GoogleCalendarApiRequest req = baseRequest();
            req.setTitle("開會");
            req.setDate("2026/10/01");
            req.setAllDay(true);

            assertThatThrownBy(() -> calendarApiService.create(token, req))
                .isInstanceOf(NumberFormatException.class);
        }
    }

    // ────────────────────────────────────────────────────────────
    // modify()
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("modify - all_day 分支")
    class ModifyAllDayBranches {

        @Test
        @DisplayName("all_day=true：patch 內容以 date 建立整天事件")
        void modify_allDayTrue_buildsDateOnlyEvent() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Patch patchRequest = mock(Calendar.Events.Patch.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.patch(eq(PRIMARY_CALENDAR), eq("event_123"), eventCaptor.capture()))
                .thenReturn(patchRequest);
            when(patchRequest.execute()).thenReturn(allDayEvent("event_123", "假日", "2026-10-10"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setTitle("假日");
            req.setDate("2026-10-10");
            req.setAllDay(true);

            CalendarData data = calendarApiService.modify(token, req);

            Event captured = eventCaptor.getValue();
            assertThat(captured.getStart().getDate().toStringRfc3339()).isEqualTo("2026-10-10");
            assertThat(captured.getStart().getDateTime()).isNull();
            assertThat(data.events().get(0).status()).isEqualTo("updated");
        }

        @Test
        @DisplayName("all_day=false：patch 內容以 date + start_time/end_time 建立 dateTime 事件")
        void modify_allDayFalse_buildsZonedDateTimeEvent() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Patch patchRequest = mock(Calendar.Events.Patch.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.patch(eq(PRIMARY_CALENDAR), eq("event_123"), eventCaptor.capture()))
                .thenReturn(patchRequest);

            ZonedDateTime start = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, ZONE);
            when(patchRequest.execute())
                .thenReturn(timedEvent("event_123", "新標題", "", start, start.plusMinutes(90)));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setTitle("新標題");
            req.setDate("2026-10-02");
            req.setStartTime("15:00:00");
            req.setEndTime("16:30:00");

            CalendarData data = calendarApiService.modify(token, req);

            Event captured = eventCaptor.getValue();
            assertThat(captured.getStart().getDateTime().toStringRfc3339())
                .isEqualTo("2026-10-02T15:00:00.000+08:00");
            assertThat(captured.getEnd().getDateTime().toStringRfc3339())
                .isEqualTo("2026-10-02T16:30:00.000+08:00");
            assertThat(data.events().get(0).status()).isEqualTo("updated");
        }
    }

    @Nested
    @DisplayName("modify - description 分支")
    class ModifyDescriptionBranches {

        @Test
        @DisplayName("description 未帶（null）時，patch 內容的 description 會被設為空字串")
        void modify_missingDescription_defaultsToEmptyString() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Patch patchRequest = mock(Calendar.Events.Patch.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.patch(eq(PRIMARY_CALENDAR), eq("event_123"), eventCaptor.capture()))
                .thenReturn(patchRequest);
            when(patchRequest.execute()).thenReturn(allDayEvent("event_123", "假日", "2026-10-10"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setTitle("假日");
            req.setDate("2026-10-10");
            req.setAllDay(true);
            // description 未帶

            calendarApiService.modify(token, req);

            assertThat(eventCaptor.getValue().getDescription()).isEqualTo("");
        }

        @Test
        @DisplayName("description 有值時，直接帶入 patch 內容")
        void modify_withDescription_usesProvidedValue() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Patch patchRequest = mock(Calendar.Events.Patch.class);
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            when(events.patch(eq(PRIMARY_CALENDAR), eq("event_123"), eventCaptor.capture()))
                .thenReturn(patchRequest);
            when(patchRequest.execute()).thenReturn(allDayEvent("event_123", "假日", "2026-10-10"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setTitle("假日");
            req.setDate("2026-10-10");
            req.setAllDay(true);
            req.setDescription("更新後的說明");

            calendarApiService.modify(token, req);

            assertThat(eventCaptor.getValue().getDescription()).isEqualTo("更新後的說明");
        }
    }

    @Nested
    @DisplayName("modify - 例外處理")
    class ModifyExceptions {

        @Test
        @DisplayName("buildCalendarService 拋出例外時，例外直接往上拋，且不會呼叫 patch")
        void modify_buildCalendarServiceThrows_propagates() throws Exception {
            GoogleToken token = token();
            when(googleAuthService.buildCalendarService(token))
                .thenThrow(new java.io.IOException("無法建立 Calendar service"));

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setAllDay(true);
            req.setDate("2026-10-10");

            assertThatThrownBy(() -> calendarApiService.modify(token, req))
                .isInstanceOf(java.io.IOException.class);

            verify(events, never()).patch(any(), any(), any());
        }

        @Test
        @DisplayName("patch().execute() 拋出例外時，例外直接往上拋")
        void modify_executeThrows_propagates() throws Exception {
            GoogleToken token = token();
            Calendar.Events.Patch patchRequest = mock(Calendar.Events.Patch.class);
            when(events.patch(eq(PRIMARY_CALENDAR), eq("event_123"), any(Event.class)))
                .thenReturn(patchRequest);
            doThrow(new java.io.IOException("patch failed")).when(patchRequest).execute();

            GoogleCalendarApiRequest req = baseRequest();
            req.setEventId("event_123");
            req.setAllDay(true);
            req.setDate("2026-10-10");

            assertThatThrownBy(() -> calendarApiService.modify(token, req))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("patch failed");
        }
    }
}
