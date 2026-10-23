package icloudcal;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Apple ID 와 앱 암호를 Windows DPAPI 로 암호화해 %APPDATA%\iCloudCalendar\account.dat 에 저장한다.
 * DPAPI 로 암호화한 값은 같은 Windows 사용자 계정에서만 복호화할 수 있다.
 */
final class CredentialStore {
    record Account(String appleId, String password) {
    }

    private static final Path FILE = Path.of(System.getenv("APPDATA"), "iCloudCalendar", "account.dat");

    private CredentialStore() {
    }

    static Optional<Account> load() {
        try {
            if (!Files.exists(FILE)) {
                return Optional.empty();
            }
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(FILE));
            String[] parts = new String(plain, StandardCharsets.UTF_8).split("\n", 2);
            return parts.length == 2 ? Optional.of(new Account(parts[0], parts[1])) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static void save(Account account) throws IOException {
        byte[] plain = (account.appleId() + "\n" + account.password()).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(FILE.getParent());
        Files.write(FILE, Crypt32Util.cryptProtectData(plain));
    }

    static void clear() throws IOException {
        Files.deleteIfExists(FILE);
    }
}
