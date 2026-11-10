package icloudcal;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 일정 입력 화면. editing 이 null 이면 새 일정을, 아니면 그 일정을 고친다.
 * onDelete 는 수정할 때만 쓰며, 인자는 반복 일정 전체를 지울지 여부이다.
 * 삭제 확인 팝업을 위에 겹쳐 띄우려고 StackPane 에 입력 화면(form)을 담는다.
 */
final class EventFormPane extends StackPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN);
    private static final DateTimeFormatter DATE_WITH_YEAR = DateTimeFormatter.ofPattern("yy년 M월 d일 (E)", Locale.KOREAN);
    /** -1 은 알림 없음 */
    private static final List<Integer> ALERTS = List.of(-1, 0, 5, 10, 15, 30, 60, 120, 1440);

    private final Label error = new Label();

    EventFormPane(List<CalendarInfo> calendars, LocalDate date, EventItem editing,
                  Function<NewEvent, CompletableFuture<Void>> onSave,
                  Function<Boolean, CompletableFuture<Void>> onDelete, Runnable onClose) {
        VBox form = new VBox();
        form.getStyleClass().add("event-form");

        // 처음 채울 값: 새 일정이면 다음 정각부터 한 시간, 수정이면 원래 값
        LocalDate startDay = date;
        LocalDate endDay = date;
        LocalTime startAt = LocalTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        LocalTime endAt = startAt.plusHours(1);
        boolean wholeDay = false;
        int alertValue = -1;
        if (editing != null) {
            wholeDay = editing.allDay();
            startDay = editing.start().toLocalDate();
            // 하루 종일 일정의 end 는 마지막 날 다음 날 0시이다
            endDay = wholeDay ? editing.end().toLocalDate().minusDays(1) : editing.end().toLocalDate();
            if (endDay.isBefore(startDay)) {
                endDay = startDay;
            }
            if (!wholeDay) {
                startAt = editing.start().toLocalTime();
                endAt = editing.end().toLocalTime();
            }
            alertValue = editing.alertMinutes() == null ? -1 : editing.alertMinutes();
        }

        Label heading = new Label(editing == null ? "새 일정" : "일정 수정");
        heading.getStyleClass().add("form-header");
        heading.setMaxWidth(Double.MAX_VALUE);

        TextField title = new TextField(editing == null ? "" : editing.title());
        title.setPromptText("제목");
        title.getStyleClass().add("title-field");
        TextField location = new TextField(editing == null || editing.location() == null ? "" : editing.location());
        location.setPromptText("위치");
        TextArea notes = new TextArea(editing == null || editing.notes() == null ? "" : editing.notes());
        notes.setPromptText("메모");
        notes.setWrapText(true);
        notes.setPrefRowCount(4);

        ComboBox<CalendarInfo> calendar = new ComboBox<>(FXCollections.observableArrayList(calendars));
        // 계정이 둘 이상이면 펼친 목록에서 캘린더마다 어느 계정인지도 보여 준다
        boolean manyAccounts = calendars.stream().map(CalendarInfo::account).distinct().count() > 1;
        calendar.setCellFactory(list -> calendarCell(manyAccounts));
        calendar.setButtonCell(calendarCell(false));
        calendar.setPrefWidth(180);
        if (editing == null) {
            calendar.getSelectionModel().selectFirst();
        } else {
            // 다른 캘린더로 옮기는 기능은 아직 없으므로 원래 캘린더로 고정한다
            CalendarInfo original = calendars.stream()
                    .filter(c -> c.url().equals(editing.calendar().url()))
                    .findFirst().orElse(editing.calendar());
            calendar.setValue(original);
            calendar.setDisable(true);
        }

        CheckBox allDay = new CheckBox();
        allDay.getStyleClass().add("switch");
        allDay.setSelected(wholeDay);
        DatePicker startDate = datePicker(startDay);
        DatePicker endDate = datePicker(endDay);
        ComboBox<LocalTime> startTime = timeBox(startAt);
        ComboBox<LocalTime> endTime = timeBox(endAt);
        for (ComboBox<LocalTime> box : List.of(startTime, endTime)) {
            box.visibleProperty().bind(allDay.selectedProperty().not());
            box.managedProperty().bind(box.visibleProperty());
        }

        // 시작을 바꾸면 종료가 시작보다 앞서지 않도록 따라 옮긴다
        startDate.valueProperty().addListener((obs, old, value) -> {
            if (value != null && endDate.getValue().isBefore(value)) {
                endDate.setValue(value);
            }
        });
        startTime.valueProperty().addListener((obs, old, value) -> {
            if (value != null && startDate.getValue().equals(endDate.getValue())
                    && !endTime.getValue().isAfter(value)) {
                endTime.setValue(value.plusHours(1));
            }
        });

        List<Integer> alerts = new ArrayList<>(ALERTS);
        if (!alerts.contains(alertValue)) {
            alerts.add(alertValue);
            alerts.sort(null);
        }
        ComboBox<Integer> alert = new ComboBox<>(FXCollections.observableArrayList(alerts));
        alert.setConverter(converter(EventFormPane::alertText));
        alert.setValue(alertValue);
        alert.setPrefWidth(150);

        VBox body = new VBox(12,
                card(title, location),
                card(row("캘린더", calendar), row("하루 종일", allDay),
                        row("시작", startDate, startTime), row("종료", endDate, endTime)));
        if (editing != null && editing.source().series()) {
            for (Node control : List.of(allDay, startDate, endDate, startTime, endTime)) {
                control.setDisable(true);
            }
            Label hint = new Label("반복 일정입니다. 바꾼 내용은 모든 반복에 적용되며, 날짜와 시간은 여기서 바꿀 수 없습니다.");
            hint.getStyleClass().add("hint");
            hint.setWrapText(true);
            body.getChildren().add(hint);
        }
        body.getChildren().addAll(card(row("알림", alert)), card(notes), error);
        body.getStyleClass().add("form-body");

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("event-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        error.getStyleClass().add("error");
        error.setWrapText(true);

        Button cancel = new Button("취소");
        cancel.getStyleClass().add("plain-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onClose.run());
        Button save = new Button("저장");
        save.getStyleClass().add("accent-button");
        save.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getStyleClass().add("form-footer");
        if (editing != null && onDelete != null) {
            Button delete = new Button("삭제");
            delete.getStyleClass().add("danger-text-button");
            delete.setOnAction(e -> {
                // 팝업이 떠 있는 동안 Enter·Esc 가 뒤의 저장·취소를 누르지 않게 한다
                save.setDefaultButton(false);
                cancel.setCancelButton(false);
                getChildren().add(confirmDelete(editing, onDelete, () -> {
                    save.setDefaultButton(true);
                    cancel.setCancelButton(true);
                }));
            });
            buttons.getChildren().add(0, delete);
        }

        save.setOnAction(e -> {
            String text = title.getText().strip();
            if (text.isEmpty()) {
                error.setText("제목을 입력하십시오.");
                return;
            }
            boolean whole = allDay.isSelected();
            LocalDateTime start = whole ? startDate.getValue().atStartOfDay()
                    : LocalDateTime.of(startDate.getValue(), startTime.getValue());
            LocalDateTime end = whole ? endDate.getValue().atStartOfDay()
                    : LocalDateTime.of(endDate.getValue(), endTime.getValue());
            if (whole ? end.isBefore(start) : !end.isAfter(start)) {
                error.setText("종료가 시작보다 늦어야 합니다.");
                return;
            }
            Integer minutes = alert.getValue() < 0 ? null : alert.getValue();
            save.setDisable(true);
            error.setText("저장하는 중…");
            onSave.apply(new NewEvent(calendar.getValue(), text, location.getText().strip(), notes.getText().strip(),
                            whole, start, end, minutes))
                    .whenCompleteAsync((ok, ex) -> {
                        if (ex != null) {
                            save.setDisable(false);
                            error.setText("저장 실패: " + App.message(ex));
                        }
                    }, Platform::runLater);
        });

        form.getChildren().addAll(heading, scroll, buttons);
        getChildren().add(form);
        Platform.runLater(() -> {
            title.requestFocus();
            title.end();
        });
    }

    private static ConfirmDialog confirmDelete(EventItem event, Function<Boolean, CompletableFuture<Void>> onDelete,
                                               Runnable onClosed) {
        boolean repeating = event.source().series() || event.source().recurrenceId() != null;
        if (!repeating) {
            return new ConfirmDialog("일정 삭제",
                    "'" + event.title() + "' 일정을 삭제할까요?\n삭제한 일정은 되돌릴 수 없습니다.",
                    List.of(new ConfirmDialog.Choice("삭제", true, () -> onDelete.apply(true))), onClosed);
        }
        return new ConfirmDialog("반복 일정 삭제",
                "'" + event.title() + "' 은(는) 반복 일정입니다.\n"
                        + "이 날짜의 일정만 지우거나, 모든 반복을 지울 수 있습니다. 삭제한 일정은 되돌릴 수 없습니다.",
                List.of(new ConfirmDialog.Choice("이 일정만 삭제", true, () -> onDelete.apply(false)),
                        new ConfirmDialog.Choice("모든 반복 일정 삭제", true, () -> onDelete.apply(true))),
                onClosed);
    }

    /** 둥근 상자에 칸들을 구분선으로 나누어 담는다. */
    private static VBox card(Node... rows) {
        VBox card = new VBox();
        card.getStyleClass().add("form-card");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                Region divider = new Region();
                divider.getStyleClass().add("card-divider");
                card.getChildren().add(divider);
            }
            card.getChildren().add(rows[i]);
        }
        return card;
    }

    /** 왼쪽에 이름, 오른쪽에 입력 컨트롤을 둔 한 줄 */
    private static HBox row(String name, Node... controls) {
        Label label = new Label(name);
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.getStyleClass().add("field-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(6, label, spacer);
        row.getChildren().addAll(controls);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("card-row");
        return row;
    }

    private static DatePicker datePicker(LocalDate date) {
        DatePicker picker = new DatePicker(date);
        picker.setEditable(false);
        picker.setPrefWidth(132);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate value) {
                if (value == null) {
                    return "";
                }
                return value.format(value.getYear() == LocalDate.now().getYear() ? DATE : DATE_WITH_YEAR);
            }

            @Override
            public LocalDate fromString(String text) {
                return picker.getValue();
            }
        });
        return picker;
    }

    private static ComboBox<LocalTime> timeBox(LocalTime initial) {
        List<LocalTime> times = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            times.add(LocalTime.MIDNIGHT.plusMinutes(30L * i));
        }
        // 기존 일정이 30분 단위가 아니면 그 시각도 고를 수 있게 넣는다
        if (!times.contains(initial)) {
            times.add(initial);
            times.sort(null);
        }
        ComboBox<LocalTime> box = new ComboBox<>(FXCollections.observableArrayList(times));
        box.setConverter(converter(TIME::format));
        box.setValue(initial);
        box.setVisibleRowCount(8);
        box.getStyleClass().add("time-box");
        box.setPrefWidth(100);
        return box;
    }

    private static ListCell<CalendarInfo> calendarCell(boolean showAccount) {
        return new ListCell<>() {
            @Override
            protected void updateItem(CalendarInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Circle dot = new Circle(4, Color.web(item.color() == null ? CalendarPane.DEFAULT_COLOR : item.color()));
                if (showAccount && item.account() != null) {
                    Label name = new Label(item.name());
                    name.textFillProperty().bind(textFillProperty());
                    Label account = new Label(item.account());
                    account.getStyleClass().add("calendar-account");
                    HBox graphic = new HBox(8, dot, new VBox(name, account));
                    graphic.setAlignment(Pos.CENTER_LEFT);
                    setText(null);
                    setGraphic(graphic);
                    return;
                }
                setText(item.name());
                setGraphic(dot);
                setGraphicTextGap(8);
            }
        };
    }

    private static String alertText(int minutes) {
        if (minutes < 0) {
            return "알림 없음";
        }
        if (minutes == 0) {
            return "일정 시작 시";
        }
        if (minutes % 1440 == 0) {
            return minutes / 1440 + "일 전";
        }
        if (minutes % 60 == 0) {
            return minutes / 60 + "시간 전";
        }
        return minutes + "분 전";
    }

    private static <T> StringConverter<T> converter(Function<T, String> format) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : format.apply(value);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
