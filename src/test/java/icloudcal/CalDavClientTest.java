package icloudcal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalDavClientTest {
    private static final URI HOME = URI.create("https://p42-caldav.icloud.com:443/123456/calendars/");

    @Test
    void parsesEventCalendarsAndSkipsOthers() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/">
                  <response>
                    <href>/123456/calendars/</href>
                    <propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                  <response>
                    <href>/123456/calendars/home/</href>
                    <propstat><prop>
                      <displayname>집</displayname>
                      <resourcetype><collection/><C:calendar/></resourcetype>
                      <A:calendar-color>#FF2968FF</A:calendar-color>
                      <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>
                      <current-user-privilege-set><privilege><all/></privilege></current-user-privilege-set>
                    </prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                  <response>
                    <href>/123456/calendars/shared/</href>
                    <propstat><prop>
                      <displayname>공유받은 캘린더</displayname>
                      <resourcetype><collection/><C:calendar/></resourcetype>
                      <A:calendar-color>blue</A:calendar-color>
                      <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>
                      <current-user-privilege-set><privilege><read/></privilege></current-user-privilege-set>
                    </prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                  <response>
                    <href>/123456/calendars/tasks/</href>
                    <propstat><prop>
                      <displayname>미리 알림</displayname>
                      <resourcetype><collection/><C:calendar/></resourcetype>
                      <C:supported-calendar-component-set><C:comp name="VTODO"/></C:supported-calendar-component-set>
                    </prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                  <response>
                    <href>/123456/calendars/inbox/</href>
                    <propstat><prop><resourcetype><collection/><C:schedule-inbox/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                </multistatus>
                """;
        List<CalendarInfo> calendars = CalDavClient.parseCalendars(
                CalDavClient.parseXml(xml.strip().getBytes(StandardCharsets.UTF_8)), HOME);

        assertEquals(2, calendars.size());
        CalendarInfo home = calendars.get(0);
        assertEquals("집", home.name());
        assertEquals(URI.create("https://p42-caldav.icloud.com:443/123456/calendars/home/"), home.url());
        assertEquals("#FF2968", home.color());
        assertTrue(home.writable());

        CalendarInfo shared = calendars.get(1);
        assertNull(shared.color());
        assertFalse(shared.writable());
    }

    @Test
    void extractsCalendarData() throws Exception {
        String xml = """
                <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <response>
                    <href>/123456/calendars/home/a.ics</href>
                    <propstat><prop><getetag>"1"</getetag><C:calendar-data>BEGIN:VCALENDAR
                END:VCALENDAR</C:calendar-data></prop><status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                </multistatus>
                """;
        List<CalDavClient.Resource> data =
                CalDavClient.calendarData(CalDavClient.parseXml(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, data.size());
        assertEquals("/123456/calendars/home/a.ics", data.get(0).href());
        assertTrue(data.get(0).ics().startsWith("BEGIN:VCALENDAR"));
    }
}
