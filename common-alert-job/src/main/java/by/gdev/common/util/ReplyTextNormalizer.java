package by.gdev.common.util;

import java.util.regex.Pattern;

/**
 * Приводит текст автоответа к виду, пригодному для UI и почты.
 */
public final class ReplyTextNormalizer {

    private static final Pattern EMOJI = Pattern.compile(
            "[\\p{IsEmoji}\\p{IsEmoji_Presentation}\\p{IsEmoji_Modifier}\\p{IsEmoji_Component}]",
            Pattern.UNICODE_CHARACTER_CLASS);

    private ReplyTextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (text.indexOf('\\') >= 0) {
            result = result
                    .replace("\\r\\n", "\r\n")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
        return stripEmoji(result);
    }

    /**
     * Убирает emoji и связанные символы (ZWJ, variation selector), чтобы на биржах
     * и в логах не оставались «??» и обрывки вроде «??‍⚕️».
     */
    public static String stripEmoji(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutEmoji = EMOJI.matcher(text).replaceAll("");
        return withoutEmoji.replaceAll("(?m)^[ \\t]+", "");
    }
}
