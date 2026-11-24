package dev.agentworkshq.eval.core.model;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Checks {

    private Checks() {
    }

    static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw bad("field '" + field + "' must not be blank");
        }
        return value;
    }

    static String maxLength(String value, String field, int max) {
        if (value != null && value.length() > max) {
            throw bad("field '" + field + "' is longer than " + max + " characters");
        }
        return value;
    }

    static <T> List<T> list(List<T> values, String field, int max) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > max) {
            throw bad("field '" + field + "' has more than " + max + " entries");
        }
        for (T value : values) {
            if (value == null) {
                throw bad("field '" + field + "' must not contain null entries");
            }
        }
        return List.copyOf(values);
    }

    static Map<String, String> sortedMap(Map<String, String> values, String field, int max) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > max) {
            throw bad("field '" + field + "' has more than " + max + " entries");
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw bad("field '" + field + "' must not contain null keys or values");
            }
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
}
