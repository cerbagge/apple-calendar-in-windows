package icloudcal;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;

/** 테마 확인과 전역 단축키 등록에 쓰는 Windows API */
final class WindowsApi {
    private static final String THEME_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final int MOD_ALT = 0x0001;
    private static final int MOD_WIN = 0x0008;
    private static final int MOD_NOREPEAT = 0x4000;
    private static final int WM_HOTKEY = 0x0312;

    private WindowsApi() {
    }

    /** 앱 창이 다크 모드인지 */
    static boolean appsDark() {
        return themeValue("AppsUseLightTheme") == 0;
    }

    /** 작업 표시줄이 다크 모드인지 (트레이 아이콘 색을 정할 때 쓴다) */
    static boolean taskbarDark() {
        return themeValue("SystemUsesLightTheme") == 0;
    }

    private static int themeValue(String name) {
        try {
            return Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, THEME_KEY, name);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Win + Alt + key 를 누를 때마다 action 을 실행한다. 다른 프로그램이 이미 쓰고 있으면 false. */
    static boolean registerHotkey(char key, Runnable action) {
        boolean[] registered = new boolean[1];
        Object lock = new Object();
        Thread thread = new Thread(() -> {
            // 단축키 메시지는 등록한 스레드의 메시지 큐로 오므로 같은 스레드에서 등록과 수신을 한다
            boolean ok = User32.INSTANCE.RegisterHotKey(null, 1, MOD_WIN | MOD_ALT | MOD_NOREPEAT, key);
            synchronized (lock) {
                registered[0] = ok;
                lock.notifyAll();
            }
            if (!ok) {
                return;
            }
            WinUser.MSG msg = new WinUser.MSG();
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) {
                    action.run();
                }
            }
        }, "hotkey");
        thread.setDaemon(true);
        synchronized (lock) {
            thread.start();
            try {
                lock.wait(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return registered[0];
        }
    }
}
