package icloudcal;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/** 화면에 표시할 일정 한 회차. 하루 종일 일정의 end 는 마지막 날 다음 날 0시이다. */
record EventItem(String title, String location, ZonedDateTime start, ZonedDateTime end,
                 boolean allDay, CalendarInfo calendar) {

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
