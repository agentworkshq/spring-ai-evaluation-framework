package dev.agentworkshq.eval.core.model;

public final class Text {

    private static final String ELLIPSIS = "...";

    private Text() {
    }

    public static String truncate(String text, int max) {
        if (max < 0) {
            throw Checks.bad("limit must not be negative");
        }
        if (text == null || text.length() <= max) {
            return text;
        }
        if (max <= ELLIPSIS.length()) {
            return ELLIPSIS.substring(0, max);
        }
        int cut = max - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + ELLIPSIS;
    }
}
