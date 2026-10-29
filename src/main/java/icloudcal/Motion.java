package icloudcal;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** Windows 11 플라이아웃처럼 빠르게 시작해 부드럽게 멈추는 이동 + 페이드 */
final class Motion {
    /** Fluent 의 decelerate 곡선 cubic-bezier(0, 0, 0, 1) */
    static final Interpolator DECELERATE = Interpolator.SPLINE(0, 0, 0, 1);
    /** Fluent 의 accelerate 곡선 cubic-bezier(1, 0, 1, 1) */
    static final Interpolator ACCELERATE = Interpolator.SPLINE(1, 0, 1, 1);

    private Motion() {
    }

    /** node 를 세로로 fromY 만큼 떨어진 곳에서 제자리로 옮기며 나타나게 한다. */
    static ParallelTransition enter(Node node, double fromY, double millis) {
        return move(node, fromY, 0, 0, 1, millis, DECELERATE);
    }

    /** node 를 세로로 toY 만큼 옮기며 사라지게 한다. */
    static ParallelTransition exit(Node node, double toY, double millis) {
        return move(node, 0, toY, 1, 0, millis, ACCELERATE);
    }

    private static ParallelTransition move(Node node, double fromY, double toY, double fromOpacity, double toOpacity,
                                           double millis, Interpolator curve) {
        Duration duration = Duration.millis(millis);
        TranslateTransition slide = new TranslateTransition(duration, node);
        slide.setFromY(fromY);
        slide.setToY(toY);
        FadeTransition fade = new FadeTransition(duration, node);
        fade.setFromValue(fromOpacity);
        fade.setToValue(toOpacity);
        ParallelTransition both = new ParallelTransition(slide, fade);
        slide.setInterpolator(curve);
        fade.setInterpolator(curve);
        both.play();
        return both;
    }
}
