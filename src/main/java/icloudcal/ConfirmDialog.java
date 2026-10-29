package icloudcal;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 창 안에 띄우는 확인 팝업. 뒤를 어둡게 덮고 가운데 카드에서 고르게 한다.
 * 선택지의 작업이 실패하면 팝업에 오류를 보여 주고, 성공하면 다음 화면은 작업을 건 쪽이 띄운다.
 */
final class ConfirmDialog extends StackPane {
    /** danger 면 빨간 버튼으로 그린다. */
    record Choice(String label, boolean danger, Supplier<CompletableFuture<Void>> action) {
    }

    private static final double OPEN_MILLIS = 200;
    private static final double CLOSE_MILLIS = 120;

    private final Runnable onClosed;
    private boolean busy;

    ConfirmDialog(String title, String message, List<Choice> choices, Runnable onClosed) {
        this.onClosed = onClosed;
        getStyleClass().add("dialog-scrim");

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label body = new Label(message);
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        Label status = new Label();
        status.getStyleClass().add("error");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());

        Button cancel = new Button("취소");
        cancel.getStyleClass().add("plain-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> close());

        List<Button> buttons = new ArrayList<>();
        for (Choice choice : choices) {
            Button button = new Button(choice.label());
            button.getStyleClass().add(choice.danger() ? "danger-button" : "accent-button");
            button.setOnAction(e -> {
                busy = true;
                buttons.forEach(b -> b.setDisable(true));
                cancel.setDisable(true);
                status.setText("처리하는 중…");
                choice.action().get().whenCompleteAsync((ok, ex) -> {
                    if (ex != null) {
                        busy = false;
                        buttons.forEach(b -> b.setDisable(false));
                        cancel.setDisable(false);
                        status.setText("실패: " + App.message(ex));
                    }
                }, Platform::runLater);
            });
            buttons.add(button);
        }

        VBox card = new VBox(10, heading, body, status);
        card.getStyleClass().add("dialog");
        card.setMaxWidth(290);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        if (buttons.size() == 1) {
            // 선택지가 하나면 [취소] [확인] 을 오른쪽에 나란히
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(8, spacer, cancel, buttons.get(0));
            row.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().add(row);
        } else {
            // 여럿이면 버튼을 세로로 쌓고 취소를 맨 아래에
            VBox column = new VBox(6);
            column.getChildren().addAll(buttons);
            column.getChildren().add(cancel);
            column.getChildren().forEach(b -> ((Button) b).setMaxWidth(Double.MAX_VALUE));
            card.getChildren().add(column);
        }
        getChildren().add(card);

        // 카드 바깥(어두운 부분)을 누르면 닫는다
        setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                close();
            }
        });
        Motion.enter(this, 0, OPEN_MILLIS);
        Motion.enter(card, 12, OPEN_MILLIS);
        Platform.runLater(cancel::requestFocus);
    }

    private void close() {
        if (busy) {
            return;
        }
        busy = true;
        Motion.exit(this, 0, CLOSE_MILLIS).setOnFinished(e -> {
            if (getParent() instanceof StackPane parent) {
                parent.getChildren().remove(this);
            }
            onClosed.run();
        });
    }
}
