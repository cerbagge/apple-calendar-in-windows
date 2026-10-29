package icloudcal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final CalendarInfo SCHOOL = new CalendarInfo(URI.create("https://x/school/"), "학교 일정", "#cc73e1", true);
    private static final CalendarInfo HOME = new CalendarInfo(URI.create("https://x/home/"), "집", "#ff2968", true);
    private static final CalendarInfo HOLIDAY = new CalendarInfo(URI.create("https://x/holiday/"), "공휴일", null, false);

    private final ObjectMapper json = new ObjectMapper();
    private final FakeBackend backend = new FakeBackend();
    private final McpServer server = new McpServer(new CalendarTools(() -> backend, SEOUL));

    /** 실제 iCloud 대신 쓰는 가짜 캘린더 */
    private static final class FakeBackend implements CalendarBackend {
        final List<NewEvent> added = new ArrayList<>();
        final List<EventItem> events = new ArrayList<>();

        @Override
        public List<CalendarInfo> listCalendars() {
            return List.of(SCHOOL, HOME, HOLIDAY);
        }

        @Override
        public List<EventItem> fetchEvents(CalendarInfo calendar, ZonedDateTime from, ZonedDateTime to) {
            return events.stream()
                    .filter(e -> e.calendar().equals(calendar))
                    .filter(e -> e.start().isBefore(to) && e.end().isAfter(from))
                    .toList();
        }

        @Override
        public void putEvent(NewEvent event) {
            added.add(event);
        }
    }

    private JsonNode request(String body) throws Exception {
        return server.handle(json.readTree(body));
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return request("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}").path("result");
    }

    @Test
    void initializeAdvertisesToolsAndEchoesProtocolVersion() throws Exception {
        JsonNode res = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\"}}}");
        assertEquals(1, res.path("id").asInt());
        assertEquals("2025-03-26", res.path("result").path("protocolVersion").asText());
        assertTrue(res.path("result").path("capabilities").has("tools"));
        assertEquals("icloud-calendar", res.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void notificationsGetNoResponseAndUnknownMethodsError() throws Exception {
        assertNull(request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertEquals(-32601, request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}")
                .path("error").path("code").asInt());
        assertEquals(-32602, request("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}").path("error").path("code").asInt());
    }

    @Test
    void listsThreeTools() throws Exception {
        JsonNode tools = request("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}").path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertEquals(List.of("list_calendars", "list_events", "add_event"), names);
        assertEquals("title", tools.get(2).path("inputSchema").path("required").get(0).asText());
    }

    @Test
    void addTimedEventDefaultsToOneHourOnFirstWritableCalendar() throws Exception {
        JsonNode res = call("add_event", "{\"title\":\"치과\",\"start\":\"2026-10-27T14:00\",\"alert_minutes\":30}");
        assertFalse(res.path("isError").asBoolean(), res.toString());
        assertEquals(1, backend.added.size());
        NewEvent event = backend.added.get(0);
        assertEquals(SCHOOL, event.calendar());
        assertEquals(LocalDateTime.of(2026, 10, 27, 14, 0), event.start());
        assertEquals(LocalDateTime.of(2026, 10, 27, 15, 0), event.end());
        assertEquals(30, event.alertMinutes());
        assertFalse(event.allDay());
    }

    @Test
    void addAllDayEventToNamedCalendar() throws Exception {
        JsonNode res = call("add_event",
                "{\"title\":\"여행\",\"start\":\"2026-11-01\",\"end\":\"2026-11-03\",\"all_day\":true,\"calendar\":\"집\"}");
        assertFalse(res.path("isError").asBoolean(), res.toString());
        NewEvent event = backend.added.get(0);
        assertEquals(HOME, event.calendar());
        assertTrue(event.allDay());
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), event.start());
        assertEquals(LocalDateTime.of(2026, 11, 3, 0, 0), event.end());
        assertNull(event.alertMinutes());
    }

    @Test
    void invalidAddRequestsReturnToolErrorsWithoutWriting() throws Exception {
        assertTrue(call("add_event", "{\"title\":\"x\",\"start\":\"2026-10-27\"}").path("isError").asBoolean());
        assertTrue(call("add_event", "{\"title\":\"x\",\"start\":\"2026-10-27T15:00\",\"end\":\"2026-10-27T14:00\"}")
                .path("isError").asBoolean());
        JsonNode readOnly = call("add_event", "{\"title\":\"x\",\"start\":\"2026-10-27T15:00\",\"calendar\":\"공휴일\"}");
        assertTrue(readOnly.path("isError").asBoolean());
        JsonNode missing = call("add_event", "{\"title\":\"x\",\"start\":\"2026-10-27T15:00\",\"calendar\":\"회사\"}");
        assertTrue(missing.path("content").get(0).path("text").asText().contains("학교 일정"));
        assertTrue(backend.added.isEmpty());
    }

    @Test
    void listEventsUsesInclusiveEndDateAndCalendarFilter() throws Exception {
        backend.events.add(new EventItem("강의", "6206", null, ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, SEOUL),
                ZonedDateTime.of(2026, 10, 27, 11, 50, 0, 0, SEOUL), false, null, SCHOOL, null));
        backend.events.add(new EventItem("휴가", null, null, ZonedDateTime.of(2026, 10, 28, 0, 0, 0, 0, SEOUL),
                ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, SEOUL), true, null, HOME, null));

        JsonNode all = json.readTree(call("list_events", "{\"start\":\"2026-10-27\",\"end\":\"2026-10-28\"}")
                .path("content").get(0).path("text").asText());
        assertEquals(2, all.size());
        assertEquals("2026-10-27T10:00", all.get(0).path("start").asText());
        assertEquals("2026-10-29", all.get(1).path("end").asText());

        String oneDay = call("list_events", "{\"start\":\"2026-10-27\",\"calendar\":\"집\"}")
                .path("content").get(0).path("text").asText();
        assertEquals("해당 기간에 일정이 없습니다.", oneDay);
    }

    @Test
    void missingAccountIsReportedAsToolError() throws Exception {
        McpServer noAccount = new McpServer(new CalendarTools(() -> {
            throw new IllegalStateException("저장된 iCloud 계정이 없습니다.");
        }, SEOUL));
        JsonNode res = noAccount.handle(json.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"list_calendars\",\"arguments\":{}}}"));
        assertTrue(res.path("result").path("isError").asBoolean());
    }

    @Test
    void serveWritesOneJsonLinePerRequest() throws Exception {
        String input = "﻿{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "not json\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        assertEquals(1, json.readTree(lines[0]).path("id").asInt());
        assertEquals(-32700, json.readTree(lines[1]).path("error").path("code").asInt());
    }
}
