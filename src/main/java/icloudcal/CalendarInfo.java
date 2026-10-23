package icloudcal;

import java.net.URI;

/** iCloud 캘린더 하나. color 는 "#RRGGBB" 형식이다. */
record CalendarInfo(URI url, String name, String color, boolean writable) {
    @Override
    public String toString() {
        return name;
    }
}
