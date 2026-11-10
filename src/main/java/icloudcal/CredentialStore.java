package icloudcal;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Apple ID 와 앱 암호들을 Windows DPAPI 로 암호화해 %APPDATA%\iCloudCalendar\account.dat 에 저장한다.
 * DPAPI 로 암호화한 값은 같은 Windows 사용자 계정에서만 복호화할 수 있다.
 * 내용은 한 줄에 "AppleID\t앱암호" 하나씩이다. 예전(계정 하나) 형식인 "AppleID\n앱암호" 도 읽는다.
 */
final class CredentialStore {
    record Account(String appleId, String password) {
    }

    private static final Path FILE = Path.of(System.getenv("APPDATA"), "iCloudCalendar", "account.dat");

    private CredentialStore() {
    }

    static List<Account> loadAll() {
        try {
            if (!Files.exists(FILE)) {
                return List.of();
            }
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(FILE));
            return decode(new String(plain, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** 계정이 하나도 없으면 파일을 지운다. */
    static void saveAll(List<Account> accounts) throws IOException {
        if (accounts.isEmpty()) {
            Files.deleteIfExists(FILE);
            return;
        }
        Files.createDirectories(FILE.getParent());
        Files.write(FILE, Crypt32Util.cryptProtectData(encode(accounts).getBytes(StandardCharsets.UTF_8)));
    }

    static String encode(List<Account> accounts) {
        StringBuilder text = new StringBuilder();
        for (Account account : accounts) {
            text.append(account.appleId()).append('\t').append(account.password()).append('\n');
        }
        return text.toString();
    }

    static List<Account> decode(String text) {
        String[] lines = text.split("\n");
        if (!text.contains("\t")) {
            // 예전 형식: 첫 줄 Apple ID, 둘째 줄 앱 암호
            return lines.length == 2 ? List.of(new Account(lines[0], lines[1])) : List.of();
        }
        List<Account> accounts = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                accounts.add(new Account(parts[0], parts[1]));
            }
        }
        return accounts;
    }
}
