package icloudcal;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 트레이에 상주하다가 클릭하거나 Win + Alt + C 를 누르면 시계 위에 달력 창을 띄운다. */
public final class App extends Application {
    private static final double WIDTH = 360;
    private static final double HEIGHT = 620;
    private static final double MARGIN = 12;

    @FunctionalInterface
    private interface IoTask<T> {
        T run() throws IOException;
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "caldav");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<YearMonth, List<EventItem>> cache = new ConcurrentHashMap<>();

    private Stage popup;
    private StackPane root;
    private CalendarPane calendarPane;
    private TrayIcon trayIcon;
    private LocalDate trayDate;
    private CalDavClient client;
    private List<CalendarInfo> calendars = List.of();
    private long lastHiddenAt;

    @Override
    public void start(Stage owner) {
        Platform.setImplicitExit(false);

        // 작업 표시줄에 버튼이 생기지 않도록 보이지 않는 UTILITY 창을 주인으로 둔다
        owner.initStyle(StageStyle.UTILITY);
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(-32000);
        owner.setY(-32000);
        owner.show();

        popup = new Stage();
        popup.initOwner(owner);
        popup.initStyle(StageStyle.TRANSPARENT);
        popup.setAlwaysOnTop(true);
        popup.setTitle("iCloud 캘린더");

        root = new StackPane();
        root.getStyleClass().addAll("window", WindowsApi.appsDark() ? "dark" : "light");
        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.TRANSPARENT);
        scene.getStylesheets().add(App.class.getResource("style.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && root.getChildren().contains(calendarPane)) {
                hide();
            }
        });
        popup.setScene(scene);
        // 계정 입력·일정 추가 중에는 다른 창(브라우저 등)을 봐야 할 수 있으므로 달력 화면일 때만 자동으로 닫는다
        popup.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && root.getChildren().contains(calendarPane)) {
                hide();
            }
        });

        calendarPane = new CalendarPane(this::loadMonth, this::openAddForm);
        installTray();
        if (!WindowsApi.registerHotkey('C', () -> Platform.runLater(this::toggle))) {
            calendarPane.setStatus("Win + Alt + C 단축키를 다른 프로그램이 쓰고 있습니다.");
        }
        worker.scheduleAtFixedRate(() -> Platform.runLater(this::refresh), 10, 10, TimeUnit.MINUTES);

        CredentialStore.load().ifPresentOrElse(account -> {
            showPane(calendarPane);
            connect(account, false).whenCompleteAsync((ok, ex) -> {
                if (ex != null) {
                    connectFailed(ex);
                }
            }, Platform::runLater);
        }, () -> {
            showLogin(null);
            show();
        });
    }

    /** 사용자에게 보여줄 오류 문장 */
    static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CalDavClient.AuthException) {
            return "Apple ID 또는 앱 암호가 올바르지 않습니다.";
        }
        if (cause instanceof UnknownHostException || cause instanceof ConnectException
                || cause instanceof HttpTimeoutException) {
            return "iCloud 에 연결할 수 없습니다. 인터넷 연결을 확인하십시오.";
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // ---- 창 ----

    private void toggle() {
        if (popup.isShowing()) {
            hide();
        } else if (System.currentTimeMillis() - lastHiddenAt > 300) {
            // 트레이 아이콘을 누르는 순간 포커스를 잃고 닫힌 경우에는 다시 열지 않는다
            show();
        }
    }

    private void show() {
        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        popup.setX(area.getMaxX() - WIDTH - MARGIN);
        popup.setY(area.getMaxY() - HEIGHT - MARGIN);
        calendarPane.render();
        popup.show();
        popup.toFront();
        popup.requestFocus();
    }

    private void hide() {
        if (popup.isShowing()) {
            popup.hide();
            lastHiddenAt = System.currentTimeMillis();
        }
    }

    private void showPane(Node pane) {
        root.getChildren().setAll(pane);
    }

    private void showLogin(String message) {
        showPane(new LoginPane(message,
                (id, pw) -> connect(new CredentialStore.Account(id, pw), true),
                () -> getHostServices().showDocument("https://account.apple.com/"),
                this::exit));
    }

    // ---- iCloud ----

    private CompletableFuture<Void> connect(CredentialStore.Account account, boolean remember) {
        CalDavClient candidate = new CalDavClient(account.appleId(), account.password());
        return async(() -> {
            List<CalendarInfo> list = candidate.listCalendars();
            if (remember) {
                CredentialStore.save(account);
            }
            return list;
        }).thenAcceptAsync(list -> {
            client = candidate;
            calendars = list;
            cache.clear();
            calendarPane.setStatus("");
            showPane(calendarPane);
            loadMonth(calendarPane.month());
        }, Platform::runLater);
    }

    private void connectFailed(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CalDavClient.AuthException) {
            showLogin("저장된 앱 암호로 연결하지 못했습니다. 앱 암호를 다시 입력하십시오.");
        } else {
            calendarPane.setStatus(message(error) + " 10분 뒤 다시 시도합니다.");
        }
    }

    private void loadMonth(YearMonth month) {
        List<EventItem> cached = cache.get(month);
        if (cached != null) {
            calendarPane.setEvents(month, cached);
            return;
        }
        if (client == null) {
            return;
        }
        CalDavClient current = client;
        List<CalendarInfo> targets = calendars;
        ZonedDateTime from = CalendarPane.gridStart(month).atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime to = from.plusDays(42);
        calendarPane.setStatus("불러오는 중…");
        async(() -> {
            List<EventItem> all = new ArrayList<>();
            for (CalendarInfo calendar : targets) {
                all.addAll(current.fetchEvents(calendar, from, to));
            }
            all.sort(Comparator.comparing(EventItem::start));
            return all;
        }).whenCompleteAsync((list, ex) -> {
            if (ex != null) {
                calendarPane.setStatus("불러오기 실패: " + message(ex));
                return;
            }
            cache.put(month, list);
            calendarPane.setEvents(month, list);
            calendarPane.setStatus("");
        }, Platform::runLater);
    }

    private void openAddForm(LocalDate date) {
        List<CalendarInfo> writable = calendars.stream().filter(CalendarInfo::writable).toList();
        if (writable.isEmpty()) {
            calendarPane.setStatus("일정을 추가할 수 있는 캘린더가 없습니다.");
            return;
        }
        showPane(new AddEventPane(writable, date, this::saveEvent, () -> showPane(calendarPane)));
    }

    private CompletableFuture<Void> saveEvent(NewEvent event) {
        CalDavClient current = client;
        return async(() -> {
            current.putEvent(event);
            return null;
        }).thenRunAsync(() -> {
            cache.clear();
            showPane(calendarPane);
            calendarPane.select(event.start().toLocalDate());
            loadMonth(calendarPane.month());
        }, Platform::runLater);
    }

    private void refresh() {
        updateTrayIcon();
        if (client == null) {
            CredentialStore.load().ifPresent(account -> connect(account, false)
                    .whenCompleteAsync((ok, ex) -> {
                        if (ex != null) {
                            connectFailed(ex);
                        }
                    }, Platform::runLater));
            return;
        }
        cache.clear();
        calendarPane.render();
        loadMonth(calendarPane.month());
    }

    private void logout() {
        try {
            CredentialStore.clear();
        } catch (IOException e) {
            calendarPane.setStatus("저장된 계정을 지우지 못했습니다: " + e.getMessage());
        }
        client = null;
        calendars = List.of();
        cache.clear();
        showLogin(null);
        show();
    }

    private <T> CompletableFuture<T> async(IoTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, worker);
    }

    private void exit() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        Platform.exit();
        System.exit(0);
    }

    // ---- 트레이 ----

    private void installTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        trayDate = LocalDate.now();
        boolean darkTaskbar = WindowsApi.taskbarDark();
        EventQueue.invokeLater(() -> {
            PopupMenu menu = new PopupMenu();
            menu.setFont(new Font("Malgun Gothic", Font.PLAIN, 12));
            menu.add(menuItem("열기", () -> Platform.runLater(this::show)));
            menu.add(menuItem("새로 고침", () -> Platform.runLater(this::refresh)));
            menu.add(menuItem("계정 변경", () -> Platform.runLater(this::logout)));
            menu.addSeparator();
            menu.add(menuItem("종료", () -> Platform.runLater(this::exit)));

            trayIcon = new TrayIcon(trayImage(trayDate, darkTaskbar), "iCloud 캘린더 (Win + Alt + C)", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        Platform.runLater(App.this::toggle);
                    }
                }
            });
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (AWTException e) {
                Platform.runLater(() -> calendarPane.setStatus("트레이 아이콘을 만들지 못했습니다."));
            }
        });
    }

    /** 날짜가 바뀌면 트레이 아이콘의 숫자를 바꾼다. */
    private void updateTrayIcon() {
        LocalDate now = LocalDate.now();
        if (trayIcon == null || now.equals(trayDate)) {
            return;
        }
        trayDate = now;
        boolean darkTaskbar = WindowsApi.taskbarDark();
        EventQueue.invokeLater(() -> trayIcon.setImage(trayImage(now, darkTaskbar)));
    }

    private static MenuItem menuItem(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** 달력 모양 테두리 안에 오늘 날짜 숫자를 그린 아이콘 */
    private static BufferedImage trayImage(LocalDate date, boolean darkTaskbar) {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(darkTaskbar ? java.awt.Color.WHITE : new java.awt.Color(0x1a1a1a));
        g.setStroke(new BasicStroke(2.2f));
        g.drawRoundRect(3, 5, 26, 24, 7, 7);
        g.fillRect(3, 5, 26, 6);
        g.fillRect(9, 2, 3, 6);
        g.fillRect(20, 2, 3, 6);
        String day = String.valueOf(date.getDayOfMonth());
        g.setFont(new Font("Segoe UI", Font.BOLD, 15));
        int width = g.getFontMetrics().stringWidth(day);
        g.drawString(day, (size - width) / 2f, 26);
        g.dispose();
        return image;
    }
}
