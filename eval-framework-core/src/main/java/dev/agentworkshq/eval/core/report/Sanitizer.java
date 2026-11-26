package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.model.Echo;

final class Sanitizer {

    private Sanitizer() {
    }

    static String plain(String text, int maxChars) {
        return Echo.clean(text, maxChars);
    }
}
