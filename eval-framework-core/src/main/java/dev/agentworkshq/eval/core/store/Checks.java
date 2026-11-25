package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;

final class Checks {

    private Checks() {
    }

    static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }

    static void notNegative(Number value, String field) {
        if (value != null && value.longValue() < 0) {
            throw bad("field '" + field + "' must not be negative");
        }
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw bad("field '" + field + "' must not be blank");
        }
        return value;
    }

    static void maxLength(String value, String field, int max) {
        if (value != null && value.length() > max) {
            throw bad("field '" + field + "' is longer than " + max + " characters");
        }
    }

    static String printable(String text, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && i < max; i++) {
            char c = text.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        if (text.length() > max) {
            sb.append("...");
        }
        return sb.toString();
    }
}
