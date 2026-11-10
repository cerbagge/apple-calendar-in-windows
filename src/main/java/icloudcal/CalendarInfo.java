package icloudcal;

import java.net.URI;

/** iCloud 캘린더 하나. color 는 "#RRGGBB" 형식이고, account 는 이 캘린더가 속한 Apple ID 이다. */
record CalendarInfo(URI url, String name, String color, boolean writable, String account) {
    CalendarInfo(URI url, String name, String color, boolean writable) {
        this(url, name, color, writable, null);
    }

    CalendarInfo withAccount(String appleId) {
        return new CalendarInfo(url, name, color, writable, appleId);
    }

    CalendarInfo withName(String newName) {
        return new CalendarInfo(url, newName, color, writable, account);
    }

    @Override
    public String toString() {
        return name;
    }
}
