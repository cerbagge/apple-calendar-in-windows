package icloudcal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsCodecTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final CalendarInfo CAL = new CalendarInfo(URI.create("https://example.com/cal/"), "집", "#ff0000", true);

    private static ZonedDateTime seoul(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, SEOUL);
    }

    @Test
    void expandsWeeklyRecurrenceWithExdateAndOverride() {
        // 매주 금요일 10:00~11:00 (서울), 10/16 회차 삭제, 10/23 회차는 14:00 으로 이동
        String ics = """
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
        List<EventItem> events = IcsCodec.expand(ics, CAL, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0));
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
        List<EventItem> events = IcsCodec.expand(ics, CAL,
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
        assertTrue(IcsCodec.expand(ics, CAL, seoul(2026, 10, 1, 0, 0), seoul(2026, 11, 1, 0, 0)).isEmpty());
    }

    @Test
    void builtEventParsesBack() {
        NewEvent timed = new NewEvent(CAL, "치과", "강남역", false,
                LocalDateTime.of(2026, 10, 23, 14, 0), LocalDateTime.of(2026, 10, 23, 15, 30), 15);
        String ics = IcsCodec.build("UID-1", timed);

        assertTrue(ics.contains("UID:UID-1"), ics);
        assertTrue(ics.contains("BEGIN:VALARM"), ics);
        assertTrue(ics.contains("TRIGGER;RELATED=START:-PT15M"), ics);

        ZoneId zone = ZoneId.systemDefault();
        List<EventItem> parsed = IcsCodec.expand(ics, CAL,
                LocalDate.of(2026, 10, 23).atStartOfDay(zone), LocalDate.of(2026, 10, 24).atStartOfDay(zone));
        assertEquals(1, parsed.size());
        assertEquals("치과", parsed.get(0).title());
        assertEquals("강남역", parsed.get(0).location());
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), parsed.get(0).start().toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 10, 23, 15, 30), parsed.get(0).end().toLocalDateTime());
    }

    @Test
    void builtAllDayEventUsesDateValuesAndExclusiveEnd() {
        NewEvent allDay = new NewEvent(CAL, "휴가", null, true,
                LocalDateTime.of(2026, 10, 26, 0, 0), LocalDateTime.of(2026, 10, 27, 0, 0), null);
        String ics = IcsCodec.build("UID-2", allDay);

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261026"), ics);
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261028"), ics);
        assertFalse(ics.contains("VALARM"), ics);
    }

    @Test
    void alertAtStartTimeIsWritten() {
        NewEvent atStart = new NewEvent(CAL, "알림", null, false,
                LocalDateTime.of(2026, 10, 23, 9, 0), LocalDateTime.of(2026, 10, 23, 10, 0), 0);
        String ics = IcsCodec.build("UID-3", atStart);
        assertTrue(ics.contains("TRIGGER;RELATED=START:PT0M\r\n"), ics);
    }
}
