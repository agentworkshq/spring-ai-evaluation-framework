package dev.agentworkshq.eval.core.report;

final class MarkdownText {

    private MarkdownText() {
    }

    static String code(String text, int maxChars, boolean inTable) {
        String clean = Sanitizer.plain(text, maxChars);
        if (clean.isEmpty()) {
            clean = " ";
        }
        int longest = 0;
        int run = 0;
        for (int i = 0; i < clean.length(); i++) {
            run = clean.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        String body = inTable ? clean.replace("|", "\\|") : clean;
        boolean pad = clean.startsWith("`") || clean.endsWith("`");
        return pad ? fence + " " + body + " " + fence : fence + body + fence;
    }
}
