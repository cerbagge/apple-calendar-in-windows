package icloudcal;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VAlarm;
import biweekly.component.VEvent;
import biweekly.io.TimezoneAssignment;
import biweekly.parameter.Related;
import biweekly.property.DurationProperty;
import biweekly.property.ExceptionDates;
import biweekly.property.Trigger;
import biweekly.util.ICalDate;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/** iCalendar(.ics) 텍스트와 일정 객체를 서로 변환한다. */
final class IcsCodec {
    private IcsCodec() {
    }

    /** ics 안의 일정을 [from, to) 구간과 겹치는 회차로 펼친다. 반복 일정도 회차마다 하나씩 만든다. href 는 ics 의 서버 위치이다. */
    static List<EventItem> expand(String ics, CalendarInfo calendar, URI href, ZonedDateTime from, ZonedDateTime to) {
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
                    Long recurrenceId = event.getRecurrenceId() == null ? null
                            : event.getRecurrenceId().getValue().getTime();
                    add(out, event, start, length, allDay, calendar, new EventItem.Source(href, recurrenceId, false),
                            zone, from, to);
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
                        add(out, event, occurrence, length, allDay, calendar,
                                new EventItem.Source(href, null, true), zone, from, to);
                    }
                }
            }
        }
        return out;
    }

    static String build(String uid, NewEvent e) {
        VEvent event = new VEvent();
        event.setUid(uid);
        setText(event, e);
        setTimes(event, e);
        if (e.alertMinutes() != null) {
            addAlarm(event, e);
        }
        ICalendar ical = new ICalendar();
        ical.setProductId("-//icloud-calendar//KO");
        ical.addEvent(event);
        return Biweekly.write(ical).go();
    }

    /**
     * 서버에서 받은 ics 에서 original 회차의 VEVENT 를 changes 대로 고친다.
     * 반복 규칙으로 펼친 회차는 반복 전체에 적용되므로 시간은 그대로 둔다. 알림은 바뀐 경우에만 다시 쓴다.
     */
    static String update(String ics, EventItem original, NewEvent changes) throws IOException {
        List<ICalendar> all = Biweekly.parse(ics).all();
        VEvent target = null;
        for (ICalendar ical : all) {
            for (VEvent event : ical.getEvents()) {
                Long id = event.getRecurrenceId() == null ? null : event.getRecurrenceId().getValue().getTime();
                if (target == null && Objects.equals(id, original.source().recurrenceId())) {
                    target = event;
                }
            }
        }
        if (target == null) {
            throw new IOException("서버에서 수정할 일정을 찾지 못했습니다. 새로 고친 뒤 다시 시도하십시오.");
        }
        setText(target, changes);
        if (!original.source().series()) {
            target.removeProperties(DurationProperty.class);
            setTimes(target, changes);
        }
        if (!Objects.equals(changes.alertMinutes(), original.alertMinutes())) {
            target.removeComponents(VAlarm.class);
            if (changes.alertMinutes() != null) {
                addAlarm(target, changes);
            }
        }
        target.setLastModified(new Date());
        return Biweekly.write(all).go();
    }

    /**
     * 반복 일정에서 occurrence 회차 하나만 뺀다. 반복 규칙에는 EXDATE 를 더하고, 따로 수정된 회차면 그 VEVENT 도 지운다.
     * 파일에 남는 일정이 없으면 null 을 돌려준다(파일째 지워야 한다).
     */
    static String removeOccurrence(String ics, EventItem occurrence) throws IOException {
        List<ICalendar> all = Biweekly.parse(ics).all();
        // 따로 수정된 회차는 RECURRENCE-ID(원래 시작 시각)로, 규칙으로 펼친 회차는 그 시작 시각으로 찾는다
        Long overrideId = occurrence.source().recurrenceId();
        Date original = new Date(overrideId != null ? overrideId : occurrence.start().toInstant().toEpochMilli());
        boolean found = false;
        int remaining = 0;
        for (ICalendar ical : all) {
            for (VEvent event : new ArrayList<>(ical.getEvents())) {
                if (event.getRecurrenceId() == null) {
                    if (event.getRecurrenceRule() != null) {
                        ExceptionDates excluded = new ExceptionDates();
                        excluded.getValues().add(new ICalDate(original, !occurrence.allDay()));
                        event.addExceptionDates(excluded);
                        event.setLastModified(new Date());
                        found = true;
                    }
                } else if (event.getRecurrenceId().getValue().getTime() == original.getTime()) {
                    ical.removeComponent(event);
                    found = true;
                }
            }
            remaining += ical.getEvents().size();
        }
        if (!found) {
            throw new IOException("서버에서 삭제할 일정을 찾지 못했습니다. 새로 고친 뒤 다시 시도하십시오.");
        }
        return remaining == 0 ? null : Biweekly.write(all).go();
    }

    private static void setText(VEvent event, NewEvent e) {
        event.setSummary(e.title());
        event.setLocation(blank(e.location()) ? null : e.location());
        event.setDescription(blank(e.notes()) ? null : e.notes());
    }

    private static void setTimes(VEvent event, NewEvent e) {
        ZoneId zone = ZoneId.systemDefault();
        if (e.allDay()) {
            // 하루 종일 일정의 DTEND 는 마지막 날 다음 날이다
            event.setDateStart(toDate(e.start().toLocalDate().atStartOfDay(), zone), false);
            event.setDateEnd(toDate(e.end().toLocalDate().plusDays(1).atStartOfDay(), zone), false);
        } else {
            event.setDateStart(toDate(e.start(), zone), true);
            event.setDateEnd(toDate(e.end(), zone), true);
        }
    }

    private static void addAlarm(VEvent event, NewEvent e) {
        biweekly.util.Duration before = biweekly.util.Duration.builder()
                .prior(e.alertMinutes() > 0).minutes(e.alertMinutes()).build();
        event.addAlarm(VAlarm.display(new Trigger(before, Related.START), e.title()));
    }

    /** 시작 기준으로 몇 분 전에 울리는 첫 알림. 없거나 다른 형식(절대 시각, 시작 후 등)이면 null. */
    private static Integer alertMinutes(VEvent event) {
        for (VAlarm alarm : event.getAlarms()) {
            Trigger trigger = alarm.getTrigger();
            if (trigger == null || trigger.getDuration() == null || trigger.getRelated() == Related.END) {
                continue;
            }
            long millis = trigger.getDuration().toMillis();
            if (millis <= 0) {
                return (int) (-millis / 60_000);
            }
        }
        return null;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static void add(List<EventItem> out, VEvent event, Date start, long length, boolean allDay,
                            CalendarInfo calendar, EventItem.Source source,
                            ZoneId zone, ZonedDateTime from, ZonedDateTime to) {
        ZonedDateTime s = Instant.ofEpochMilli(start.getTime()).atZone(zone);
        ZonedDateTime e = s.plusNanos(length * 1_000_000);
        boolean overlaps = s.isBefore(to) && (e.isAfter(from) || (length == 0 && !s.isBefore(from)));
        if (!overlaps) {
            return;
        }
        String title = event.getSummary() == null ? "(제목 없음)" : event.getSummary().getValue();
        String location = event.getLocation() == null ? null : event.getLocation().getValue();
        // biweekly 는 줄바꿈을 OS 줄바꿈(Windows 는 \r\n)으로 풀어 주므로 \n 으로 맞춘다
        String notes = event.getDescription() == null ? null
                : event.getDescription().getValue().replace("\r\n", "\n");
        out.add(new EventItem(title, location, notes, s, e, allDay, alertMinutes(event), calendar, source));
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
