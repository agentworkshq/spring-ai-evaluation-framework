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
}
