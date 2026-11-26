package dev.agentworkshq.eval.core.model;

public final class Echo {

    private static final int MAX_ECHO = 64;

    private Echo() {
    }

    public static String safe(String text) {
        return text == null ? "(null)" : clean(text, MAX_ECHO);
    }

    public static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastSpace = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isStripped(cp) || cp == ' ') {
                if (!lastSpace) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else {
                sb.appendCodePoint(cp);
                lastSpace = false;
            }
        }
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return Text.truncate(sb.toString(), maxChars);
    }

    private static boolean isStripped(int cp) {
        return cp < 0x20
                || cp >= 0x7F && cp <= 0x9F
                || cp == 0x2028 || cp == 0x2029
                || cp >= 0x202A && cp <= 0x202E
                || cp >= 0x2066 && cp <= 0x2069
                || cp == 0x200E || cp == 0x200F || cp == 0x061C
                || cp >= 0x200B && cp <= 0x200D
                || cp == 0x2060 || cp == 0xFEFF;
    }
}
