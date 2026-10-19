package by.gdev.common.util;

/**
 * Человекочитаемое представление длительности для логов.
 */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String formatDuration(long elapsedMs) {
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
        if (elapsedMs < 1000) {
            return elapsedMs + " мс";
        }
        if (elapsedMs < 60_000) {
            return String.format("%.2f сек", elapsedMs / 1000.0);
        }
        long totalSec = elapsedMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min < 60) {
            return sec > 0 ? min + " мин " + sec + " сек" : min + " мин";
        }
        long hours = min / 60;
        min = min % 60;
        if (min > 0 && sec > 0) {
            return hours + " ч " + min + " мин " + sec + " сек";
        }
        if (min > 0) {
            return hours + " ч " + min + " мин";
        }
        return hours + " ч " + sec + " сек";
    }
}
