package icloudcal;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 연결된 iCloud 계정 목록. 계정을 더하거나 이 PC 에서 지울 수 있다. */
final class AccountsPane extends StackPane {
    /** ok 가 false 면 status 에 연결 실패 이유가 들어 있다. */
    record Row(String appleId, String status, boolean ok) {
    }

    private static final String ICON_FONT = Font.getFamilies().contains("Segoe Fluent Icons")
            ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

    AccountsPane(List<Row> rows, Runnable onAdd, Function<String, CompletableFuture<Void>> onRemove,
                 Runnable onClose) {
        Label heading = new Label("계정");
        heading.getStyleClass().add("form-header");
        heading.setMaxWidth(Double.MAX_VALUE);

        VBox card = new VBox();
        card.getStyleClass().add("form-card");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                Region divider = new Region();
                divider.getStyleClass().add("card-divider");
                card.getChildren().add(divider);
            }
            card.getChildren().add(accountRow(rows.get(i), onRemove));
        }

        Button add = new Button("+ 계정 추가");
        add.getStyleClass().add("plain-button");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> onAdd.run());

        Label hint = new Label("여러 계정의 캘린더를 한 달력에 함께 보여 줍니다. "
                + "계정을 지워도 iCloud 의 일정은 지워지지 않고, 이 PC 에 저장한 앱 암호만 지웁니다.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        VBox body = new VBox(12, card, add, hint);
        body.getStyleClass().add("form-body");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("event-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button close = new Button("닫기");
        close.getStyleClass().add("accent-button");
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> onClose.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("form-footer");

        getChildren().add(new VBox(heading, scroll, footer));
    }

    private HBox accountRow(Row row, Function<String, CompletableFuture<Void>> onRemove) {
        Label icon = new Label(""); // 사람 모양
        icon.setStyle("-fx-font-family: '" + ICON_FONT + "';");
        icon.getStyleClass().add("account-icon");

        Label id = new Label(row.appleId());
        id.getStyleClass().add("event-title");
        Label status = new Label(row.status());
        status.getStyleClass().add(row.ok() ? "event-time" : "error");
        status.setWrapText(true);
        VBox text = new VBox(2, id, status);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button remove = new Button("삭제");
        remove.setMinWidth(Region.USE_PREF_SIZE);
        remove.getStyleClass().add("danger-text-button");
        remove.setOnAction(e -> getChildren().add(new ConfirmDialog("계정 삭제",
                "'" + row.appleId() + "' 계정을 이 PC 에서 지울까요?\n"
                        + "iCloud 의 일정은 지워지지 않으며, 나중에 앱 암호로 다시 추가할 수 있습니다.",
                List.of(new ConfirmDialog.Choice("삭제", true, () -> onRemove.apply(row.appleId()))),
                () -> { })));

        HBox line = new HBox(12, icon, text, remove);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("card-row");
        return line;
    }
}
