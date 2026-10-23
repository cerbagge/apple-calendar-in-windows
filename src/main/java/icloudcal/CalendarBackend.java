package icloudcal;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

/** MCP 도구가 쓰는 캘린더 기능. 실제 구현은 CalDavClient 이고, 테스트에서는 가짜로 바꿔 끼운다. */
interface CalendarBackend {
    List<CalendarInfo> listCalendars() throws IOException;

    List<EventItem> fetchEvents(CalendarInfo calendar, ZonedDateTime from, ZonedDateTime to) throws IOException;

    void putEvent(NewEvent event) throws IOException;
}
