package gr.pnet.em.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps phone numbers out of logs, as camara-tep's Masking does. TEP has no phone number in its URLs, but a client
 * can still put one in a path, and an upstream error message can carry one.
 */
public final class Masking {

    // E.164 numbers, raw or URL-encoded ("%2B")
    private static final Pattern MSISDN = Pattern.compile("(\\+|%2B)(\\d{4})(\\d+)(\\d{2})");

    private Masking() {
    }

    /** Masks every phone number found in free text: {@code +306912345678} becomes {@code +3069******78}. */
    public static String maskAll(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MSISDN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String masked = m.group(1) + m.group(2) + "*".repeat(m.group(3).length()) + m.group(4);
            m.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        m.appendTail(out);
        return out.toString();
    }
}
