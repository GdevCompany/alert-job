package by.gdev.common.util;

/**
 * Приводит текст автоответа к виду, пригодному для UI и почты.
 */
public final class ReplyTextNormalizer {

    private ReplyTextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.indexOf('\\') < 0) {
            return text;
        }
        return text
                .replace("\\r\\n", "\r\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
