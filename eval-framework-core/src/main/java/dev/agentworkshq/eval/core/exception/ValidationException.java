package dev.agentworkshq.eval.core.exception;

public class ValidationException extends BaseException {

    public ValidationException(ErrorMessages errorMessage, Object... args) {
        super(errorMessage, args);
    }

    public ValidationException(ErrorMessages errorMessage, Throwable cause, Object... args) {
        super(errorMessage, cause, args);
    }
}
