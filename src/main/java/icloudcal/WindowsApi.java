package icloudcal;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 테마 확인, 전역 단축키 등록, 한글 입력 조합 창 처리에 쓰는 Windows API */
final class WindowsApi {
    private static final String THEME_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final int MOD_ALT = 0x0001;
    private static final int MOD_WIN = 0x0008;
    private static final int MOD_NOREPEAT = 0x4000;
    private static final int WM_HOTKEY = 0x0312;

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_IME_STARTCOMPOSITION = 0x010D;
    private static final int WM_IME_COMPOSITION = 0x010F;
    private static final int WM_IME_SETCONTEXT = 0x0281;
    private static final long ISC_SHOWUICOMPOSITIONWINDOW = 0x80000000L;
    private static final long ISC_SHOWUIALL = 0xC000000FL;
    private static final int CFS_POINT = 0x0002;

    private interface Imm32 extends StdCallLibrary {
        Imm32 INSTANCE = Native.load("imm32", Imm32.class);

        Pointer ImmGetContext(WinDef.HWND hwnd);

        /** form 은 COMPOSITIONFORM { dwStyle, POINT, RECT } 을 int 7개로 펼친 것 */
        boolean ImmSetCompositionWindow(Pointer himc, int[] form);

        boolean ImmReleaseContext(WinDef.HWND hwnd, Pointer himc);
    }

    /** 가로챈 창 → 원래 창 프로시저 */
    private static final Map<Long, Pointer> ORIGINAL_PROCS = new HashMap<>();
    private static Supplier<int[]> caretSupplier;
    /** JNA 콜백이 GC 되지 않도록 붙잡아 둔다 */
    private static final WinUser.WindowProc IME_PROC = WindowsApi::imeProc;

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

    /**
     * JavaFX 는 조합 중인 글자를 입력란 안에 직접 그리면서도 IME 에 조합 창을 숨기라고 알리지 않고 위치도 주지 않는다.
     * 그래서 IME(특히 Windows 11 의 Microsoft 한글 IME)가 화면 왼쪽 위에 조합 창을 따로 띄울 수 있다.
     * 이 스레드에서 만든 title 창의 메시지를 가로채 조합 창을 끄고, 그래도 뜨면 caret 위치(창 안 물리 픽셀)로 옮긴다.
     * JavaFX 는 창을 숨길 때 네이티브 창을 없애므로 창을 띄울 때마다 호출해야 한다. JavaFX 스레드에서 호출한다.
     */
    static void fixImeComposition(String title, Supplier<int[]> caret) {
        caretSupplier = caret;
        WinDef.HWND[] found = new WinDef.HWND[1];
        User32.INSTANCE.EnumThreadWindows(Kernel32.INSTANCE.GetCurrentThreadId(), (hwnd, data) -> {
            char[] buffer = new char[256];
            int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
            if (title.equals(new String(buffer, 0, length))) {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);
        WinDef.HWND hwnd = found[0];
        if (hwnd == null || ORIGINAL_PROCS.containsKey(Pointer.nativeValue(hwnd.getPointer()))) {
            return;
        }
        Pointer original = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC,
                CallbackReference.getFunctionPointer(IME_PROC));
        ORIGINAL_PROCS.put(Pointer.nativeValue(hwnd.getPointer()), original);
        // 창이 이미 활성화되어 처음 받은 WM_IME_SETCONTEXT 를 놓쳤으므로 IME 에 다시 알린다
        if (hwnd.equals(User32.INSTANCE.GetForegroundWindow())) {
            User32.INSTANCE.SendMessage(hwnd, WM_IME_SETCONTEXT, new WinDef.WPARAM(1), new WinDef.LPARAM(ISC_SHOWUIALL));
        }
    }

    private static WinDef.LRESULT imeProc(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
        long key = Pointer.nativeValue(hwnd.getPointer());
        Pointer original = ORIGINAL_PROCS.get(key);
        switch (msg) {
            case WM_IME_SETCONTEXT -> lParam = new WinDef.LPARAM(lParam.longValue() & ~ISC_SHOWUICOMPOSITIONWINDOW);
            case WM_IME_STARTCOMPOSITION, WM_IME_COMPOSITION -> moveComposition(hwnd);
            case WM_NCDESTROY -> ORIGINAL_PROCS.remove(key);
            default -> {
            }
        }
        return User32.INSTANCE.CallWindowProc(original, hwnd, msg, wParam, lParam);
    }

    private static void moveComposition(WinDef.HWND hwnd) {
        int[] caret = caretSupplier == null ? null : caretSupplier.get();
        if (caret == null) {
            return;
        }
        Pointer himc = Imm32.INSTANCE.ImmGetContext(hwnd);
        if (himc != null) {
            Imm32.INSTANCE.ImmSetCompositionWindow(himc, new int[]{CFS_POINT, caret[0], caret[1], 0, 0, 0, 0});
            Imm32.INSTANCE.ImmReleaseContext(hwnd, himc);
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
