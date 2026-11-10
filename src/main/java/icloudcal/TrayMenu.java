package icloudcal;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;

/**
 * 트레이 아이콘을 우클릭하면 작업 표시줄 바로 위에 뜨는 메뉴. Windows 기본 메뉴 대신 달력 창과 같은 테마·강조 색을 쓴다.
 * 바깥을 누르거나(포커스를 잃으면) Esc 를 누르면 닫힌다.
 */
final class TrayMenu {
    /** icon 은 Segoe Fluent Icons 글자이다. */
    record Item(String icon, String label, Runnable action) {
    }

    /** items 에 넣으면 그 자리에 구분선을 그린다. */
    static final Item SEPARATOR = new Item("", "", () -> { });

    private static final String ICON_FONT = Font.getFamilies().contains("Segoe Fluent Icons")
            ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";
    /** 그림자가 잘리지 않도록 메뉴 둘레에 두는 투명한 여백 */
    private static final double SHADOW = 12;
    /** 메뉴와 작업 표시줄·화면 가장자리 사이 */
    private static final double GAP = 6;

    private final Stage stage = new Stage();
    private final VBox menu = new VBox();

    TrayMenu(Stage owner, String stylesheet, List<Item> items) {
        stage.initOwner(owner);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);

        menu.getStyleClass().addAll("window", "tray-menu");
        for (Item item : items) {
            if (item == SEPARATOR) {
                Region separator = new Region();
                separator.getStyleClass().add("menu-separator");
                menu.getChildren().add(separator);
                continue;
            }
            Label icon = new Label(item.icon());
            icon.setStyle("-fx-font-family: '" + ICON_FONT + "';");
            icon.getStyleClass().add("menu-icon");
            Label text = new Label(item.label());
            HBox row = new HBox(12, icon, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("menu-item");
            row.setOnMouseClicked(e -> {
                stage.hide();
                item.action().run();
            });
            menu.getChildren().add(row);
        }

        StackPane frame = new StackPane(menu);
        frame.setPadding(new Insets(SHADOW));
        frame.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(frame, Color.TRANSPARENT);
        scene.getStylesheets().add(stylesheet);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.hide();
            }
        });
        stage.setScene(scene);
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                stage.hide();
            }
        });
    }

    /** 마우스 x 위치에서 시작해 작업 표시줄 바로 위에 띄운다. 화면 오른쪽을 넘으면 왼쪽으로 민다. */
    void show(double screenX, double screenY) {
        App.applyTheme(menu);
        menu.applyCss();
        menu.layout();
        double width = menu.prefWidth(-1);
        double height = menu.prefHeight(width);
        Rectangle2D area = Screen.getScreensForRectangle(screenX, screenY, 1, 1).stream()
                .findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double left = Math.max(area.getMinX() + GAP, Math.min(screenX, area.getMaxX() - width - GAP));
        double top = area.getMaxY() - height - GAP;
        stage.setX(left - SHADOW);
        stage.setY(top - SHADOW);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }
}
