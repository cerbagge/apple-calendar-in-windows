package icloudcal;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/** Apple ID 와 앱 암호 입력 화면 */
final class LoginPane extends VBox {

    LoginPane(String message, BiFunction<String, String, CompletableFuture<Void>> onConnect,
              Runnable openAppleAccount, Runnable onQuit) {
        getStyleClass().add("form-pane");

        Label heading = new Label("iCloud 캘린더 연결");
        heading.getStyleClass().add("form-title");

        Label guide = new Label("Apple 계정 비밀번호가 아니라 앱 암호를 입력하십시오. "
                + "앱 암호는 Apple 계정 페이지의 [로그인 및 보안 → 앱 암호]에서 만들 수 있습니다.");
        guide.setWrapText(true);
        guide.getStyleClass().add("hint");
        Hyperlink link = new Hyperlink("account.apple.com 열기");
        link.setOnAction(e -> openAppleAccount.run());

        TextField appleId = new TextField();
        appleId.setPromptText("Apple ID (이메일)");
        PasswordField password = new PasswordField();
        password.setPromptText("앱 암호 (xxxx-xxxx-xxxx-xxxx)");

        Label error = new Label(message == null ? "" : message);
        error.getStyleClass().add("error");
        error.setWrapText(true);

        Label note = new Label("입력한 값은 이 PC의 Windows 사용자 계정으로 암호화해 저장합니다.");
        note.setWrapText(true);
        note.getStyleClass().add("hint");

        Button quit = new Button("종료");
        quit.getStyleClass().add("plain-button");
        quit.setOnAction(e -> onQuit.run());
        Button connect = new Button("연결");
        connect.getStyleClass().add("accent-button");
        connect.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, quit, connect);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        connect.setOnAction(e -> {
            String id = appleId.getText().strip();
            String pw = password.getText().strip();
            if (id.isEmpty() || pw.isEmpty()) {
                error.setText("Apple ID 와 앱 암호를 모두 입력하십시오.");
                return;
            }
            connect.setDisable(true);
            error.setText("연결하는 중…");
            onConnect.apply(id, pw).whenCompleteAsync((ok, ex) -> {
                if (ex != null) {
                    connect.setDisable(false);
                    error.setText(App.message(ex));
                }
            }, Platform::runLater);
        });

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        getChildren().addAll(heading, guide, link, appleId, password, error, grow, note, buttons);
        Platform.runLater(appleId::requestFocus);
    }
}
