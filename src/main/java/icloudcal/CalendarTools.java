package icloudcal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** MCP 도구 3종: list_calendars, list_events, add_event */
final class CalendarTools {
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ObjectMapper json = new ObjectMapper();
    private final Supplier<CalendarBackend> factory;
    private final ZoneId zone;
    private CalendarBackend backend;

    CalendarTools(Supplier<CalendarBackend> factory, ZoneId zone) {
        this.factory = factory;
        this.zone = zone;
    }

    ObjectNode list() {
        ObjectNode result = json.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode calendars = tool(tools, "list_calendars",
                "iCloud 캘린더 목록을 조회합니다. 일정을 추가할 캘린더 이름과 쓰기 가능 여부를 확인할 때 사용합니다.", true);
        calendars.putObject("inputSchema").put("type", "object").putObject("properties");

        ObjectNode events = tool(tools, "list_events",
                "기간 안의 iCloud 일정을 조회합니다. 반복 일정은 회차별로 펼쳐서 돌려줍니다. 시간은 "
                        + zone.getId() + " 기준입니다.", true);
        ObjectNode eventProps = schema(events, "start");
        prop(eventProps, "start", "string", "조회 시작 날짜 (YYYY-MM-DD, 포함)");
        prop(eventProps, "end", "string", "조회 끝 날짜 (YYYY-MM-DD, 포함). 생략하면 start 하루만 조회합니다.");
        prop(eventProps, "calendar", "string", "캘린더 이름. 생략하면 모든 캘린더를 조회합니다.");

        ObjectNode add = tool(tools, "add_event",
                "iCloud 캘린더에 일정을 추가합니다. 시간은 " + zone.getId() + " 기준입니다.", false);
        ObjectNode addProps = schema(add, "title", "start");
        prop(addProps, "title", "string", "일정 제목");
        prop(addProps, "start", "string",
                "시작. 시간 일정은 YYYY-MM-DDTHH:MM, 하루 종일 일정은 YYYY-MM-DD");
        prop(addProps, "end", "string",
                "종료. 시간 일정은 YYYY-MM-DDTHH:MM, 하루 종일 일정은 마지막 날짜(포함). "
                        + "생략하면 시간 일정은 시작 1시간 뒤, 하루 종일 일정은 시작과 같은 날입니다.");
        prop(addProps, "all_day", "boolean", "하루 종일 일정이면 true (기본값 false)");
        prop(addProps, "calendar", "string", "캘린더 이름. 생략하면 쓰기 가능한 첫 번째 캘린더에 추가합니다.");
        prop(addProps, "location", "string", "위치 (선택)");
        prop(addProps, "alert_minutes", "integer", "시작 몇 분 전에 알릴지. 0 이면 시작 시각, 생략하면 알림 없음.");
        return result;
    }

    /** 도구를 실행한다. 알 수 없는 도구면 null. 실행 중 오류는 isError 결과로 돌려준다. */
    ObjectNode call(String name, JsonNode args) {
        try {
            String text = switch (name) {
                case "list_calendars" -> listCalendars();
                case "list_events" -> listEvents(args);
                case "add_event" -> addEvent(args);
                default -> null;
            };
            return text == null ? null : result(text, false);
        } catch (IOException | RuntimeException e) {
            return result(message(e), true);
        }
    }

    private String listCalendars() throws IOException {
        ArrayNode out = json.createArrayNode();
        for (CalendarInfo calendar : backend().listCalendars()) {
            ObjectNode item = out.addObject();
            item.put("name", calendar.name());
            item.put("account", calendar.account());
            item.put("writable", calendar.writable());
            item.put("color", calendar.color());
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    private String listEvents(JsonNode args) throws IOException {
        LocalDate start = date(required(args, "start"), "start");
        LocalDate end = args.hasNonNull("end") ? date(args.get("end").asText(), "end") : start;
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end 는 start 보다 앞설 수 없습니다.");
        }
        List<CalendarInfo> calendars = backend().listCalendars();
        if (args.hasNonNull("calendar")) {
            calendars = List.of(findCalendar(calendars, args.get("calendar").asText()));
        }
        ZonedDateTime from = start.atStartOfDay(zone);
        ZonedDateTime to = end.plusDays(1).atStartOfDay(zone);
        List<EventItem> events = new ArrayList<>();
        for (CalendarInfo calendar : calendars) {
            events.addAll(backend().fetchEvents(calendar, from, to));
        }
        events.sort(Comparator.comparing(EventItem::start));

        ArrayNode out = json.createArrayNode();
        for (EventItem event : events) {
            ObjectNode item = out.addObject();
            item.put("title", event.title());
            item.put("all_day", event.allDay());
            if (event.allDay()) {
                item.put("start", event.start().toLocalDate().toString());
                item.put("end", event.end().toLocalDate().minusDays(1).toString());
            } else {
                item.put("start", event.start().withZoneSameInstant(zone).format(MINUTES));
                item.put("end", event.end().withZoneSameInstant(zone).format(MINUTES));
            }
            if (event.location() != null && !event.location().isBlank()) {
                item.put("location", event.location());
            }
            item.put("calendar", event.calendar().name());
        }
        return events.isEmpty() ? "해당 기간에 일정이 없습니다." : json.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    private String addEvent(JsonNode args) throws IOException {
        String title = required(args, "title").strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title 이 비어 있습니다.");
        }
        boolean allDay = args.path("all_day").asBoolean(false);
        String startText = required(args, "start");
        String endText = args.hasNonNull("end") ? args.get("end").asText() : null;

        LocalDateTime start;
        LocalDateTime end;
        if (allDay) {
            start = date(startText.length() > 10 ? startText.substring(0, 10) : startText, "start").atStartOfDay();
            end = endText == null ? start
                    : date(endText.length() > 10 ? endText.substring(0, 10) : endText, "end").atStartOfDay();
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("end 는 start 보다 앞설 수 없습니다.");
            }
        } else {
            start = dateTime(startText, "start");
            end = endText == null ? start.plusHours(1) : dateTime(endText, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end 는 start 보다 늦어야 합니다.");
            }
        }
        Integer alert = null;
        if (args.hasNonNull("alert_minutes")) {
            alert = args.get("alert_minutes").asInt();
            if (alert < 0) {
                throw new IllegalArgumentException("alert_minutes 는 0 이상이어야 합니다.");
            }
        }
        String location = args.hasNonNull("location") ? args.get("location").asText().strip() : null;

        List<CalendarInfo> calendars = backend().listCalendars();
        CalendarInfo calendar;
        if (args.hasNonNull("calendar")) {
            calendar = findCalendar(calendars, args.get("calendar").asText());
            if (!calendar.writable()) {
                throw new IllegalArgumentException("'" + calendar.name() + "' 캘린더는 읽기 전용입니다.");
            }
        } else {
            calendar = calendars.stream().filter(CalendarInfo::writable).findFirst().orElseThrow(() ->
                    new IllegalArgumentException("일정을 추가할 수 있는 캘린더가 없습니다."));
        }

        backend().putEvent(new NewEvent(calendar, title, location, null, allDay, start, end, alert));
        String when = allDay
                ? start.toLocalDate() + (end.equals(start) ? "" : " ~ " + end.toLocalDate()) + " 하루 종일"
                : start.format(MINUTES) + " ~ " + end.format(MINUTES);
        return "일정을 추가했습니다: " + title + " (" + calendar.name() + ", " + when + ")";
    }

    private CalendarBackend backend() {
        if (backend == null) {
            backend = factory.get();
        }
        return backend;
    }

    private static CalendarInfo findCalendar(List<CalendarInfo> calendars, String name) {
        return calendars.stream()
                .filter(c -> c.name().equalsIgnoreCase(name.strip()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("'" + name + "' 캘린더가 없습니다. 있는 캘린더: "
                        + String.join(", ", calendars.stream().map(CalendarInfo::name).toList())));
    }

    private static String required(JsonNode args, String field) {
        if (!args.hasNonNull(field)) {
            throw new IllegalArgumentException(field + " 값이 필요합니다.");
        }
        return args.get(field).asText();
    }

    private static LocalDate date(String text, String field) {
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 는 YYYY-MM-DD 형식이어야 합니다: " + text);
        }
    }

    private static LocalDateTime dateTime(String text, String field) {
        try {
            return LocalDateTime.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 는 YYYY-MM-DDTHH:MM 형식이어야 합니다: " + text
                    + " (하루 종일 일정이면 all_day 를 true 로 지정하십시오)");
        }
    }

    private static String message(Exception e) {
        if (e instanceof CalDavClient.AuthException) {
            return "Apple ID 또는 앱 암호가 올바르지 않습니다. 달력 앱에서 계정을 다시 연결하십시오.";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private ObjectNode result(String text, boolean isError) {
        ObjectNode result = json.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private static ObjectNode tool(ArrayNode tools, String name, String description, boolean readOnly) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        tool.putObject("annotations").put("readOnlyHint", readOnly);
        return tool;
    }

    private static ObjectNode schema(ObjectNode tool, String... required) {
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ArrayNode names = schema.putArray("required");
        for (String name : required) {
            names.add(name);
        }
        return schema.putObject("properties");
    }

    private static void prop(ObjectNode properties, String name, String type, String description) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", type);
        prop.put("description", description);
    }
}
