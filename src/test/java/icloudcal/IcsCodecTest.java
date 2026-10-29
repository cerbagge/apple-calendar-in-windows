package icloudcal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsCodecTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final CalendarInfo CAL = new CalendarInfo(URI.create("https://example.com/cal/"), "집", "#ff0000", true);
    private static final URI HREF = URI.create("https://example.com/cal/a.ics");

    /** 매주 금요일 10:00~11:00 (서울), 10/16 회차 삭제, 10/23 회차는 14:00 으로 이동 */
    private static final String WEEKLY = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:test
            BEGIN:VTIMEZONE
            TZID:Asia/Seoul
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0900
            TZOFFSETTO:+0900
            TZNAME:KST
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:weekly-1
            DTSTAMP:20261001T000000Z
            DTSTART;TZID=Asia/Seoul:20261002T100000
            DTEND;TZID=Asia/Seoul:20261002T110000
            RRULE:FREQ=WEEKLY
            EXDATE;TZID=Asia/Seoul:20261016T100000
            SUMMARY:주간 회의
            END:VEVENT
            BEGIN:VEVENT
            UID:weekly-1
            DTSTAMP:20261001T000000Z
            RECURRENCE-ID;TZID=Asia/Seoul:20261023T100000
            DTSTART;TZID=Asia/Seoul:20261023T140000
            DTEND;TZID=Asia/Seoul:20261023T150000
            SUMMARY:주간 회의 (변경)
            END:VEVENT
            END:VCALENDAR
            """;

    private static ZonedDateTime seoul(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, SEOUL);
    }

    @Test
    void expandsWeeklyRecurrenceWithExdateAndOverride() {
        String ics = WEEKLY;
        List<EventItem> events = IcsCodec.expand(ics, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0));
        List<ZonedDateTime> starts = events.stream().map(EventItem::start).sorted().toList();

        assertEquals(List.of(
                seoul(2026, 10, 2, 10, 0),
                seoul(2026, 10, 9, 10, 0),
                seoul(2026, 10, 23, 14, 0),
                seoul(2026, 10, 30, 10, 0)), starts);
        EventItem moved = events.stream().filter(e -> e.start().getDayOfMonth() == 23).findFirst().orElseThrow();
        assertEquals("주간 회의 (변경)", moved.title());
        assertEquals(seoul(2026, 10, 23, 15, 0), moved.end());
    }

    @Test
    void allDayEventCoversEachDayUntilExclusiveEnd() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:test
                BEGIN:VEVENT
                UID:trip
                DTSTAMP:20261001T000000Z
                DTSTART;VALUE=DATE:20261023
                DTEND;VALUE=DATE:20261025
                SUMMARY:여행
                END:VEVENT
                END:VCALENDAR
                """;
        ZoneId zone = ZoneId.systemDefault();
        List<EventItem> events = IcsCodec.expand(ics, CAL, HREF,
                LocalDate.of(2026, 10, 1).atStartOfDay(zone), LocalDate.of(2026, 11, 1).atStartOfDay(zone));

        assertEquals(1, events.size());
        EventItem trip = events.get(0);
        assertTrue(trip.allDay());
        assertFalse(trip.occursOn(LocalDate.of(2026, 10, 22)));
        assertTrue(trip.occursOn(LocalDate.of(2026, 10, 23)));
        assertTrue(trip.occursOn(LocalDate.of(2026, 10, 24)));
        assertFalse(trip.occursOn(LocalDate.of(2026, 10, 25)));
    }

    @Test
    void eventsOutsideRangeAreDropped() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:test
                BEGIN:VEVENT
                UID:old
                DTSTAMP:20261001T000000Z
                DTSTART:20260901T010000Z
                DTEND:20260901T020000Z
                SUMMARY:지난 일정
                END:VEVENT
                END:VCALENDAR
                """;
        assertTrue(IcsCodec.expand(ics, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0)).isEmpty());
    }

    @Test
    void builtEventParsesBack() {
        NewEvent timed = new NewEvent(CAL, "치과", "강남역", null, false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 30), 15);
        String ics = IcsCodec.build("UID-1", timed);

        assertTrue(ics.contains("UID:UID-1"), ics);
        assertTrue(ics.contains("BEGIN:VALARM"), ics);
        assertTrue(ics.contains("TRIGGER;RELATED=START:-PT15M"), ics);

        ZoneId zone = ZoneId.systemDefault();
        List<EventItem> parsed = IcsCodec.expand(ics, CAL, HREF,
                LocalDate.of(2026, 10, 23).atStartOfDay(zone), LocalDate.of(2026, 10, 24).atStartOfDay(zone));
        assertEquals(1, parsed.size());
        assertEquals("치과", parsed.get(0).title());
        assertEquals("강남역", parsed.get(0).location());
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), parsed.get(0).start().toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 10, 23, 15, 30), parsed.get(0).end().toLocalDateTime());
    }

    @Test
    void builtAllDayEventUsesDateValuesAndExclusiveEnd() {
        NewEvent allDay = new NewEvent(CAL, "휴가", null, null, true,
                LocalDateTime.of(2026, 10, 26, 0, 0), LocalDateTime.of(2026, 10, 27, 0, 0), null);
        String ics = IcsCodec.build("UID-2", allDay);

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261026"), ics);
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261028"), ics);
        assertFalse(ics.contains("VALARM"), ics);
    }

    @Test
    void alertAtStartTimeIsWritten() {
        NewEvent atStart = new NewEvent(CAL, "알림", null, null, false,
                LocalDateTime.of(2026, 10, 23, 9, 0), LocalDateTime.of(2026, 10, 23, 10, 0), 0);
        String ics = IcsCodec.build("UID-3", atStart);
        assertTrue(ics.contains("TRIGGER;RELATED=START:PT0M\r\n"), ics);
    }

    @Test
    void notesAlertAndSourceAreParsed() {
        NewEvent event = new NewEvent(CAL, "치과", null, "스케일링\n보험증 챙기기", false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 0), 15);
        EventItem parsed = parseSingle(IcsCodec.build("UID-4", event), LocalDate.of(2026, 10, 23));

        assertEquals("스케일링\n보험증 챙기기", parsed.notes());
        assertEquals(15, parsed.alertMinutes());
        assertEquals(HREF, parsed.source().href());
        assertNull(parsed.source().recurrenceId());
        assertFalse(parsed.source().series());
    }

    @Test
    void updateRewritesSingleEventAndKeepsUnchangedAlarm() throws Exception {
        String ics = IcsCodec.build("UID-5", new NewEvent(CAL, "치과", "강남역", null, false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 0), 15));
        EventItem original = parseSingle(ics, LocalDate.of(2026, 10, 23));

        String updated = IcsCodec.update(ics, original, new NewEvent(CAL, "치과 (변경)", "", "메모", false,
                LocalDateTime.of(2026, 10, 24, 9, 0), LocalDateTime.of(2026, 10, 24, 9, 30), 15));

        assertTrue(updated.contains("UID:UID-5"), updated);
        assertEquals(1, updated.split("BEGIN:VALARM", -1).length - 1, updated);
        EventItem parsed = parseSingle(updated, LocalDate.of(2026, 10, 24));
        assertEquals("치과 (변경)", parsed.title());
        assertNull(parsed.location());
        assertEquals("메모", parsed.notes());
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 0), parsed.start().toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 30), parsed.end().toLocalDateTime());
        assertEquals(15, parsed.alertMinutes());
    }

    @Test
    void updateRemovesAlarmWhenChangedToNone() throws Exception {
        String ics = IcsCodec.build("UID-6", new NewEvent(CAL, "회의", null, null, false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 0), 10));
        EventItem original = parseSingle(ics, LocalDate.of(2026, 10, 23));

        String updated = IcsCodec.update(ics, original, new NewEvent(CAL, "회의", null, null, false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 0), null));

        assertFalse(updated.contains("VALARM"), updated);
    }

    @Test
    void updatingSeriesOccurrenceChangesTextButKeepsTimesAndOverrides() throws Exception {
        EventItem occurrence = IcsCodec.expand(WEEKLY, CAL, HREF, seoul(2026, 10, 9, 0, 0), seoul(2026, 10, 10, 0, 0))
                .get(0);
        assertTrue(occurrence.source().series());

        String updated = IcsCodec.update(WEEKLY, occurrence, new NewEvent(CAL, "팀 회의", null, null, false,
                LocalDateTime.of(2026, 10, 9, 18, 0), LocalDateTime.of(2026, 10, 9, 19, 0), null));

        List<EventItem> events = IcsCodec.expand(updated, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0));
        assertEquals(List.of("팀 회의", "팀 회의", "주간 회의 (변경)", "팀 회의"),
                events.stream().sorted(Comparator.comparing(EventItem::start)).map(EventItem::title).toList());
        assertTrue(events.stream().filter(e -> e.title().equals("팀 회의"))
                .allMatch(e -> e.start().getHour() == 10), updated);
    }

    @Test
    void updatingMovedOccurrenceOnlyChangesThatOccurrence() throws Exception {
        EventItem moved = IcsCodec.expand(WEEKLY, CAL, HREF, seoul(2026, 10, 23, 0, 0), seoul(2026, 10, 24, 0, 0))
                .get(0);
        assertFalse(moved.source().series());
        assertTrue(moved.source().recurrenceId() != null);

        String updated = IcsCodec.update(WEEKLY, moved, new NewEvent(CAL, "주간 회의 (16시)", null, null, false,
                LocalDateTime.of(2026, 10, 23, 16, 0), LocalDateTime.of(2026, 10, 23, 17, 0), null));

        List<EventItem> events = IcsCodec.expand(updated, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0));
        EventItem changed = events.stream().filter(e -> e.start().getDayOfMonth() == 23).findFirst().orElseThrow();
        assertEquals("주간 회의 (16시)", changed.title());
        assertEquals(seoul(2026, 10, 23, 16, 0), changed.start());
        assertEquals(3, events.stream().filter(e -> e.title().equals("주간 회의")).count());
    }

    @Test
    void removingSeriesOccurrenceKeepsOtherOccurrences() throws Exception {
        EventItem occurrence = IcsCodec.expand(WEEKLY, CAL, HREF, seoul(2026, 10, 9, 0, 0), seoul(2026, 10, 10, 0, 0))
                .get(0);

        String updated = IcsCodec.removeOccurrence(WEEKLY, occurrence);

        List<ZonedDateTime> starts = IcsCodec.expand(updated, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0))
                .stream().map(EventItem::start).sorted().toList();
        assertEquals(List.of(
                seoul(2026, 10, 2, 10, 0),
                seoul(2026, 10, 23, 14, 0),
                seoul(2026, 10, 30, 10, 0)), starts);
    }

    @Test
    void removingMovedOccurrenceDropsOverrideAndExcludesIt() throws Exception {
        EventItem moved = IcsCodec.expand(WEEKLY, CAL, HREF, seoul(2026, 10, 23, 0, 0), seoul(2026, 10, 24, 0, 0))
                .get(0);

        String updated = IcsCodec.removeOccurrence(WEEKLY, moved);

        assertFalse(updated.contains("RECURRENCE-ID"), updated);
        List<ZonedDateTime> starts = IcsCodec.expand(updated, CAL, HREF, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0))
                .stream().map(EventItem::start).sorted().toList();
        assertEquals(List.of(
                seoul(2026, 10, 2, 10, 0),
                seoul(2026, 10, 9, 10, 0),
                seoul(2026, 10, 30, 10, 0)), starts);
    }

    @Test
    void removingAllDaySeriesOccurrence() throws Exception {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:test
                BEGIN:VEVENT
                UID:daily
                DTSTAMP:20261001T000000Z
                DTSTART;VALUE=DATE:20261026
                DTEND;VALUE=DATE:20261027
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:당번
                END:VEVENT
                END:VCALENDAR
                """;
        ZoneId zone = ZoneId.systemDefault();
        EventItem middle = parseSingle(ics, LocalDate.of(2026, 10, 27));

        String updated = IcsCodec.removeOccurrence(ics, middle);

        List<LocalDate> days = IcsCodec.expand(updated, CAL, HREF,
                        LocalDate.of(2026, 10, 1).atStartOfDay(zone), LocalDate.of(2026, 11, 1).atStartOfDay(zone))
                .stream().map(e -> e.start().toLocalDate()).sorted().toList();
        assertEquals(List.of(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)), days);
    }

    private static EventItem parseSingle(String ics, LocalDate day) {
        ZoneId zone = ZoneId.systemDefault();
        List<EventItem> parsed = IcsCodec.expand(ics, CAL, HREF, day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone));
        assertEquals(1, parsed.size(), ics);
        return parsed.get(0);
    }
}
