package icloudcal;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VAlarm;
import biweekly.component.VEvent;
import biweekly.io.TimezoneAssignment;
import biweekly.parameter.Related;
import biweekly.property.Trigger;
import biweekly.util.ICalDate;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** iCalendar(.ics) 텍스트와 일정 객체를 서로 변환한다. */
final class IcsCodec {
    private IcsCodec() {
    }

    /** ics 안의 일정을 [from, to) 구간과 겹치는 회차로 펼친다. 반복 일정도 회차마다 하나씩 만든다. */
    static List<EventItem> expand(String ics, CalendarInfo calendar, ZonedDateTime from, ZonedDateTime to) {
        ZoneId zone = from.getZone();
        List<EventItem> out = new ArrayList<>();
        for (ICalendar ical : Biweekly.parse(ics).all()) {
            // RECURRENCE-ID 로 따로 수정된 회차는 원래 반복 규칙에서 빼고 수정본으로 표시한다
            Set<Long> overridden = new HashSet<>();
            for (VEvent event : ical.getEvents()) {
                if (event.getRecurrenceId() != null) {
                    overridden.add(event.getRecurrenceId().getValue().getTime());
                }
            }
            for (VEvent event : ical.getEvents()) {
                if (event.getDateStart() == null || isCancelled(event)) {
                    continue;
                }
                ICalDate start = event.getDateStart().getValue();
                boolean allDay = !start.hasTime();
                long length = lengthMillis(event, start, allDay);

                if (event.getRecurrenceRule() == null || event.getRecurrenceId() != null) {
                    add(out, event, start, length, allDay, calendar, zone, from, to);
                    continue;
                }
                TimeZone tz = allDay ? TimeZone.getTimeZone(zone) : timezone(ical, event, zone);
                DateIterator it = event.getDateIterator(tz);
                it.advanceTo(new Date(from.toInstant().toEpochMilli() - length));
                while (it.hasNext()) {
                    Date occurrence = it.next();
                    if (!occurrence.before(Date.from(to.toInstant()))) {
                        break;
                    }
                    if (!overridden.contains(occurrence.getTime())) {
                        add(out, event, occurrence, length, allDay, calendar, zone, from, to);
                    }
                }
            }
        }
        return out;
    }

    static String build(String uid, NewEvent e) {
        ZoneId zone = ZoneId.systemDefault();
        VEvent event = new VEvent();
        event.setUid(uid);
        event.setSummary(e.title());
        if (e.location() != null && !e.location().isBlank()) {
            event.setLocation(e.location());
        }
        if (e.allDay()) {
            // 하루 종일 일정의 DTEND 는 마지막 날 다음 날이다
            event.setDateStart(toDate(e.start().toLocalDate().atStartOfDay(), zone), false);
            event.setDateEnd(toDate(e.end().toLocalDate().plusDays(1).atStartOfDay(), zone), false);
        } else {
            event.setDateStart(toDate(e.start(), zone), true);
            event.setDateEnd(toDate(e.end(), zone), true);
        }
        if (e.alertMinutes() != null) {
            biweekly.util.Duration before = biweekly.util.Duration.builder()
                    .prior(e.alertMinutes() > 0).minutes(e.alertMinutes()).build();
            event.addAlarm(VAlarm.display(new Trigger(before, Related.START), e.title()));
        }
        ICalendar ical = new ICalendar();
        ical.setProductId("-//icloud-calendar//KO");
        ical.addEvent(event);
        return Biweekly.write(ical).go();
    }

    private static void add(List<EventItem> out, VEvent event, Date start, long length, boolean allDay,
                            CalendarInfo calendar, ZoneId zone, ZonedDateTime from, ZonedDateTime to) {
        ZonedDateTime s = Instant.ofEpochMilli(start.getTime()).atZone(zone);
        ZonedDateTime e = s.plusNanos(length * 1_000_000);
        boolean overlaps = s.isBefore(to) && (e.isAfter(from) || (length == 0 && !s.isBefore(from)));
        if (!overlaps) {
            return;
        }
        String title = event.getSummary() == null ? "(제목 없음)" : event.getSummary().getValue();
        String location = event.getLocation() == null ? null : event.getLocation().getValue();
        out.add(new EventItem(title, location, s, e, allDay, calendar));
    }

    private static long lengthMillis(VEvent event, ICalDate start, boolean allDay) {
        if (event.getDateEnd() != null) {
            return Math.max(0, event.getDateEnd().getValue().getTime() - start.getTime());
        }
        if (event.getDuration() != null) {
            return event.getDuration().getValue().toMillis();
        }
        return allDay ? 24L * 60 * 60 * 1000 : 0;
    }

    private static TimeZone timezone(ICalendar ical, VEvent event, ZoneId fallback) {
        TimezoneAssignment assignment = ical.getTimezoneInfo().getTimezone(event.getDateStart());
        return assignment == null ? TimeZone.getTimeZone(fallback) : assignment.getTimeZone();
    }

    private static boolean isCancelled(VEvent event) {
        return event.getStatus() != null && event.getStatus().isCancelled();
    }

    private static Date toDate(LocalDateTime time, ZoneId zone) {
        return Date.from(time.atZone(zone).toInstant());
    }
}
