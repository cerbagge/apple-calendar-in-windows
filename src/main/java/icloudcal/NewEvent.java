package icloudcal;

import java.time.LocalDateTime;

/**
 * 새로 올리거나 고칠 일정. 하루 종일 일정이면 start/end 의 날짜만 쓰며 end 는 마지막 날(포함)이다.
 * alertMinutes 가 null 이면 알림을 넣지 않는다.
 */
record NewEvent(CalendarInfo calendar, String title, String location, String notes, boolean allDay,
                LocalDateTime start, LocalDateTime end, Integer alertMinutes) {
}
