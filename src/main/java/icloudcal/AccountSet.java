package icloudcal;

import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 여러 iCloud 계정을 하나의 캘린더 모음처럼 다룬다. 캘린더 목록은 계정 순서대로 합치고,
 * 일정 조회·추가·수정·삭제는 그 캘린더가 속한 계정으로 보낸다.
 * 서로 다른 계정에 이름이 같은 캘린더가 있으면 "이름 (AppleID)" 로 바꿔 구분한다.
 */
final class AccountSet implements CalendarBackend {
    private final List<CalDavClient> clients;
    /** 캘린더 주소 → 그 캘린더를 가진 계정 */
    private final Map<URI, CalDavClient> owners = new ConcurrentHashMap<>();
    /** 마지막 listCalendars 에서 연결하지 못한 계정과 그 이유 */
    private volatile Map<String, IOException> failures = Map.of();

    AccountSet(List<CalDavClient> clients) {
        this.clients = List.copyOf(clients);
    }

    static AccountSet of(List<CredentialStore.Account> accounts) {
        return new AccountSet(accounts.stream().map(a -> new CalDavClient(a.appleId(), a.password())).toList());
    }

    List<String> appleIds() {
        return clients.stream().map(CalDavClient::appleId).toList();
    }

    Map<String, IOException> failures() {
        return failures;
    }

    /** 일부 계정이 실패해도 나머지 계정의 캘린더를 돌려준다. 모든 계정이 실패하면 첫 오류를 던진다. */
    @Override
    public List<CalendarInfo> listCalendars() throws IOException {
        List<CalendarInfo> all = new ArrayList<>();
        Map<String, IOException> failed = new LinkedHashMap<>();
        for (CalDavClient client : clients) {
            try {
                for (CalendarInfo calendar : client.listCalendars()) {
                    owners.put(calendar.url(), client);
                    all.add(calendar);
                }
            } catch (IOException e) {
                failed.put(client.appleId(), e);
            }
        }
        failures = Map.copyOf(failed);
        if (all.isEmpty() && !failed.isEmpty()) {
            throw failed.values().iterator().next();
        }
        return disambiguate(all);
    }

    @Override
    public List<EventItem> fetchEvents(CalendarInfo calendar, ZonedDateTime from, ZonedDateTime to) throws IOException {
        return owner(calendar).fetchEvents(calendar, from, to);
    }

    @Override
    public void putEvent(NewEvent event) throws IOException {
        owner(event.calendar()).putEvent(event);
    }

    void updateEvent(EventItem original, NewEvent changes) throws IOException {
        owner(original.calendar()).updateEvent(original, changes);
    }

    void deleteEvent(EventItem item, boolean wholeSeries) throws IOException {
        owner(item.calendar()).deleteEvent(item, wholeSeries);
    }

    private CalDavClient owner(CalendarInfo calendar) throws IOException {
        CalDavClient client = owners.get(calendar.url());
        if (client == null) {
            throw new IOException("'" + calendar.name() + "' 캘린더의 계정을 찾지 못했습니다. 동기화한 뒤 다시 시도하십시오.");
        }
        return client;
    }

    /** 계정이 둘 이상이고 이름이 겹치는 캘린더에만 Apple ID 를 붙인다. */
    static List<CalendarInfo> disambiguate(List<CalendarInfo> calendars) {
        Map<String, Integer> counts = new HashMap<>();
        calendars.forEach(c -> counts.merge(c.name(), 1, Integer::sum));
        return calendars.stream()
                .map(c -> counts.get(c.name()) > 1 && c.account() != null
                        ? c.withName(c.name() + " (" + c.account() + ")") : c)
                .toList();
    }
}
