package icloudcal;

import javafx.application.Application;

/** JavaFX 를 모듈이 아닌 클래스패스로 실행하려면 Application 을 상속하지 않은 진입점이 필요하다. */
public final class Launcher {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
