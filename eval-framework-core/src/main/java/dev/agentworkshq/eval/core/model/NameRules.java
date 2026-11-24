package dev.agentworkshq.eval.core.model;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.regex.Pattern;

public final class NameRules {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private NameRules() {
    }

    public static boolean isValid(String value) {
        return value != null && NAME.matcher(value).matches();
    }

    public static void require(String value, String field) {
        if (!isValid(value)) {
            throw new ValidationException(ErrorMessages.EVAL_E010, "field '" + field + "' is not a valid name");
        }
    }
}
