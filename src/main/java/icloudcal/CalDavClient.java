package icloudcal;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** iCloud CalDAV 서버와 통신한다. */
final class CalDavClient implements CalendarBackend {
    static final String DAV = "DAV:";
    static final String CALDAV = "urn:ietf:params:xml:ns:caldav";
    static final String APPLE = "http://apple.com/ns/ical/";

    private static final URI ROOT = URI.create("https://caldav.icloud.com/");
    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** Apple ID 또는 앱 암호가 틀렸을 때 */
    static final class AuthException extends IOException {
        AuthException() {
            super("인증 실패");
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String authorization;
    private URI home;

    CalDavClient(String appleId, String appPassword) {
        String token = appleId + ":" + appPassword;
        authorization = "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /** 일정을 담을 수 있는 캘린더 목록. 처음 호출할 때 계정 확인도 겸한다. */
    @Override
    public List<CalendarInfo> listCalendars() throws IOException {
        if (home == null) {
            home = discoverHome();
        }
        Document doc = propfind(home, "1", """
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
                  <d:prop>
                    <d:displayname/><d:resourcetype/><a:calendar-color/>
                    <c:supported-calendar-component-set/><d:current-user-privilege-set/>
                  </d:prop>
                </d:propfind>""");
        return parseCalendars(doc, home);
    }

    @Override
    public List<EventItem> fetchEvents(CalendarInfo calendar, ZonedDateTime from, ZonedDateTime to) throws IOException {
        String body = """
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:getetag/><c:calendar-data/></d:prop>
                  <c:filter>
                    <c:comp-filter name="VCALENDAR">
                      <c:comp-filter name="VEVENT">
                        <c:time-range start="%s" end="%s"/>
                      </c:comp-filter>
                    </c:comp-filter>
                  </c:filter>
                </c:calendar-query>""".formatted(utc(from), utc(to));
        HttpResponse<byte[]> res = send("REPORT", calendar.url(), body, Map.of(
                "Depth", "1", "Content-Type", "application/xml; charset=utf-8"));
        expect(res, 207);
        List<EventItem> events = new ArrayList<>();
        for (String ics : calendarData(parseXml(res.body()))) {
            events.addAll(IcsCodec.expand(ics, calendar, from, to));
        }
        return events;
    }

    @Override
    public void putEvent(NewEvent event) throws IOException {
        String uid = UUID.randomUUID().toString().toUpperCase();
        URI target = event.calendar().url().resolve(uid + ".ics");
        HttpResponse<byte[]> res = send("PUT", target, IcsCodec.build(uid, event), Map.of(
                "Content-Type", "text/calendar; charset=utf-8", "If-None-Match", "*"));
        expect(res, 201, 204);
    }

    private URI discoverHome() throws IOException {
        Document principalDoc = propfind(ROOT, "0",
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>");
        URI principal = ROOT.resolve(hrefIn(principalDoc, DAV, "current-user-principal"));
        Document homeDoc = propfind(principal, "0", """
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><c:calendar-home-set/></d:prop>
                </d:propfind>""");
        return principal.resolve(hrefIn(homeDoc, CALDAV, "calendar-home-set"));
    }

    private Document propfind(URI uri, String depth, String body) throws IOException {
        HttpResponse<byte[]> res = send("PROPFIND", uri, body, Map.of(
                "Depth", depth, "Content-Type", "application/xml; charset=utf-8"));
        expect(res, 207);
        return parseXml(res.body());
    }

    /** iCloud 는 계정마다 다른 서버(pNN-caldav)로 보내므로 리다이렉트를 같은 메서드로 따라간다. */
    private HttpResponse<byte[]> send(String method, URI uri, String body, Map<String, String> headers)
            throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(req::header);
            HttpResponse<byte[]> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("요청이 중단되었습니다.", e);
            }
            int code = res.statusCode();
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = res.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return res;
                }
                uri = uri.resolve(location);
                continue;
            }
            if (code == 401) {
                throw new AuthException();
            }
            return res;
        }
        throw new IOException("리다이렉트가 너무 많습니다.");
    }

    private static void expect(HttpResponse<byte[]> res, int... codes) throws IOException {
        for (int code : codes) {
            if (res.statusCode() == code) {
                return;
            }
        }
        throw new IOException("서버 응답 오류 (HTTP " + res.statusCode() + ")");
    }

    private static String utc(ZonedDateTime time) {
        return time.withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP);
    }

    // ---- 응답 해석 (테스트에서 직접 호출) ----

    static Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("서버 응답을 해석하지 못했습니다.", e);
        }
    }

    static List<CalendarInfo> parseCalendars(Document doc, URI base) {
        List<CalendarInfo> calendars = new ArrayList<>();
        for (Element response : elements(doc.getDocumentElement(), DAV, "response")) {
            Element type = first(response, DAV, "resourcetype");
            if (type == null || first(type, CALDAV, "calendar") == null) {
                continue;
            }
            Element components = first(response, CALDAV, "supported-calendar-component-set");
            if (components != null && !supportsEvents(components)) {
                continue; // 미리 알림(VTODO) 목록 등
            }
            String href = text(first(response, DAV, "href"));
            String name = text(first(response, DAV, "displayname"));
            calendars.add(new CalendarInfo(base.resolve(href), name.isBlank() ? "캘린더" : name,
                    color(text(first(response, APPLE, "calendar-color"))), writable(response)));
        }
        return calendars;
    }

    static List<String> calendarData(Document doc) {
        List<String> data = new ArrayList<>();
        for (Element element : elements(doc.getDocumentElement(), CALDAV, "calendar-data")) {
            String ics = element.getTextContent();
            if (ics != null && !ics.isBlank()) {
                data.add(ics);
            }
        }
        return data;
    }

    private static boolean supportsEvents(Element components) {
        for (Element comp : elements(components, CALDAV, "comp")) {
            if ("VEVENT".equalsIgnoreCase(comp.getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean writable(Element response) {
        Element privileges = first(response, DAV, "current-user-privilege-set");
        if (privileges == null) {
            return true;
        }
        for (String name : List.of("all", "write", "write-content", "bind")) {
            if (first(privileges, DAV, name) != null) {
                return true;
            }
        }
        return false;
    }

    /** "#RRGGBBAA" 처럼 오는 색을 "#RRGGBB" 로 바꾼다. 형식이 다르면 null. */
    private static String color(String raw) {
        String value = raw.strip();
        if (value.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            return value.substring(0, 7);
        }
        return null;
    }

    private static String hrefIn(Document doc, String ns, String property) throws IOException {
        Element prop = first(doc.getDocumentElement(), ns, property);
        Element href = prop == null ? null : first(prop, DAV, "href");
        if (href == null) {
            throw new IOException("CalDAV 응답에 " + property + " 가 없습니다.");
        }
        return text(href);
    }

    private static List<Element> elements(Element parent, String ns, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, name);
        List<Element> list = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            list.add((Element) nodes.item(i));
        }
        return list;
    }

    private static Element first(Element parent, String ns, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }
}
