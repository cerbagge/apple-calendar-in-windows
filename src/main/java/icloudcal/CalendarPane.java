package icloudcal;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 오늘 날짜, 월 달력, 선택한 날의 일정 목록을 보여준다. */
final class CalendarPane extends VBox {
    private static final Locale KO = Locale.KOREAN;
    private static final DateTimeFormatter HEADER = DateTimeFormatter.ofPattern("M월 d일 EEEE", KO);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy년 M월", KO);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일 EEEE", KO);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", KO);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M/d a h:mm", KO);
    private static final String[] WEEKDAYS = {"일", "월", "화", "수", "목", "금", "토"};
    private static final String DEFAULT_COLOR = "#4cc2ff";

    private final Consumer<YearMonth> onMonthChange;
    private final Label today = new Label();
    private final Label monthLabel = new Label();
    private final GridPane grid = new GridPane();
    private final Label dayTitle = new Label();
    private final VBox eventList = new VBox(2);
    private final Label status = new Label();

    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private List<EventItem> events = List.of();

    CalendarPane(Consumer<YearMonth> onMonthChange, Consumer<LocalDate> onAdd) {
        this.onMonthChange = onMonthChange;
        getStyleClass().add("calendar-pane");

        today.getStyleClass().add("today-header");
        today.setMaxWidth(Double.MAX_VALUE);
        today.setOnMouseClicked(e -> select(LocalDate.now()));

        monthLabel.getStyleClass().add("month-label");
        HBox nav = new HBox(2, monthLabel, spacer(), navButton("▲", -1), navButton("▼", 1));
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("month-nav");

        for (int i = 0; i < 7; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 7);
            column.setHalignment(HPos.CENTER);
            grid.getColumnConstraints().add(column);
        }
        grid.getStyleClass().add("month-grid");

        dayTitle.getStyleClass().add("day-title");
        Button add = new Button("+ 일정 추가");
        add.getStyleClass().add("accent-button");
        add.setOnAction(e -> onAdd.accept(selected));
        HBox dayBar = new HBox(dayTitle, spacer(), add);
        dayBar.setAlignment(Pos.CENTER_LEFT);
        dayBar.getStyleClass().add("day-bar");

        ScrollPane scroll = new ScrollPane(eventList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("event-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        status.getStyleClass().add("status");
        status.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(today, nav, grid, dayBar, scroll, status);
        render();
    }

    /** 6주 달력의 첫 칸(일요일) 날짜 */
    static LocalDate gridStart(YearMonth month) {
        LocalDate first = month.atDay(1);
        return first.minusDays(first.getDayOfWeek().getValue() % 7);
    }

    YearMonth month() {
        return month;
    }

    void setEvents(YearMonth forMonth, List<EventItem> list) {
        if (forMonth.equals(month)) {
            events = list;
            render();
        }
    }

    void setStatus(String text) {
        status.setText(text);
    }

    void select(LocalDate day) {
        selected = day;
        YearMonth target = YearMonth.from(day);
        if (target.equals(month)) {
            render();
        } else {
            changeMonth(target);
        }
    }

    /** 날짜가 바뀌었을 수 있으므로 창을 열 때마다 다시 그린다. */
    void render() {
        today.setText(LocalDate.now().format(HEADER));
        monthLabel.setText(month.format(MONTH));
        renderGrid();
        renderEvents();
    }

    private void changeMonth(YearMonth target) {
        month = target;
        events = List.of();
        render();
        onMonthChange.accept(target);
    }

    private void renderGrid() {
        grid.getChildren().clear();
        for (int i = 0; i < 7; i++) {
            Label weekday = new Label(WEEKDAYS[i]);
            weekday.getStyleClass().add("weekday");
            grid.add(weekday, i, 0);
        }
        LocalDate start = gridStart(month);
        LocalDate now = LocalDate.now();
        for (int i = 0; i < 42; i++) {
            LocalDate day = start.plusDays(i);
            Label number = new Label(String.valueOf(day.getDayOfMonth()));
            Circle dot = new Circle(2.5);
            dot.getStyleClass().add("dot");
            dot.setVisible(events.stream().anyMatch(e -> e.occursOn(day)));

            VBox cell = new VBox(1, number, dot);
            cell.setAlignment(Pos.CENTER);
            cell.setMinSize(40, 40);
            cell.setPrefSize(40, 40);
            cell.setMaxSize(40, 40);
            cell.getStyleClass().add("day");
            if (!YearMonth.from(day).equals(month)) {
                cell.getStyleClass().add("other-month");
            }
            if (day.equals(now)) {
                cell.getStyleClass().add("today");
            }
            if (day.equals(selected)) {
                cell.getStyleClass().add("selected");
            }
            cell.setOnMouseClicked(e -> select(day));
            grid.add(cell, i % 7, 1 + i / 7);
        }
    }

    private void renderEvents() {
        dayTitle.setText(selected.format(DAY));
        eventList.getChildren().clear();
        List<EventItem> today = events.stream()
                .filter(e -> e.occursOn(selected))
                .sorted(Comparator.comparing((EventItem e) -> !e.allDay()).thenComparing(EventItem::start))
                .toList();
        if (today.isEmpty()) {
            Label empty = new Label("일정 없음");
            empty.getStyleClass().add("empty");
            eventList.getChildren().add(empty);
            return;
        }
        for (EventItem event : today) {
            eventList.getChildren().add(eventRow(event));
        }
    }

    private HBox eventRow(EventItem event) {
        Region bar = new Region();
        bar.getStyleClass().add("event-bar");
        bar.setMinWidth(4);
        bar.setPrefWidth(4);
        String color = event.calendar().color() == null ? DEFAULT_COLOR : event.calendar().color();
        bar.setStyle("-fx-background-color: " + color + ";");

        Label title = new Label(event.title());
        title.getStyleClass().add("event-title");
        Label time = new Label(timeText(event) + "  ·  " + event.calendar().name());
        time.getStyleClass().add("event-time");
        VBox text = new VBox(2, title, time);
        if (event.location() != null && !event.location().isBlank()) {
            Label location = new Label(event.location());
            location.getStyleClass().add("event-time");
            text.getChildren().add(location);
        }

        HBox row = new HBox(10, bar, text);
        row.getStyleClass().add("event-row");
        return row;
    }

    private static String timeText(EventItem event) {
        if (event.allDay()) {
            return "하루 종일";
        }
        if (event.start().toLocalDate().equals(event.end().toLocalDate())) {
            return event.start().format(TIME) + " - " + event.end().format(TIME);
        }
        return event.start().format(DATE_TIME) + " - " + event.end().format(DATE_TIME);
    }

    private Button navButton(String text, int delta) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        button.setOnAction(e -> changeMonth(month.plusMonths(delta)));
        return button;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
