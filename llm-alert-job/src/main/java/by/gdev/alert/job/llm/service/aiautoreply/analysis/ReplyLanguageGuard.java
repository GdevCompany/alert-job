package by.gdev.alert.job.llm.service.aiautoreply.analysis;

import by.gdev.common.util.ReplyTextNormalizer;

/**
 * Чистит текст автоответа от «чужих» иероглифов перед отправкой на биржу.
 * <p>
 * <b>CJK</b> — общее IT-название для письменности <b>C</b>hinese / <b>J</b>apanese / <b>K</b>orean:
 * китайские, японские и корейские знаки (например {@code 以及形状参数} или {@code こんにちは}).
 * Это не латиница и не кириллица — в ответе на русский/английский заказ такие куски выглядят как сбой.
 * <p>
 * Нейросеть иногда вставляет их посреди нормального текста, хотя в промпте указано «отвечай на языке заказа».
 * Промпт помогает, но не всегда; этот класс — запасной фильтр в пайплайне {@link AiOrderAnalysisService}
 * после {@link ReplyTextNormalizer}.
 * <p>
 * Логика: из {@code reply} вырезаются китайские/японские/корейские вставки для <b>любого</b> заказа,
 * который сам написан не этими языками (русский, английский и т.д.).
 * Если заказ в основном на китайском, японском или корейском — ответ не режем.
 */
public final class ReplyLanguageGuard {

    private ReplyLanguageGuard() {
    }

    /**
     * true, если в заголовке/описании в основном китайские, японские или корейские знаки
     * (такой заказ не фильтруем).
     */
    public static boolean isPredominantlyCjk(String... textParts) {
        int cjk = 0;
        int cyrillicOrLatin = 0;
        for (String part : textParts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            for (int i = 0; i < part.length(); i++) {
                Character.UnicodeScript script = Character.UnicodeScript.of(part.charAt(i));
                if (isCjkScript(script)) {
                    cjk++;
                } else if (script == Character.UnicodeScript.CYRILLIC || script == Character.UnicodeScript.LATIN) {
                    cyrillicOrLatin++;
                }
            }
        }
        return cjk > 0 && cjk >= cyrillicOrLatin;
    }

    private static boolean isCjkScript(Character.UnicodeScript script) {
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * Удаляет из строки CJK-знаки и CJK-пунктуацию, подчищает лишние пробелы.
     */
    public static String stripCjk(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!shouldStripCodePoint(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        String withoutCjk = sb.toString();
        return withoutCjk.replaceAll("[ \\t]{2,}", " ").replaceAll(" *([,.;:!?])", "$1").trim();
    }

    private static boolean shouldStripCodePoint(int cp) {
        if (isCjkPunctuation(cp)) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /** CJK-пунктуация и fullwidth-знаки, не попадающие в {@code Script=Han}. */
    private static boolean isCjkPunctuation(int cp) {
        if (cp >= 0x3000 && cp <= 0x303F) {
            return true;
        }
        if (cp >= 0xFF01 && cp <= 0xFF0F) {
            return true;
        }
        if (cp >= 0xFF1A && cp <= 0xFF1F) {
            return true;
        }
        if (cp >= 0xFF3B && cp <= 0xFF40) {
            return true;
        }
        return cp >= 0xFF5B && cp <= 0xFF65;
    }

    /**
     * Убирает из ответа «левые» иероглифы, если заказ не на китайском/японском/корейском.
     */
    public static String alignReplyWithOrderLanguage(String reply, String orderTitle, String orderDescription) {
        if (reply == null || reply.isEmpty()) {
            return reply;
        }
        if (isPredominantlyCjk(orderTitle, orderDescription)) {
            return reply;
        }
        return stripCjk(reply);
    }
}
