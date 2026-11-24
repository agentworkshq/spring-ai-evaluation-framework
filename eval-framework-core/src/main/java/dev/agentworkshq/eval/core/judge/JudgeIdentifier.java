package dev.agentworkshq.eval.core.judge;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalRun;

public final class JudgeIdentifier {

    private JudgeIdentifier() {
    }

    public static String require(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 100) {
            throw bad("judge identifier must have 1 to 100 characters");
        }
        if (identifier.chars().anyMatch(Character::isISOControl)) {
            throw bad("judge identifier must not contain control characters");
        }
        if (identifier.contains("://")) {
            throw bad("judge identifier must not be a URL");
        }
        try {
            EvalRun.requireSafeConfigKey(identifier);
        } catch (ValidationException e) {
            throw bad("judge identifier looks like a credential");
        }
        return identifier;
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }
}
