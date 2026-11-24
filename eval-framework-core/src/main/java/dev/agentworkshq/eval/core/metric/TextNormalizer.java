package dev.agentworkshq.eval.core.metric;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(folded).replaceAll(" ").trim();
    }
}
