package icloudcal;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 화면에 표시할 일정 한 회차. 하루 종일 일정의 end 는 마지막 날 다음 날 0시이다.
 * alertMinutes 는 시작 몇 분 전 알림인지(없거나 해석할 수 없으면 null), source 는 수정할 때 쓰는 서버 위치이다.
 */
record EventItem(String title, String location, String notes, ZonedDateTime start, ZonedDateTime end,
                 boolean allDay, Integer alertMinutes, CalendarInfo calendar, Source source) {

    /**
     * 이 회차가 들어 있는 서버의 .ics 파일.
     * recurrenceId 는 따로 수정된 반복 회차의 RECURRENCE-ID(epoch ms)이고, series 는 반복 규칙으로 펼친 회차인지이다.
     */
    record Source(URI href, Long recurrenceId, boolean series) {
    }

    boolean occursOn(LocalDate day) {
        ZonedDateTime dayStart = day.atStartOfDay(start.getZone());
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        if (!start.isBefore(dayEnd)) {
            return false;
        }
        // 길이가 0 인 일정은 시작 시각이 속한 날에 표시한다
        return end.isAfter(dayStart) || !start.isBefore(dayStart);
    }
}
