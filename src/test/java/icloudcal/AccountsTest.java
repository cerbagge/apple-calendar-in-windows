package icloudcal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsTest {

    @Test
    void readsOldSingleAccountFormat() {
        List<CredentialStore.Account> accounts = CredentialStore.decode("me@icloud.com\nabcd-efgh-ijkl-mnop");
        assertEquals(List.of(new CredentialStore.Account("me@icloud.com", "abcd-efgh-ijkl-mnop")), accounts);
    }

    @Test
    void encodesAndDecodesSeveralAccounts() {
        List<CredentialStore.Account> accounts = List.of(
                new CredentialStore.Account("me@icloud.com", "aaaa-bbbb-cccc-dddd"),
                new CredentialStore.Account("school@icloud.com", "eeee-ffff-gggg-hhhh"));
        assertEquals(accounts, CredentialStore.decode(CredentialStore.encode(accounts)));
    }

    @Test
    void brokenContentGivesNoAccounts() {
        assertTrue(CredentialStore.decode("").isEmpty());
        assertTrue(CredentialStore.decode("only-one-line").isEmpty());
    }

    @Test
    void sameNamedCalendarsFromDifferentAccountsGetAccountSuffix() {
        CalendarInfo myHome = new CalendarInfo(URI.create("https://a/home/"), "집", null, true, "me@icloud.com");
        CalendarInfo myWork = new CalendarInfo(URI.create("https://a/work/"), "직장", null, true, "me@icloud.com");
        CalendarInfo schoolHome = new CalendarInfo(URI.create("https://b/home/"), "집", null, true, "school@icloud.com");

        List<String> names = AccountSet.disambiguate(List.of(myHome, myWork, schoolHome)).stream()
                .map(CalendarInfo::name).toList();

        assertEquals(List.of("집 (me@icloud.com)", "직장", "집 (school@icloud.com)"), names);
    }

    @Test
    void singleAccountNamesStayAsIs() {
        CalendarInfo home = new CalendarInfo(URI.create("https://a/home/"), "집", null, true, "me@icloud.com");
        CalendarInfo work = new CalendarInfo(URI.create("https://a/work/"), "직장", null, true, "me@icloud.com");
        assertEquals(List.of(home, work), AccountSet.disambiguate(List.of(home, work)));
    }
}
