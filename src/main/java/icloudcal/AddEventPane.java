package icloudcal;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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

/** 새 일정 입력 화면 */
final class AddEventPane extends VBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
    /** -1 은 알림 없음 */
    private static final List<Integer> ALERTS = List.of(-1, 0, 5, 10, 15, 30, 60, 120, 1440);

    private final Label error = new Label();

    AddEventPane(List<CalendarInfo> calendars, LocalDate date,
                 Function<NewEvent, CompletableFuture<Void>> onSave, Runnable onClose) {
        getStyleClass().add("form-pane");

        Label heading = new Label("새 일정");
        heading.getStyleClass().add("form-title");

        TextField title = new TextField();
        title.setPromptText("제목");
        TextField location = new TextField();
        location.setPromptText("위치 (선택)");

        ComboBox<CalendarInfo> calendar = new ComboBox<>(FXCollections.observableArrayList(calendars));
        calendar.getSelectionModel().selectFirst();
        calendar.setMaxWidth(Double.MAX_VALUE);

        CheckBox allDay = new CheckBox("하루 종일");
        DatePicker startDate = datePicker(date);
        DatePicker endDate = datePicker(date);
        LocalTime base = LocalTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        ComboBox<LocalTime> startTime = timeBox(base);
        ComboBox<LocalTime> endTime = timeBox(base.plusHours(1));
        startTime.disableProperty().bind(allDay.selectedProperty());
        endTime.disableProperty().bind(allDay.selectedProperty());

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

        ComboBox<Integer> alert = new ComboBox<>(FXCollections.observableArrayList(ALERTS));
        alert.setConverter(converter(AddEventPane::alertText));
        alert.setValue(-1);
        alert.setMaxWidth(Double.MAX_VALUE);

        GridPane form = new GridPane();
        form.getStyleClass().add("form-grid");
        form.addRow(0, fieldLabel("캘린더"), calendar);
        form.addRow(1, fieldLabel("시작"), new HBox(6, startDate, startTime));
        form.addRow(2, fieldLabel("종료"), new HBox(6, endDate, endTime));
        form.addRow(3, new Region(), allDay);
        form.addRow(4, fieldLabel("알림"), alert);

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
            onSave.apply(new NewEvent(calendar.getValue(), text, location.getText().strip(), whole, start, end, minutes))
                    .whenCompleteAsync((ok, ex) -> {
                        if (ex != null) {
                            save.setDisable(false);
                            error.setText("저장 실패: " + App.message(ex));
                        }
                    }, Platform::runLater);
        });

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        getChildren().addAll(heading, title, location, form, error, grow, buttons);
        Platform.runLater(title::requestFocus);
    }

    private static DatePicker datePicker(LocalDate date) {
        DatePicker picker = new DatePicker(date);
        picker.setEditable(false);
        picker.setPrefWidth(150);
        return picker;
    }

    private static ComboBox<LocalTime> timeBox(LocalTime initial) {
        List<LocalTime> times = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            times.add(LocalTime.MIDNIGHT.plusMinutes(30L * i));
        }
        ComboBox<LocalTime> box = new ComboBox<>(FXCollections.observableArrayList(times));
        box.setConverter(converter(TIME::format));
        box.setValue(initial);
        box.setVisibleRowCount(8);
        box.setPrefWidth(118);
        return box;
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

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.getStyleClass().add("field-label");
        return label;
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
